package com.muxset.tbeplus.data

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Serializable
data class ChannelConfig(
    val name: String,
    val id: String,
)

val DEFAULT_CHANNELS: List<ChannelConfig> = listOf(
    ChannelConfig("特变电工股份有限公司", "XT-2bb8a866-d2a3-47da-bbad-8c63db21e9b6"),
    ChannelConfig("新变厂新闻资讯", "XT-0ba2025b-e2cb-498b-99b1-cdc741a21f75"),
    ChannelConfig("开讲了", "XT-1b1e03a9-8222-4b7f-a7eb-a5d91e2d012d"),
)

@Serializable
data class SweepStats(
    val total: Int = 0,
    val liked: Int = 0,
    val skipped: Int = 0,
    val lastSweepTime: String = "",
)

@Serializable
data class MonthlyStats(
    val monthlyTotal: Int = 0,
    val monthlyLiked: Int = 0,
    val month: String = "",
)

@Serializable
data class AccountRecord(
    val username: String,
    val password: String,
    val tbeaArtToken: String = "",
    val ejiaCookies: Map<String, String> = emptyMap(),
    val ejiaUserId: String = "",
    val tokenRefreshTime: Long = 0L,
    val sweepStats: SweepStats = SweepStats(),
    val monthlyStats: MonthlyStats = MonthlyStats(),
)

@Serializable
enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT,
}

@Serializable
data class AppConfig(
    val accounts: List<AccountRecord> = emptyList(),
    val activeAccount: Int = -1,
    val scanIntervalHours: Double = 1.0,
    val autoBackgroundEnabled: Boolean = false,
    val autoCheckMonthlyTotal: Boolean = true,
    val disableSystemProxy: Boolean = false,
    val multiAccountAuto: Boolean = false,
    val appearanceMode: ThemeMode = ThemeMode.SYSTEM,
    val channels: List<ChannelConfig> = DEFAULT_CHANNELS,
)

data class ArticleItem(
    val id: String,
    val title: String,
    val channel: String,
    val sendTime: String,
)

enum class LogKind {
    INFO,
    LIKED,
    SKIP,
    WARN,
    ERROR,
    SYSTEM,
}

data class LogEntry(
    val id: Long,
    val time: String,
    val kind: LogKind,
    val tag: String,
    val message: String,
)

data class ScanProgress(
    val stage: String,
    val channel: String = "",
    val current: Int = 0,
    val total: Int = 0,
    val liked: Int = 0,
    val skipped: Int = 0,
    val message: String = "",
    val username: String = "",
) {
    val ratio: Float
        get() = if (total > 0) (current.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
}

enum class TokenHealthState {
    UNSET,
    CHECKING,
    REFRESHING,
    VALID,
    EXPIRED,
    UNKNOWN,
}

data class AuthResult(
    val token: String,
    val ejiaCookies: Map<String, String>,
    val ejiaUserId: String,
)

enum class ScanPreset(val label: String) {
    TODAY("今天"),
    THREE_DAYS("最近3天"),
    WEEK("本周"),
    MONTH("本月"),
    LAST_MONTH("上月"),
    CUSTOM("自定义");

    fun dateRange(today: LocalDate = LocalDate.now()): Pair<String, String>? {
        val fmt = DateTimeFormatter.ISO_LOCAL_DATE
        return when (this) {
            TODAY -> today.format(fmt) to today.format(fmt)
            THREE_DAYS -> today.minusDays(2).format(fmt) to today.format(fmt)
            WEEK -> today.with(DayOfWeek.MONDAY).format(fmt) to today.format(fmt)
            MONTH -> today.withDayOfMonth(1).format(fmt) to today.format(fmt)
            LAST_MONTH -> {
                val prev = YearMonth.from(today).minusMonths(1)
                prev.atDay(1).format(fmt) to prev.atEndOfMonth().format(fmt)
            }
            CUSTOM -> null
        }
    }
}
