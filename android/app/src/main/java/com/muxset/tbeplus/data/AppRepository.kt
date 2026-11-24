package com.muxset.tbeplus.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * 线程安全的配置与账号隔离缓存仓库。
 * 所有账号读写均支持显式传入 username，彻底杜绝后台任务与界面切换时的跨账号串号污染。
 */
class AppRepository(val filesDir: File) {

    private val lock = Any()
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val configFile = File(filesDir, "config.json")
    private val cacheFile = File(filesDir, "liked_articles.json")

    private var config: AppConfig
    private var likedCache: MutableMap<String, MutableSet<String>>
    private var cacheDirty: Boolean = false

    private val _configFlow: MutableStateFlow<AppConfig>
    val configFlow: StateFlow<AppConfig>

    private val _cacheCountsFlow: MutableStateFlow<Map<String, Int>>
    val cacheCountsFlow: StateFlow<Map<String, Int>>

    init {
        if (!filesDir.exists()) {
            filesDir.mkdirs()
        }
        config = loadConfigFromDisk()
        likedCache = loadCacheFromDisk()
        _configFlow = MutableStateFlow(config)
        _cacheCountsFlow = MutableStateFlow(computeCacheCounts())
        configFlow = _configFlow.asStateFlow()
        cacheCountsFlow = _cacheCountsFlow.asStateFlow()
    }

    private fun loadConfigFromDisk(): AppConfig {
        if (!configFile.exists()) {
            return AppConfig()
        }
        return try {
            json.decodeFromString<AppConfig>(configFile.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            AppConfig()
        }
    }

    private fun loadCacheFromDisk(): MutableMap<String, MutableSet<String>> {
        if (!cacheFile.exists()) {
            return mutableMapOf()
        }
        return try {
            val decoded = json.decodeFromString<Map<String, List<String>>>(
                cacheFile.readText(Charsets.UTF_8),
            )
            decoded.mapValuesTo(mutableMapOf()) { (_, list) -> list.toMutableSet() }
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun atomicWrite(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.${Thread.currentThread().id}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun saveConfigLocked() {
        atomicWrite(configFile, json.encodeToString(config))
        _configFlow.value = config
    }

    private fun saveCacheLocked() {
        val out = likedCache.mapValues { (_, set) -> set.sorted() }
        atomicWrite(cacheFile, json.encodeToString(out))
        cacheDirty = false
        _cacheCountsFlow.value = computeCacheCounts()
    }

    private fun computeCacheCounts(): Map<String, Int> =
        likedCache.mapValues { (_, set) -> set.size }

    private fun resolveIndexLocked(username: String? = null): Int {
        val accounts = config.accounts
        if (username != null) {
            return accounts.indexOfFirst { it.username == username }
        }
        val active = config.activeAccount
        return if (active in accounts.indices) active else -1
    }

    fun getConfigSnapshot(): AppConfig = synchronized(lock) { config }

    fun getActiveUsername(): String? = synchronized(lock) {
        val idx = resolveIndexLocked(null)
        if (idx >= 0) config.accounts[idx].username else null
    }

    fun resolveAccount(username: String? = null): AccountRecord? = synchronized(lock) {
        val idx = resolveIndexLocked(username)
        if (idx >= 0) config.accounts[idx] else null
    }

    fun getCredentials(username: String? = null): Pair<String?, String?> {
        val acc = resolveAccount(username)
        return acc?.username to acc?.password
    }

    fun getToken(username: String? = null): String = resolveAccount(username)?.tbeaArtToken.orEmpty()

    fun getEjiaCookies(username: String? = null): Map<String, String> =
        resolveAccount(username)?.ejiaCookies ?: emptyMap()

    fun addOrUpdateAccount(
        username: String,
        password: String,
        auth: AuthResult? = null,
        activate: Boolean = true,
    ): Int = synchronized(lock) {
        val cleanUser = username.trim()
        val cleanPass = password.trim()
        val list = config.accounts.toMutableList()
        val existingIdx = list.indexOfFirst { it.username == cleanUser }
        val nowSec = System.currentTimeMillis() / 1000L

        val idx = if (existingIdx >= 0) {
            val prev = list[existingIdx]
            list[existingIdx] = prev.copy(
                password = cleanPass,
                tbeaArtToken = auth?.token ?: prev.tbeaArtToken,
                ejiaCookies = auth?.ejiaCookies ?: prev.ejiaCookies,
                ejiaUserId = auth?.ejiaUserId?.ifBlank { prev.ejiaUserId } ?: prev.ejiaUserId,
                tokenRefreshTime = if (auth != null) nowSec else prev.tokenRefreshTime,
            )
            existingIdx
        } else {
            list.add(
                AccountRecord(
                    username = cleanUser,
                    password = cleanPass,
                    tbeaArtToken = auth?.token.orEmpty(),
                    ejiaCookies = auth?.ejiaCookies ?: emptyMap(),
                    ejiaUserId = auth?.ejiaUserId.orEmpty(),
                    tokenRefreshTime = if (auth != null) nowSec else 0L,
                ),
            )
            list.lastIndex
        }

        val nextActive = when {
            activate -> idx
            config.activeAccount !in list.indices -> 0
            else -> config.activeAccount
        }
        config = config.copy(accounts = list, activeAccount = nextActive)
        saveConfigLocked()
        idx
    }

    fun saveToken(auth: AuthResult, username: String? = null) {
        synchronized(lock) {
            val idx = resolveIndexLocked(username)
            if (idx < 0) return
            val list = config.accounts.toMutableList()
            val prev = list[idx]
            list[idx] = prev.copy(
                tbeaArtToken = auth.token,
                ejiaCookies = auth.ejiaCookies,
                ejiaUserId = auth.ejiaUserId.ifBlank { prev.ejiaUserId },
                tokenRefreshTime = System.currentTimeMillis() / 1000L,
            )
            config = config.copy(accounts = list)
            saveConfigLocked()
        }
    }

    fun switchAccount(username: String): Boolean = synchronized(lock) {
        val idx = config.accounts.indexOfFirst { it.username == username }
        if (idx < 0) return false
        config = config.copy(activeAccount = idx)
        saveConfigLocked()
        true
    }

    fun removeAccount(index: Int): String? = synchronized(lock) {
        if (index !in config.accounts.indices) return null
        val list = config.accounts.toMutableList()
        val removed = list.removeAt(index)
        val nextActive = when {
            list.isEmpty() -> -1
            config.activeAccount == index -> index.coerceAtMost(list.lastIndex)
            config.activeAccount > index -> config.activeAccount - 1
            else -> config.activeAccount
        }
        config = config.copy(accounts = list, activeAccount = nextActive)
        saveConfigLocked()

        if (likedCache.remove(removed.username) != null) {
            saveCacheLocked()
        }
        removed.username
    }

    fun saveSweepStats(total: Int, liked: Int, skipped: Int, username: String? = null) {
        synchronized(lock) {
            val idx = resolveIndexLocked(username)
            if (idx < 0) return
            val nowStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val list = config.accounts.toMutableList()
            list[idx] = list[idx].copy(
                sweepStats = SweepStats(
                    total = total,
                    liked = liked,
                    skipped = skipped,
                    lastSweepTime = nowStr,
                ),
            )
            config = config.copy(accounts = list)
            saveConfigLocked()
        }
    }

    fun saveMonthlyStats(total: Int, liked: Int, username: String? = null) {
        synchronized(lock) {
            val idx = resolveIndexLocked(username)
            if (idx < 0) return
            val monthStr = YearMonth.now().toString()
            val list = config.accounts.toMutableList()
            list[idx] = list[idx].copy(
                monthlyStats = MonthlyStats(
                    monthlyTotal = total,
                    monthlyLiked = liked,
                    month = monthStr,
                ),
            )
            config = config.copy(accounts = list)
            saveConfigLocked()
        }
    }

    fun updateMonthlyTotalOnly(total: Int, minLiked: Int = 0, username: String? = null) {
        synchronized(lock) {
            val idx = resolveIndexLocked(username)
            if (idx < 0) return
            val monthStr = YearMonth.now().toString()
            val cur = config.accounts[idx].monthlyStats
            val prevLiked = if (cur.month == monthStr) cur.monthlyLiked else 0
            var liked = maxOf(prevLiked, minLiked)
            if (total >= 0) {
                liked = minOf(total, liked)
            }
            val list = config.accounts.toMutableList()
            list[idx] = list[idx].copy(
                monthlyStats = MonthlyStats(
                    monthlyTotal = total,
                    monthlyLiked = liked,
                    month = monthStr,
                ),
            )
            config = config.copy(accounts = list)
            saveConfigLocked()
        }
    }

    fun getCurrentMonthStats(username: String? = null): MonthlyStats? {
        val ms = resolveAccount(username)?.monthlyStats ?: return null
        return if (ms.month == YearMonth.now().toString()) ms else null
    }

    fun updateSettings(
        scanIntervalHours: Double,
        autoBackgroundEnabled: Boolean,
        autoCheckMonthlyTotal: Boolean,
        disableSystemProxy: Boolean,
        multiAccountAuto: Boolean,
        channels: List<ChannelConfig>,
    ) {
        synchronized(lock) {
            config = config.copy(
                scanIntervalHours = scanIntervalHours.coerceAtLeast(0.25),
                autoBackgroundEnabled = autoBackgroundEnabled,
                autoCheckMonthlyTotal = autoCheckMonthlyTotal,
                disableSystemProxy = disableSystemProxy,
                multiAccountAuto = multiAccountAuto,
                channels = channels.ifEmpty { DEFAULT_CHANNELS },
            )
            saveConfigLocked()
        }
    }

    fun saveAppearanceMode(mode: ThemeMode) {
        synchronized(lock) {
            config = config.copy(appearanceMode = mode)
            saveConfigLocked()
        }
    }

    // ============================
    // 账号隔离本地缓存
    // ============================

    private fun resolveCacheUserLocked(username: String?): String =
        username?.takeIf { it.isNotBlank() } ?: getActiveUsername() ?: "default"

    fun isLiked(articleKey: String, username: String? = null): Boolean = synchronized(lock) {
        val user = resolveCacheUserLocked(username)
        likedCache[user]?.contains(articleKey) == true
    }

    fun markLiked(articleKey: String, username: String? = null, flush: Boolean = true) {
        synchronized(lock) {
            val user = resolveCacheUserLocked(username)
            val set = likedCache.getOrPut(user) { mutableSetOf() }
            if (set.add(articleKey)) {
                cacheDirty = true
                _cacheCountsFlow.value = computeCacheCounts()
                if (flush) {
                    saveCacheLocked()
                }
            }
        }
    }

    fun markLikedBatch(articleKeys: Collection<String>, username: String? = null) {
        synchronized(lock) {
            val user = resolveCacheUserLocked(username)
            val set = likedCache.getOrPut(user) { mutableSetOf() }
            var changed = false
            for (k in articleKeys) {
                if (set.add(k)) changed = true
            }
            if (changed) {
                cacheDirty = true
                saveCacheLocked()
            }
        }
    }

    fun flushCache() {
        synchronized(lock) {
            if (cacheDirty) {
                saveCacheLocked()
            }
        }
    }

    fun getCacheSize(username: String? = null): Int = synchronized(lock) {
        val user = resolveCacheUserLocked(username)
        likedCache[user]?.size ?: 0
    }

    fun clearCache(username: String? = null): Int = synchronized(lock) {
        val user = resolveCacheUserLocked(username)
        val count = likedCache[user]?.size ?: 0
        likedCache[user] = mutableSetOf()
        cacheDirty = true
        saveCacheLocked()
        count
    }
}
