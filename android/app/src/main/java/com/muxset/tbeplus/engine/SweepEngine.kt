package com.muxset.tbeplus.engine

import com.muxset.tbeplus.data.AppRepository
import com.muxset.tbeplus.data.LogKind
import com.muxset.tbeplus.data.ScanProgress
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class SweepOutcome(
    val total: Int = 0,
    val liked: Int = 0,
    val skipped: Int = 0,
    val authExpired: Boolean = false,
    val cancelled: Boolean = false,
)

class SweepEngine(
    private val repo: AppRepository,
    private val client: TbeaClient,
) {
    fun runSweep(
        startDate: String? = null,
        endDate: String? = null,
        dryRun: Boolean = false,
        username: String? = null,
        isCancelled: () -> Boolean = { false },
        onLog: (LogKind, String) -> Unit = { _, _ -> },
        onProgress: (ScanProgress) -> Unit = {},
    ): SweepOutcome {
        val today = LocalDate.now()
        val monthStart = today.withDayOfMonth(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val sd = startDate ?: monthStart
        val ed = endDate ?: todayStr

        val targetUser = username ?: repo.getActiveUsername()
        if (targetUser.isNullOrBlank()) {
            onLog(LogKind.ERROR, "[频道扫描] 未配置账号，请先添加账号")
            return SweepOutcome()
        }

        val cfg = repo.getConfigSnapshot()
        val disableProxy = cfg.disableSystemProxy
        val token = repo.getToken(targetUser)
        val cookies = repo.getEjiaCookies(targetUser)
        val (_, password) = repo.getCredentials(targetUser)

        if (cookies.isEmpty() || (!dryRun && token.isBlank())) {
            if (!password.isNullOrBlank()) {
                onLog(LogKind.WARN, "[频道扫描] ($targetUser) 尚未缓存有效凭据，需要认证登录")
                return SweepOutcome(authExpired = true)
            }
            onLog(LogKind.ERROR, "[频道扫描] 凭据不足，请先在账号管理中配置密码")
            return SweepOutcome()
        }

        onLog(
            LogKind.INFO,
            if (dryRun) "[干跑统计] ($targetUser) 统计文章数: $sd ~ $ed"
            else "[频道扫描] ($targetUser) 扫描日期范围: $sd ~ $ed",
        )

        if (client.checkEjiaSessionValid(cookies, disableProxy) == false) {
            onLog(LogKind.WARN, "[频道扫描] Session Cookies 已过期，需要刷新凭据")
            return SweepOutcome(authExpired = true)
        }
        if (!dryRun && client.checkTokenValid(token, disableProxy) == false) {
            onLog(LogKind.WARN, "[频道扫描] 新闻 Token 已过期，需要刷新凭据")
            return SweepOutcome(authExpired = true)
        }

        val unique = try {
            client.fetchAllChannelsArticles(
                cookies = cookies,
                channels = cfg.channels,
                startDate = sd,
                endDate = ed,
                disableProxy = disableProxy,
                isCancelled = isCancelled,
                onLog = onLog,
                onChannelProgress = { idx, chTotal, chName ->
                    onProgress(
                        ScanProgress(
                            stage = "fetch",
                            channel = chName,
                            current = idx,
                            total = chTotal,
                            message = "正在拉取频道 [$chName] ($idx/$chTotal)",
                            username = targetUser,
                        ),
                    )
                },
            )
        } catch (e: AuthExpiredException) {
            onLog(LogKind.WARN, "[频道扫描] ${e.message}")
            return SweepOutcome(authExpired = true)
        }

        if (isCancelled()) {
            onLog(LogKind.INFO, "[频道扫描] 任务已手动取消")
            return SweepOutcome(cancelled = true)
        }

        val total = unique.size
        if (dryRun) {
            val cachedLiked = unique.count { repo.isLiked(it.id, targetUser) }
            onLog(LogKind.INFO, "[干跑统计] ($targetUser) 本月共有 $total 篇文章（本地已记录 $cachedLiked 篇）")
            repo.updateMonthlyTotalOnly(total, minLiked = cachedLiked, username = targetUser)
            return SweepOutcome(total = total)
        }

        onLog(LogKind.INFO, "[频道扫描] 共有 $total 篇文章（去重后），开始检查点赞状态...")
        if (total == 0) {
            repo.saveSweepStats(0, 0, 0, username = targetUser)
            if (sd == monthStart && ed >= todayStr) {
                repo.saveMonthlyStats(0, 0, username = targetUser)
            }
            return SweepOutcome()
        }

        var likedCount = 0
        var skippedCount = 0
        var pendingFlush = 0

        try {
            for ((index, art) in unique.withIndex()) {
                if (isCancelled()) {
                    onLog(LogKind.INFO, "[频道扫描] 收到停止信号，正在保存进度...")
                    break
                }

                val idx = index + 1
                val shortTitle = art.title.take(22)
                val display = "「$shortTitle」(ID:${art.id})"

                onProgress(
                    ScanProgress(
                        stage = "like",
                        channel = art.channel,
                        current = idx,
                        total = total,
                        liked = likedCount,
                        skipped = skippedCount,
                        message = "($idx/$total) $shortTitle",
                        username = targetUser,
                    ),
                )

                if (repo.isLiked(art.id, targetUser)) {
                    onLog(LogKind.SKIP, "[频道扫描] 本地已记录，跳过: $display")
                    skippedCount++
                    continue
                }

                try {
                    val detail = client.getArticleDetail(token, art.id, disableProxy)
                    val code = detail["code"]?.jsonPrimitive?.intOrNull
                    val dataObj = detail["data"]?.jsonObject
                    if (code == 1 && dataObj != null) {
                        val isDigg = dataObj["is_digg"]?.jsonPrimitive?.booleanOrNull ?: true
                        if (isDigg) {
                            onLog(LogKind.SKIP, "[频道扫描] 服务端已赞，跳过: $display")
                            repo.markLiked(art.id, targetUser, flush = false)
                            pendingFlush++
                            skippedCount++
                        } else {
                            onLog(LogKind.INFO, "[频道扫描] 发现未赞，点赞中: $display")
                            val likeResp = client.likeArticle(token, art.id, disableProxy)
                            val likeCode = likeResp["code"]?.jsonPrimitive?.intOrNull
                            val likeMsg = likeResp["msg"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            when {
                                likeCode == 1 -> {
                                    repo.markLiked(art.id, targetUser, flush = false)
                                    pendingFlush++
                                    likedCount++
                                    onLog(LogKind.LIKED, "[频道扫描] $display 点赞成功！")
                                }
                                likeMsg.contains("重复") || likeMsg.contains("已点") -> {
                                    repo.markLiked(art.id, targetUser, flush = false)
                                    pendingFlush++
                                    skippedCount++
                                    onLog(LogKind.SKIP, "[频道扫描] $display 已点赞（服务端确认）")
                                }
                                else -> onLog(LogKind.ERROR, "[频道扫描] $display 点赞失败: $likeMsg")
                            }
                        }
                    } else {
                        val msg = detail["msg"]?.jsonPrimitive?.contentOrNull ?: "未知响应"
                        onLog(LogKind.WARN, "[频道扫描] 无法获取文章详情: $display ($msg)")
                    }
                } catch (authErr: AuthExpiredException) {
                    onLog(LogKind.WARN, "[频道扫描] ${authErr.message}")
                    return SweepOutcome(authExpired = true)
                } catch (e: Exception) {
                    onLog(LogKind.ERROR, "[频道扫描] 处理 $display 时出错: ${e.message}")
                }

                if (pendingFlush >= 5) {
                    repo.flushCache()
                    pendingFlush = 0
                }

                if (!isCancelled()) {
                    Thread.sleep(450)
                }
            }
        } finally {
            repo.flushCache()
        }

        val wasCancelled = isCancelled()
        onLog(
            LogKind.LIKED,
            "[频道扫描] 完成！共 $total 篇: 新点赞 $likedCount，跳过 $skippedCount。",
        )
        repo.saveSweepStats(total, likedCount, skippedCount, username = targetUser)
        if (sd == monthStart && ed >= todayStr && !wasCancelled) {
            repo.saveMonthlyStats(total, likedCount + skippedCount, username = targetUser)
        }
        return SweepOutcome(
            total = total,
            liked = likedCount,
            skipped = skippedCount,
            cancelled = wasCancelled,
        )
    }
}
