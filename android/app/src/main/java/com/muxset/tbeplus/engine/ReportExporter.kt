package com.muxset.tbeplus.engine

import com.muxset.tbeplus.data.AppRepository
import com.muxset.tbeplus.data.LogKind
import com.muxset.tbeplus.data.ScanProgress
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.YearMonth

class ReportExporter(
    private val repo: AppRepository,
    private val client: TbeaClient,
) {
    private fun csvEscape(value: String): String {
        val needsQuotes = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
        if (!needsQuotes) return value
        return "\"${value.replace("\"", "\"\"")}\""
    }

    fun exportMonthlyReport(
        targetMonth: YearMonth = YearMonth.now(),
        username: String? = null,
        isCancelled: () -> Boolean = { false },
        onLog: (LogKind, String) -> Unit = { _, _ -> },
        onProgress: (ScanProgress) -> Unit = {},
    ): File? {
        val targetUser = username ?: repo.getActiveUsername()
        if (targetUser.isNullOrBlank()) {
            onLog(LogKind.ERROR, "[报告导出] 未配置账号")
            return null
        }

        val cfg = repo.getConfigSnapshot()
        val disableProxy = cfg.disableSystemProxy
        val token = repo.getToken(targetUser)
        val cookies = repo.getEjiaCookies(targetUser)
        if (token.isBlank() || cookies.isEmpty()) {
            throw AuthExpiredException("导出月报凭据缺失或已过期")
        }

        val startDate = targetMonth.atDay(1).toString()
        val endDate = targetMonth.atEndOfMonth().toString()
        onLog(LogKind.INFO, "[报告导出] ($targetUser) 开始生成 $targetMonth 月度点赞报告...")

        val unique = client.fetchAllChannelsArticles(
            cookies = cookies,
            channels = cfg.channels,
            startDate = startDate,
            endDate = endDate,
            disableProxy = disableProxy,
            isCancelled = isCancelled,
            onLog = onLog,
            onChannelProgress = { idx, chTotal, chName ->
                onProgress(
                    ScanProgress(
                        stage = "export",
                        channel = chName,
                        current = idx,
                        total = chTotal,
                        message = "拉取频道 [$chName] ($idx/$chTotal)",
                        username = targetUser,
                    ),
                )
            },
        )
        if (isCancelled()) return null

        val total = unique.size
        val rows = ArrayList<Array<String>>(total)
        var likedCount = 0

        try {
            for ((index, art) in unique.withIndex()) {
                if (isCancelled()) return null
                val idx = index + 1
                onProgress(
                    ScanProgress(
                        stage = "export",
                        channel = art.channel,
                        current = idx,
                        total = total,
                        liked = likedCount,
                        message = "核验状态 ($idx/$total) ${art.title.take(18)}",
                        username = targetUser,
                    ),
                )

                val statusText = try {
                    val detail = client.getArticleDetail(token, art.id, disableProxy)
                    val code = detail["code"]?.jsonPrimitive?.intOrNull
                    val data = detail["data"]?.jsonObject
                    if (code == 1 && data != null) {
                        val isDigg = data["is_digg"]?.jsonPrimitive?.booleanOrNull == true
                        if (isDigg) {
                            likedCount++
                            repo.markLiked(art.id, targetUser, flush = false)
                            "已赞"
                        } else {
                            "未赞"
                        }
                    } else {
                        "未知"
                    }
                } catch (auth: AuthExpiredException) {
                    throw auth
                } catch (_: Exception) {
                    "查询失败"
                }

                rows.add(arrayOf(art.id, art.title, art.channel, art.sendTime, statusText))
                if (!isCancelled()) Thread.sleep(250)
            }
        } finally {
            repo.flushCache()
        }

        val reportsDir = File(repo.filesDir, "reports").apply { mkdirs() }
        val file = File(reportsDir, "TBE+月度点赞报告_${targetMonth}_${targetUser}.csv")
        val sb = StringBuilder()
        sb.append('\uFEFF')
        sb.append("文章ID,标题,来源频道,推送时间,点赞状态\n")
        for (r in rows) {
            sb.append(r.joinToString(",") { csvEscape(it) }).append('\n')
        }
        file.writeText(sb.toString(), Charsets.UTF_8)

        onLog(LogKind.LIKED, "[报告导出] 月报生成成功！共 $total 篇（已赞 $likedCount 篇）")
        return file
    }
}
