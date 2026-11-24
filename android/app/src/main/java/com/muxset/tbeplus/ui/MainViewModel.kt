package com.muxset.tbeplus.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.muxset.tbeplus.data.AccountRecord
import com.muxset.tbeplus.data.AppConfig
import com.muxset.tbeplus.data.AppRepository
import com.muxset.tbeplus.data.AuthResult
import com.muxset.tbeplus.data.ChannelConfig
import com.muxset.tbeplus.data.DEFAULT_CHANNELS
import com.muxset.tbeplus.data.LogEntry
import com.muxset.tbeplus.data.LogKind
import com.muxset.tbeplus.data.ScanPreset
import com.muxset.tbeplus.data.ScanProgress
import com.muxset.tbeplus.data.ThemeMode
import com.muxset.tbeplus.data.TokenHealthState
import com.muxset.tbeplus.engine.AuthExpiredException
import com.muxset.tbeplus.engine.ReportExporter
import com.muxset.tbeplus.engine.SweepEngine
import com.muxset.tbeplus.engine.TbeaClient
import com.muxset.tbeplus.worker.WorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

sealed class PostAuthAction {
    data object None : PostAuthAction()
    data class RunScan(val startDate: String, val endDate: String) : PostAuthAction()
    data class RunExport(val month: YearMonth) : PostAuthAction()
    data object RunDryCheck : PostAuthAction()
}

data class AuthSheetRequest(
    val username: String,
    val password: String,
    val channels: List<ChannelConfig>,
    val isAccountVerify: Boolean = false,
    val postAction: PostAuthAction = PostAuthAction.None,
)

data class UiState(
    val config: AppConfig = AppConfig(),
    val cacheCounts: Map<String, Int> = emptyMap(),
    val tokenHealth: TokenHealthState = TokenHealthState.UNKNOWN,
    val isScanning: Boolean = false,
    val isExporting: Boolean = false,
    val scanProgress: ScanProgress? = null,
    val selectedPreset: ScanPreset = ScanPreset.MONTH,
    val customStartDate: String = LocalDate.now().withDayOfMonth(1).toString(),
    val customEndDate: String = LocalDate.now().toString(),
    val logs: List<LogEntry> = emptyList(),
    val lastReportFile: File? = null,
    val pendingAuth: AuthSheetRequest? = null,
    val lastAuthError: String? = null,
    val snackbarMessage: String? = null,
) {
    val activeAccount: AccountRecord?
        get() = config.accounts.getOrNull(config.activeAccount)

    val isBusy: Boolean
        get() = isScanning || isExporting || pendingAuth != null
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = AppRepository(application.filesDir)
    private val client = TbeaClient()
    private val sweepEngine = SweepEngine(repo, client)
    private val reportExporter = ReportExporter(repo, client)

    private val _uiState = MutableStateFlow(
        UiState(
            config = repo.getConfigSnapshot(),
            cacheCounts = repo.cacheCountsFlow.value,
        ),
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val logSeq = AtomicLong(0L)
    private val cancelFlag = AtomicBoolean(false)
    private var activeTaskJob: Job? = null

    init {
        viewModelScope.launch {
            repo.configFlow.collect { cfg ->
                _uiState.update { it.copy(config = cfg) }
            }
        }
        viewModelScope.launch {
            repo.cacheCountsFlow.collect { counts ->
                _uiState.update { it.copy(cacheCounts = counts) }
            }
        }

        val initialCfg = repo.getConfigSnapshot()
        WorkScheduler.syncSchedule(
            application,
            initialCfg.autoBackgroundEnabled && initialCfg.accounts.isNotEmpty(),
            initialCfg.scanIntervalHours,
        )

        appendLog(LogKind.SYSTEM, "系统", "TBE+ 阅赞助手 Android 版已就绪")
        if (initialCfg.accounts.isNotEmpty()) {
            startupCheck()
        } else {
            _uiState.update { it.copy(tokenHealth = TokenHealthState.UNSET) }
        }
    }

    private fun appendLog(kind: LogKind, tag: String, msg: String) {
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val entry = LogEntry(
            id = logSeq.incrementAndGet(),
            time = time,
            kind = kind,
            tag = tag,
            message = msg,
        )
        _uiState.update { state ->
            val updated = (state.logs + entry).takeLast(500)
            state.copy(logs = updated)
        }
    }

    private fun parseLogWithTag(kind: LogKind, raw: String) {
        val trimmed = raw.trim()
        val match = Regex("^\\[([^\\]]+)]\\s*(.*)$").find(trimmed)
        if (match != null) {
            appendLog(kind, match.groupValues[1], match.groupValues[2])
        } else {
            appendLog(kind, "运行", trimmed)
        }
    }

    fun consumeSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    private fun toast(msg: String) {
        _uiState.update { it.copy(snackbarMessage = msg) }
    }

    fun startupCheck(targetUsername: String? = null) {
        val user = targetUsername ?: repo.getActiveUsername()
        if (user.isNullOrBlank()) {
            _uiState.update { it.copy(tokenHealth = TokenHealthState.UNSET) }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val cfg = repo.getConfigSnapshot()
            val (_, pwd) = repo.getCredentials(user)
            if (pwd.isNullOrBlank()) {
                if (repo.getActiveUsername() == user) {
                    _uiState.update { it.copy(tokenHealth = TokenHealthState.UNSET) }
                }
                return@launch
            }

            if (repo.getActiveUsername() == user) {
                _uiState.update { it.copy(tokenHealth = TokenHealthState.CHECKING) }
            }

            val tokOk = client.checkTokenValid(repo.getToken(user), cfg.disableSystemProxy)
            val sesOk = client.checkEjiaSessionValid(repo.getEjiaCookies(user), cfg.disableSystemProxy)
            if (repo.getActiveUsername() != user) return@launch

            when {
                tokOk == true && sesOk != false -> {
                    _uiState.update { it.copy(tokenHealth = TokenHealthState.VALID) }
                    appendLog(LogKind.LIKED, "自检", "($user) Token 与会话状态有效")
                    if (cfg.autoCheckMonthlyTotal && !_uiState.value.isScanning) {
                        runDryRun(user)
                    }
                }
                tokOk == null && sesOk == null -> {
                    _uiState.update { it.copy(tokenHealth = TokenHealthState.UNKNOWN) }
                    appendLog(LogKind.WARN, "自检", "网络连接波动，暂无法校验凭据状态")
                }
                else -> {
                    _uiState.update { it.copy(tokenHealth = TokenHealthState.EXPIRED) }
                    appendLog(LogKind.WARN, "自检", "($user) 登录凭据已过期，正在唤起自动续期...")
                    val next = if (cfg.autoCheckMonthlyTotal) PostAuthAction.RunDryCheck else PostAuthAction.None
                    requestWebViewAuth(user, pwd, isVerify = false, postAction = next)
                }
            }
        }
    }

    private fun runDryRun(username: String) {
        val today = LocalDate.now()
        val sd = today.withDayOfMonth(1).toString()
        val ed = today.toString()
        val outcome = sweepEngine.runSweep(
            startDate = sd,
            endDate = ed,
            dryRun = true,
            username = username,
            onLog = { k, m -> parseLogWithTag(k, m) },
        )
        if (outcome.authExpired && repo.getActiveUsername() == username) {
            val (_, pwd) = repo.getCredentials(username)
            if (!pwd.isNullOrBlank()) {
                requestWebViewAuth(username, pwd, isVerify = false, postAction = PostAuthAction.RunDryCheck)
            }
        }
    }

    fun checkTokenHealth() {
        if (_uiState.value.isBusy) {
            toast("任务运行中，请稍候")
            return
        }
        startupCheck()
    }

    fun selectPreset(preset: ScanPreset) {
        _uiState.update { it.copy(selectedPreset = preset) }
    }

    fun setCustomDateRange(startDate: String, endDate: String) {
        _uiState.update { it.copy(customStartDate = startDate, customEndDate = endDate) }
    }

    fun startScan() {
        val st = _uiState.value
        if (st.isScanning || st.isExporting) {
            toast("已有任务正在运行")
            return
        }
        val active = st.activeAccount
        if (active == null) {
            toast("请先添加账号")
            return
        }

        val (sd, ed) = st.selectedPreset.dateRange() ?: (st.customStartDate to st.customEndDate)
        if (sd > ed) {
            toast("起始日期不能晚于结束日期")
            return
        }

        executeScanInternal(sd, ed, active.username)
    }

    private fun executeScanInternal(startDate: String, endDate: String, targetUser: String) {
        cancelFlag.set(false)
        _uiState.update {
            it.copy(
                isScanning = true,
                scanProgress = ScanProgress(stage = "prepare", message = "正在准备扫描 $startDate ~ $endDate"),
            )
        }

        activeTaskJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val outcome = sweepEngine.runSweep(
                    startDate = startDate,
                    endDate = endDate,
                    dryRun = false,
                    username = targetUser,
                    isCancelled = { cancelFlag.get() },
                    onLog = { k, m -> parseLogWithTag(k, m) },
                    onProgress = { prog ->
                        _uiState.update { it.copy(scanProgress = prog) }
                    },
                )
                if (outcome.authExpired && !cancelFlag.get()) {
                    _uiState.update { it.copy(tokenHealth = TokenHealthState.EXPIRED) }
                    val (_, pwd) = repo.getCredentials(targetUser)
                    if (!pwd.isNullOrBlank()) {
                        requestWebViewAuth(
                            username = targetUser,
                            password = pwd,
                            isVerify = false,
                            postAction = PostAuthAction.RunScan(startDate, endDate),
                        )
                    } else {
                        toast("凭据失效且缺少密码，请前往账号管理更新")
                    }
                } else if (!outcome.authExpired && !outcome.cancelled) {
                    _uiState.update { it.copy(tokenHealth = TokenHealthState.VALID) }
                    toast("扫描完成：新赞 ${outcome.liked} 篇，跳过 ${outcome.skipped} 篇")
                }
            } finally {
                _uiState.update { it.copy(isScanning = false, scanProgress = null) }
            }
        }
    }

    fun stopCurrentTask() {
        cancelFlag.set(true)
        appendLog(LogKind.WARN, "系统", "已发送停止信号，等待当前请求结束...")
    }

    fun exportMonthlyReport() {
        val st = _uiState.value
        if (st.isScanning || st.isExporting) {
            toast("请等待当前任务完成")
            return
        }
        val active = st.activeAccount
        if (active == null) {
            toast("请先添加账号")
            return
        }
        executeExportInternal(YearMonth.now(), active.username)
    }

    private fun executeExportInternal(month: YearMonth, targetUser: String) {
        cancelFlag.set(false)
        _uiState.update {
            it.copy(
                isExporting = true,
                scanProgress = ScanProgress(stage = "export", message = "正在准备导出 $month 月报..."),
            )
        }

        activeTaskJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = reportExporter.exportMonthlyReport(
                    targetMonth = month,
                    username = targetUser,
                    isCancelled = { cancelFlag.get() },
                    onLog = { k, m -> parseLogWithTag(k, m) },
                    onProgress = { prog -> _uiState.update { it.copy(scanProgress = prog) } },
                )
                if (file != null) {
                    _uiState.update { it.copy(lastReportFile = file) }
                    toast("月报已生成：${file.name}")
                }
            } catch (_: AuthExpiredException) {
                if (!cancelFlag.get()) {
                    _uiState.update { it.copy(tokenHealth = TokenHealthState.EXPIRED) }
                    val (_, pwd) = repo.getCredentials(targetUser)
                    if (!pwd.isNullOrBlank()) {
                        requestWebViewAuth(
                            username = targetUser,
                            password = pwd,
                            isVerify = false,
                            postAction = PostAuthAction.RunExport(month),
                        )
                    }
                }
            } catch (e: Exception) {
                appendLog(LogKind.ERROR, "报告导出", "导出异常: ${e.message}")
                toast("导出失败：${e.message}")
            } finally {
                _uiState.update { it.copy(isExporting = false, scanProgress = null) }
            }
        }
    }

    fun manualRefreshToken(targetUsername: String? = null) {
        if (_uiState.value.isBusy) {
            toast("任务运行中，请稍候")
            return
        }
        val user = targetUsername ?: repo.getActiveUsername()
        if (user.isNullOrBlank()) {
            toast("请先添加账号")
            return
        }
        val (_, pwd) = repo.getCredentials(user)
        if (pwd.isNullOrBlank()) {
            toast("该账号未保存密码")
            return
        }
        requestWebViewAuth(user, pwd, isVerify = false, postAction = PostAuthAction.None)
    }

    fun startAddOrUpdateAccount(username: String, password: String) {
        val u = username.trim()
        val p = password.trim()
        if (u.isBlank() || p.isBlank()) {
            toast("请输入完整的工号和密码")
            return
        }
        if (_uiState.value.isBusy) {
            toast("当前正在执行任务，请先停止")
            return
        }
        requestWebViewAuth(u, p, isVerify = true, postAction = PostAuthAction.RunDryCheck)
    }

    private fun requestWebViewAuth(
        username: String,
        password: String,
        isVerify: Boolean,
        postAction: PostAuthAction,
    ) {
        _uiState.update {
            it.copy(
                tokenHealth = TokenHealthState.REFRESHING,
                lastAuthError = null,
                pendingAuth = AuthSheetRequest(
                    username = username,
                    password = password,
                    channels = repo.getConfigSnapshot().channels,
                    isAccountVerify = isVerify,
                    postAction = postAction,
                ),
            )
        }
    }

    fun onWebViewAuthSuccess(result: AuthResult) {
        val req = _uiState.value.pendingAuth ?: return
        _uiState.update {
            it.copy(
                pendingAuth = null,
                lastAuthError = null,
                tokenHealth = TokenHealthState.VALID,
            )
        }

        if (req.isAccountVerify) {
            repo.addOrUpdateAccount(req.username, req.password, auth = result, activate = true)
            appendLog(LogKind.LIKED, "账号验证", "账号 ${req.username} 登录验证成功并已设为当前账号")
            toast("账号 ${req.username} 添加并验证成功")
            val cfg = repo.getConfigSnapshot()
            WorkScheduler.syncSchedule(
                getApplication(),
                cfg.autoBackgroundEnabled,
                cfg.scanIntervalHours,
            )
        } else {
            repo.saveToken(result, username = req.username)
            appendLog(LogKind.LIKED, "凭据更新", "(${req.username}) 凭据已刷新并保存")
            toast("凭据刷新成功")
        }

        when (val next = req.postAction) {
            is PostAuthAction.RunScan -> executeScanInternal(next.startDate, next.endDate, req.username)
            is PostAuthAction.RunExport -> executeExportInternal(next.month, req.username)
            is PostAuthAction.RunDryCheck -> viewModelScope.launch(Dispatchers.IO) {
                if (repo.getConfigSnapshot().autoCheckMonthlyTotal) {
                    runDryRun(req.username)
                }
            }
            PostAuthAction.None -> Unit
        }
    }

    fun onWebViewAuthError(errorMsg: String) {
        _uiState.update {
            it.copy(
                pendingAuth = null,
                lastAuthError = errorMsg,
                tokenHealth = if (repo.getActiveUsername() != null) TokenHealthState.EXPIRED else TokenHealthState.UNSET,
            )
        }
        appendLog(LogKind.ERROR, "认证登录", errorMsg)
        toast(errorMsg)
    }

    fun dismissWebViewAuth() {
        _uiState.update {
            it.copy(
                pendingAuth = null,
                tokenHealth = if (repo.getActiveUsername() != null) TokenHealthState.UNKNOWN else TokenHealthState.UNSET,
            )
        }
    }

    fun switchAccount(username: String) {
        if (_uiState.value.isBusy) {
            toast("任务运行中，请先停止后再切换账号")
            return
        }
        if (repo.switchAccount(username)) {
            appendLog(LogKind.INFO, "账号切换", "已切换至账号 $username（缓存 ${repo.getCacheSize(username)} 篇）")
            startupCheck(username)
        }
    }

    fun removeAccount(index: Int) {
        if (_uiState.value.isBusy) {
            toast("任务运行中，请先停止后再删除账号")
            return
        }
        val removed = repo.removeAccount(index) ?: return
        appendLog(LogKind.WARN, "账号管理", "已删除账号 $removed 及其本地缓存")
        toast("已删除账号 $removed")
        val cfg = repo.getConfigSnapshot()
        if (cfg.accounts.isEmpty()) {
            WorkScheduler.syncSchedule(getApplication(), false, cfg.scanIntervalHours)
            _uiState.update { it.copy(tokenHealth = TokenHealthState.UNSET) }
        } else {
            startupCheck()
        }
    }

    fun clearAccountCache(username: String) {
        if (_uiState.value.isScanning) {
            toast("扫描进行中，请稍后再清空缓存")
            return
        }
        val count = repo.clearCache(username)
        appendLog(LogKind.INFO, "缓存管理", "已清空账号 $username 的 $count 条已赞缓存")
        toast("已清空 $count 条缓存记录")
    }

    fun setThemeMode(mode: ThemeMode) {
        repo.saveAppearanceMode(mode)
    }

    fun saveSettings(
        scanIntervalHours: Double,
        autoBackgroundEnabled: Boolean,
        autoCheckMonthlyTotal: Boolean,
        disableSystemProxy: Boolean,
        multiAccountAuto: Boolean,
        channels: List<ChannelConfig>,
    ) {
        repo.updateSettings(
            scanIntervalHours = scanIntervalHours,
            autoBackgroundEnabled = autoBackgroundEnabled,
            autoCheckMonthlyTotal = autoCheckMonthlyTotal,
            disableSystemProxy = disableSystemProxy,
            multiAccountAuto = multiAccountAuto,
            channels = channels.ifEmpty { DEFAULT_CHANNELS },
        )
        val hasAcc = repo.getConfigSnapshot().accounts.isNotEmpty()
        WorkScheduler.syncSchedule(
            getApplication(),
            autoBackgroundEnabled && hasAcc,
            scanIntervalHours,
        )
        appendLog(LogKind.INFO, "系统设置", "设置已保存")
        toast("设置已保存")
    }

    fun clearLogs() {
        _uiState.update { it.copy(logs = emptyList()) }
    }

    companion object {
        fun formatRelativeTime(timeStr: String): String {
            if (timeStr.isBlank()) return "从未"
            return try {
                val dt = LocalDateTime.parse(timeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                val mins = ChronoUnit.MINUTES.between(dt, LocalDateTime.now()).coerceAtLeast(0L)
                when {
                    mins < 1 -> "刚刚"
                    mins < 60 -> "${mins}分钟前"
                    mins < 1440 -> "${mins / 60}小时前"
                    else -> "${mins / 1440}天前"
                }
            } catch (_: Exception) {
                timeStr.take(10)
            }
        }
    }
}
