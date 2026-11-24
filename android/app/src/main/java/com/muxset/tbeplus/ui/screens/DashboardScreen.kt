package com.muxset.tbeplus.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.muxset.tbeplus.data.LogEntry
import com.muxset.tbeplus.data.LogKind
import com.muxset.tbeplus.data.ScanPreset
import com.muxset.tbeplus.data.TokenHealthState
import com.muxset.tbeplus.ui.MainViewModel
import com.muxset.tbeplus.ui.UiState
import com.muxset.tbeplus.ui.theme.LocalTbeColors
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

private enum class LogFilter(val label: String) {
    ALL("全部"),
    LIKED("新赞与完成"),
    WARN("警告与错误"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: UiState,
    onSelectPreset: (ScanPreset) -> Unit,
    onCustomDateChange: (String, String) -> Unit,
    onStartScan: () -> Unit,
    onStopTask: () -> Unit,
    onRefreshToken: () -> Unit,
    onCheckToken: () -> Unit,
    onExportReport: () -> Unit,
    onSwitchAccount: (String) -> Unit,
    onClearLogs: () -> Unit,
    onGoAccounts: () -> Unit,
    onShowToast: (String) -> Unit,
) {
    val c = LocalTbeColors.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var showAccountSheet by remember { mutableStateOf(false) }
    var pickingStartDate by remember { mutableStateOf(false) }
    var pickingEndDate by remember { mutableStateOf(false) }
    var logFilter by remember { mutableStateOf(LogFilter.ALL) }

    val active = state.activeAccount
    val currentMonth = YearMonth.now().toString()
    val monthly = active?.monthlyStats?.takeIf { it.month == currentMonth }
    val sweep = active?.sweepStats

    val filteredLogs = remember(state.logs, logFilter) {
        state.logs.filter { entry ->
            when (logFilter) {
                LogFilter.ALL -> true
                LogFilter.LIKED -> entry.kind == LogKind.LIKED
                LogFilter.WARN -> entry.kind == LogKind.WARN || entry.kind == LogKind.ERROR
            }
        }.asReversed()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1) 顶部品牌栏 + 状态胶囊 + 账号切换胶囊
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(
                                Brush.linearGradient(listOf(Color(0xFF4338CA), c.accent)),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Bolt,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Column {
                        Text(
                            text = "TBE+ 阅赞助手",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = c.textPrimary,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val statusColor = when {
                                state.isScanning || state.isExporting -> c.blue
                                state.config.autoBackgroundEnabled -> c.emerald
                                else -> c.textMuted
                            }
                            val statusTxt = when {
                                state.isScanning -> "扫描执行中"
                                state.isExporting -> "月报导出中"
                                state.tokenHealth == TokenHealthState.REFRESHING -> "凭据刷新中"
                                state.config.autoBackgroundEnabled -> "后台定时已开启"
                                else -> "空闲待机"
                            }
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(statusColor),
                            )
                            Text(
                                text = statusTxt,
                                fontSize = 11.5.sp,
                                color = c.textSecondary,
                            )
                        }
                    }
                }

                // 账号快速切换胶囊
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = c.bgCard,
                    border = BorderStroke(1.dp, c.border),
                    modifier = Modifier.clickable {
                        if (state.isBusy) {
                            onShowToast("任务执行中，暂不可切换账号")
                        } else if (state.config.accounts.isEmpty()) {
                            onGoAccounts()
                        } else {
                            showAccountSheet = true
                        }
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            text = active?.username ?: "未设账号",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = c.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            imageVector = Icons.Rounded.ExpandMore,
                            contentDescription = "切换账号",
                            tint = c.textMuted,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }

        // 2) 2x2 KPI 卡片网格
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    KpiCard(
                        modifier = Modifier.weight(1f),
                        title = "本月文章",
                        value = monthly?.monthlyTotal?.toString() ?: "---",
                        subtitle = "覆盖 ${state.config.channels.size} 个频道",
                        icon = Icons.Rounded.Description,
                        iconTint = c.blue,
                        iconBg = c.blueSoft,
                    )

                    // 本月点赞 + 48dp 环形进度圈
                    val total = monthly?.monthlyTotal ?: 0
                    val liked = monthly?.monthlyLiked ?: 0
                    val ratio = when {
                        monthly == null -> 0f
                        total == 0 -> 1f
                        else -> (liked.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    }
                    val animProgress by animateFloatAsState(
                        targetValue = ratio,
                        animationSpec = tween(durationMillis = 500),
                        label = "kpiRing",
                    )
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        color = c.bgCard,
                        border = BorderStroke(1.dp, c.border),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "本月点赞",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = c.textSecondary,
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = monthly?.monthlyLiked?.toString() ?: "---",
                                    fontSize = 23.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = c.emeraldText,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = when {
                                        monthly == null -> "待核算进度"
                                        total - liked <= 0 -> "已全部点赞"
                                        else -> "待赞 ${total - liked} 篇"
                                    },
                                    fontSize = 11.sp,
                                    color = c.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Box(
                                modifier = Modifier.size(48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val stroke = Stroke(width = 4.5.dp.toPx(), cap = StrokeCap.Round)
                                    drawArc(
                                        color = c.bgInset,
                                        startAngle = 0f,
                                        sweepAngle = 360f,
                                        useCenter = false,
                                        style = stroke,
                                    )
                                    drawArc(
                                        color = c.emerald,
                                        startAngle = -90f,
                                        sweepAngle = 360f * animProgress,
                                        useCenter = false,
                                        style = stroke,
                                    )
                                }
                                Text(
                                    text = if (monthly == null) "--" else "${(ratio * 100).toInt()}%",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = c.textPrimary,
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val lastRel = MainViewModel.formatRelativeTime(sweep?.lastSweepTime.orEmpty())
                    KpiCard(
                        modifier = Modifier.weight(1f),
                        title = "上次扫描",
                        value = lastRel,
                        subtitle = "新赞 ${sweep?.liked ?: 0} · 跳过 ${sweep?.skipped ?: 0}",
                        icon = Icons.Rounded.Schedule,
                        iconTint = c.purple,
                        iconBg = c.purpleSoft,
                    )

                    val (tokText, tokDotColor) = when (state.tokenHealth) {
                        TokenHealthState.VALID -> "有效" to c.emerald
                        TokenHealthState.EXPIRED -> "已过期" to c.rose
                        TokenHealthState.REFRESHING -> "刷新中" to c.amber
                        TokenHealthState.CHECKING -> "检测中" to c.amber
                        TokenHealthState.UNSET -> "未设置" to c.textMuted
                        TokenHealthState.UNKNOWN -> "未知" to c.amber
                    }
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onCheckToken() },
                        shape = RoundedCornerShape(16.dp),
                        color = c.bgCard,
                        border = BorderStroke(1.dp, c.border),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "凭据状态",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = c.textSecondary,
                                )
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(c.amberSoft),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Key,
                                        contentDescription = null,
                                        tint = c.amber,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(tokDotColor),
                                )
                                Text(
                                    text = tokText,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = c.textPrimary,
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "点击立即校验 / 续期",
                                fontSize = 11.sp,
                                color = c.textMuted,
                            )
                        }
                    }
                }
            }
        }

        // 3) 扫描控制卡
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = c.bgCard,
                border = BorderStroke(1.dp, c.border),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "扫描范围",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.textPrimary,
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        ScanPreset.entries.forEach { preset ->
                            val selected = preset == state.selectedPreset
                            Surface(
                                shape = RoundedCornerShape(9.dp),
                                color = if (selected) c.accent else c.bgInset,
                                border = BorderStroke(1.dp, if (selected) c.accent else c.border),
                                modifier = Modifier.clickable(enabled = !state.isScanning && !state.isExporting) {
                                    onSelectPreset(preset)
                                },
                            ) {
                                Text(
                                    text = preset.label,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    fontSize = 12.5.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (selected) Color.White else c.textSecondary,
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = state.selectedPreset == ScanPreset.CUSTOM) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(
                                onClick = { pickingStartDate = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(9.dp),
                            ) {
                                Text(
                                    text = "起: ${state.customStartDate}",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            OutlinedButton(
                                onClick = { pickingEndDate = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(9.dp),
                            ) {
                                Text(
                                    text = "止: ${state.customEndDate}",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (state.isScanning || state.isExporting) {
                        Button(
                            onClick = onStopTask,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = RoundedCornerShape(11.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = c.rose,
                                contentColor = Color.White,
                            ),
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "停止当前任务", fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Button(
                            onClick = onStartScan,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = RoundedCornerShape(11.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = c.emerald,
                                contentColor = Color.White,
                            ),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "开始扫描点赞", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    AnimatedVisibility(visible = (state.isScanning || state.isExporting) && state.scanProgress != null) {
                        val prog = state.scanProgress
                        if (prog != null) {
                            Column(modifier = Modifier.padding(top = 12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = prog.message,
                                        fontSize = 11.5.sp,
                                        color = c.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = "${(prog.ratio * 100).toInt()}%",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = c.accentText,
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { prog.ratio },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(999.dp)),
                                    color = c.accent,
                                    trackColor = c.bgInset,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = onRefreshToken,
                            enabled = !state.isBusy,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "刷新凭据", fontSize = 12.sp)
                        }
                        FilledTonalButton(
                            onClick = onExportReport,
                            enabled = !state.isBusy,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "导出月报", fontSize = 12.sp)
                        }
                        if (state.lastReportFile != null) {
                            FilledTonalButton(
                                onClick = {
                                    runCatching {
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            state.lastReportFile,
                                        )
                                        val share = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/csv"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(share, "分享月度点赞报告"))
                                    }.onFailure {
                                        onShowToast("分享报告失败: ${it.message}")
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Icon(Icons.Rounded.Share, contentDescription = "分享 CSV", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }

        // 4) 运行动态控制台
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = c.bgCard,
                border = BorderStroke(1.dp, c.border),
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "运行动态",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = c.textPrimary,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    val txt = filteredLogs.reversed().joinToString("\n") {
                                        "[${it.time}] [${it.tag}] ${it.message}"
                                    }
                                    if (txt.isNotBlank()) {
                                        clipboard.setText(AnnotatedString(txt))
                                        onShowToast("已复制当前运行动态")
                                    }
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.ContentCopy,
                                    contentDescription = "复制日志",
                                    tint = c.textMuted,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            IconButton(
                                onClick = onClearLogs,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = "清空日志",
                                    tint = c.textMuted,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp)
                            .padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        LogFilter.entries.forEach { f ->
                            val selected = f == logFilter
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = if (selected) c.accentSoft else c.bgInset,
                                border = BorderStroke(1.dp, if (selected) c.accent.copy(alpha = 0.4f) else c.borderSubtle),
                                modifier = Modifier.clickable { logFilter = f },
                            ) {
                                Text(
                                    text = f.label,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    fontSize = 11.5.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) c.accentText else c.textSecondary,
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(c.bgInset)
                            .border(0.5.dp, c.borderSubtle)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        if (filteredLogs.isEmpty()) {
                            Text(
                                text = "暂无符合条件的运行记录",
                                fontSize = 12.sp,
                                color = c.textMuted,
                                modifier = Modifier.padding(vertical = 22.dp),
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                filteredLogs.take(80).forEach { entry ->
                                    LogRowItem(entry)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 账号快切 BottomSheet
    if (showAccountSheet) {
        ModalBottomSheet(onDismissRequest = { showAccountSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "切换当前账号",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = c.textPrimary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                state.config.accounts.forEach { acc ->
                    val isCurrent = acc.username == active?.username
                    val cacheCount = state.cacheCounts[acc.username] ?: 0
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isCurrent) c.accentSoft else c.bgInset,
                        border = BorderStroke(1.dp, if (isCurrent) c.accent else c.border),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showAccountSheet = false
                                onSwitchAccount(acc.username)
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    text = acc.username,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = c.textPrimary,
                                )
                                Text(
                                    text = "本地已赞缓存 $cacheCount 篇",
                                    fontSize = 11.5.sp,
                                    color = c.textMuted,
                                )
                            }
                            if (isCurrent) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = "当前",
                                    tint = c.accent,
                                )
                            }
                        }
                    }
                }
                TextButton(
                    onClick = {
                        showAccountSheet = false
                        onGoAccounts()
                    },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("管理或添加更多账号")
                }
            }
        }
    }

    if (pickingStartDate) {
        val initMillis = runCatching {
            LocalDate.parse(state.customStartDate).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrNull()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initMillis)
        DatePickerDialog(
            onDismissRequest = { pickingStartDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { ms ->
                            val picked = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()
                            onCustomDateChange(picked, state.customEndDate)
                        }
                        pickingStartDate = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { pickingStartDate = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (pickingEndDate) {
        val initMillis = runCatching {
            LocalDate.parse(state.customEndDate).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrNull()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initMillis)
        DatePickerDialog(
            onDismissRequest = { pickingEndDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { ms ->
                            val picked = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()
                            onCustomDateChange(state.customStartDate, picked)
                        }
                        pickingEndDate = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { pickingEndDate = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun KpiCard(
    modifier: Modifier,
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
) {
    val c = LocalTbeColors.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = c.bgCard,
        border = BorderStroke(1.dp, c.border),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.textSecondary,
                )
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(iconBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold,
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = c.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class LogBadgeStyle(
    val label: String,
    val bg: Color,
    val fg: Color,
    val msgColor: Color,
)

@Composable
private fun LogRowItem(entry: LogEntry) {
    val c = LocalTbeColors.current
    val style = when (entry.kind) {
        LogKind.LIKED -> LogBadgeStyle("已赞", c.emeraldSoft, c.emeraldText, c.emeraldText)
        LogKind.SKIP -> LogBadgeStyle("跳过", c.borderSubtle, c.textMuted, c.textMuted)
        LogKind.WARN -> LogBadgeStyle("警告", c.amberSoft, c.amberText, c.amberText)
        LogKind.ERROR -> LogBadgeStyle("错误", c.roseSoft, c.roseText, c.roseText)
        LogKind.SYSTEM -> LogBadgeStyle("系统", c.accentSoft, c.accentText, c.accentText)
        LogKind.INFO -> LogBadgeStyle("信息", c.borderSubtle, c.textSecondary, c.textPrimary)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            text = entry.time,
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            color = c.textMuted,
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(style.bg)
                .padding(horizontal = 5.dp, vertical = 1.dp),
        ) {
            Text(
                text = style.label,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = style.fg,
            )
        }
        Text(
            text = "[${entry.tag}] ${entry.message}",
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            color = style.msgColor,
            modifier = Modifier.weight(1f),
        )
    }
}
