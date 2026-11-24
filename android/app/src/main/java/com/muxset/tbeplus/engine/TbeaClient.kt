package com.muxset.tbeplus.engine

import com.muxset.tbeplus.data.ArticleItem
import com.muxset.tbeplus.data.ChannelConfig
import com.muxset.tbeplus.data.LogKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class AuthExpiredException(message: String) : Exception(message)

/**
 * TBEA 统一网络客户端：
 * - 支持强制直连 (Proxy.NO_PROXY)
 * - 自动 5xx / 网络瞬断重试
 * - 置顶旧文章防翻页截断
 * - 双端凭据失效检测 (401 / 登录页 HTML / code=-1)
 */
class TbeaClient(
    private val pubaccUrl: String = DEFAULT_PUBACC_URL,
    private val detailUrl: String = DEFAULT_DETAIL_URL,
    private val likeUrl: String = DEFAULT_LIKE_URL,
    private val checkLoginUrl: String = DEFAULT_CHECK_LOGIN_URL,
) {
    companion object {
        const val DEFAULT_PUBACC_URL = "https://ejia.tbea.com/pubacc_v2/api/card/getArticleList"
        const val DEFAULT_DETAIL_URL = "https://tbeanews.tbea.com/api/article/detail"
        const val DEFAULT_LIKE_URL = "https://tbeanews.tbea.com/api/article/addDigg"
        const val DEFAULT_CHECK_LOGIN_URL = "https://ejia.tbea.com/space/c/rest/user/checkLogin"
        const val PROBE_ARTICLE_ID = "8141"

        private val ID_IN_URL_REGEX = Regex("[?&]id=(\\d+)")
        private val TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    private val jsonParser = Json { ignoreUnknownKeys = true }

    private val normalClient: OkHttpClient = buildClient(disableProxy = false)
    private val directClient: OkHttpClient = buildClient(disableProxy = true)

    private fun buildClient(disableProxy: Boolean): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .apply {
                if (disableProxy) {
                    proxy(Proxy.NO_PROXY)
                }
            }
            .build()

    private fun client(disableProxy: Boolean): OkHttpClient =
        if (disableProxy) directClient else normalClient

    private fun executeWithRetry(request: Request, disableProxy: Boolean, maxAttempts: Int = 3): Response {
        var lastErr: IOException? = null
        val http = client(disableProxy)
        for (attempt in 1..maxAttempts) {
            try {
                val resp = http.newCall(request).execute()
                if (resp.code in listOf(500, 502, 503, 504) && attempt < maxAttempts) {
                    resp.close()
                    Thread.sleep(attempt * 400L)
                    continue
                }
                return resp
            } catch (e: IOException) {
                lastErr = e
                if (attempt < maxAttempts) {
                    Thread.sleep(attempt * 400L)
                }
            }
        }
        throw lastErr ?: IOException("网络请求失败")
    }

    private fun cookieHeader(cookies: Map<String, String>): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    fun isTokenExpiredPayload(payload: JsonObject): Boolean {
        val code = payload["code"]?.jsonPrimitive?.intOrNull
        if (code == -1 || code == 401) return true
        val msg = payload["msg"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return listOf("登录超时", "重新登录", "未登录", "token", "Token").any { msg.contains(it) }
    }

    fun checkTokenValid(token: String, disableProxy: Boolean): Boolean? {
        if (token.isBlank()) return false
        return try {
            val url = detailUrl.toHttpUrl().newBuilder()
                .addQueryParameter("id", PROBE_ARTICLE_ID)
                .build()
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .header("token", token)
                .get()
                .build()
            executeWithRetry(req, disableProxy, maxAttempts = 2).use { resp ->
                if (resp.code == 401 || resp.code == 403) return false
                val bodyStr = resp.body?.string().orEmpty()
                val obj = jsonParser.parseToJsonElement(bodyStr).jsonObject
                if (obj["code"]?.jsonPrimitive?.intOrNull == 1) return true
                if (isTokenExpiredPayload(obj)) return false
                true
            }
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            false
        }
    }

    fun checkEjiaSessionValid(cookies: Map<String, String>, disableProxy: Boolean): Boolean? {
        if (cookies.isEmpty()) return false
        return try {
            val req = Request.Builder()
                .url(checkLoginUrl)
                .header("User-Agent", "Mozilla/5.0")
                .header("Cookie", cookieHeader(cookies))
                .post(ByteArray(0).toRequestBody(null))
                .build()
            executeWithRetry(req, disableProxy, maxAttempts = 2).use { resp ->
                if (resp.code == 401 || resp.code == 403) return false
                if (resp.code != 200) return null
                val ctype = resp.header("Content-Type").orEmpty().lowercase()
                val text = resp.body?.string().orEmpty()
                if (ctype.contains("html") || text.trimStart().startsWith("<!")) return false
                val obj = jsonParser.parseToJsonElement(text).jsonObject
                obj["success"]?.jsonPrimitive?.booleanOrNull == true
            }
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            false
        }
    }

    fun fetchChannelArticles(
        cookies: Map<String, String>,
        channel: ChannelConfig,
        startDate: String,
        endDate: String,
        disableProxy: Boolean,
        isCancelled: () -> Boolean = { false },
        onLog: (LogKind, String) -> Unit = { _, _ -> },
        pageSize: Int = 25,
        maxPages: Int = 20,
    ): List<ArticleItem> {
        val result = mutableListOf<ArticleItem>()
        var prevSig: List<String>? = null

        for (page in 1..maxPages) {
            if (isCancelled()) break

            val form = FormBody.Builder()
                .add("ids", "[\"${channel.id}\"]")
                .add("source", "1")
                .add("pageIndex", page.toString())
                .add("pageSize", pageSize.toString())
                .build()

            val req = Request.Builder()
                .url(pubaccUrl)
                .header("User-Agent", "Mozilla/5.0")
                .header("Cookie", cookieHeader(cookies))
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Origin", "https://ejia.tbea.com")
                .header("Referer", "https://ejia.tbea.com/im/xiaoxi/")
                .post(form)
                .build()

            try {
                executeWithRetry(req, disableProxy).use { resp ->
                    if (resp.code == 401 || resp.code == 403) {
                        throw AuthExpiredException("Session Cookies 已过期 (${resp.code})")
                    }
                    if (!resp.isSuccessful) {
                        onLog(LogKind.ERROR, "[频道拉取] [${channel.name}] HTTP ${resp.code}")
                        return result
                    }

                    val ctype = resp.header("Content-Type").orEmpty().lowercase()
                    val rawText = resp.body?.string().orEmpty()
                    if (ctype.contains("html") || rawText.trimStart().startsWith("<!")) {
                        throw AuthExpiredException("Session Cookies 已过期 (返回登录页)")
                    }

                    val root = try {
                        jsonParser.parseToJsonElement(rawText).jsonObject
                    } catch (_: Exception) {
                        throw AuthExpiredException("Session Cookies 已过期 (响应非 JSON)")
                    }

                    val success = root["success"]?.jsonPrimitive?.booleanOrNull == true
                    val errCode = root["errorCode"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (!success && errCode != "200") {
                        val errMsg = root["error"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (errCode == "401" || errMsg.contains("登录") || errMsg.contains("有效期")) {
                            throw AuthExpiredException("Session Cookies 已过期 ($errMsg)")
                        }
                        onLog(LogKind.ERROR, "[频道拉取] [${channel.name}] 接口异常: $errMsg")
                        return result
                    }

                    val dataArr = root["data"] as? JsonArray ?: return result
                    if (dataArr.isEmpty()) return result

                    val sig = dataArr.mapNotNull { elem ->
                        val obj = elem as? JsonObject ?: return@mapNotNull null
                        obj["id"]?.jsonPrimitive?.contentOrNull
                            ?: obj["url"]?.jsonPrimitive?.contentOrNull
                    }
                    if (sig == prevSig) return result
                    prevSig = sig

                    val pageArticles = mutableListOf<ArticleItem>()
                    for (elem in dataArr) {
                        val item = elem as? JsonObject ?: continue
                        val parsed = parseArticleItem(item, channel.name)
                        if (parsed != null) pageArticles.add(parsed)
                    }

                    for (art in pageArticles) {
                        val sendDate = art.sendTime.take(10)
                        if (sendDate in startDate..endDate) {
                            result.add(art)
                        }
                    }

                    // 仅当整页文章最大日期早于起始日期时停止翻页，防止单篇置顶旧文章截断
                    val latestDate = pageArticles.maxOfOrNull { it.sendTime.take(10) } ?: "0000-00-00"
                    if (latestDate < startDate || dataArr.size < pageSize) {
                        return result
                    }
                }
            } catch (e: AuthExpiredException) {
                throw e
            } catch (e: Exception) {
                onLog(LogKind.ERROR, "[频道拉取] [${channel.name}] 失败: ${e.message}")
                break
            }

            if (!isCancelled()) {
                Thread.sleep(200)
            }
        }
        return result
    }

    private fun parseArticleItem(item: JsonObject, channelName: String): ArticleItem? {
        val url = item["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        var artId = ID_IN_URL_REGEX.find(url)?.groupValues?.getOrNull(1)
        if (artId.isNullOrBlank()) {
            artId = item["id"]?.jsonPrimitive?.contentOrNull
                ?: item["yzj_id"]?.jsonPrimitive?.contentOrNull
        }
        val title = item["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (artId.isNullOrBlank() || title.isBlank()) return null

        val tsMs = item["publishTimeStamp"]?.jsonPrimitive?.doubleOrNull
            ?: item["sendTime"]?.jsonPrimitive?.doubleOrNull
        val sendTime = if (tsMs != null && tsMs > 0) {
            try {
                Instant.ofEpochMilli(tsMs.toLong())
                    .atZone(ZoneId.systemDefault())
                    .format(TIME_FMT)
            } catch (_: Exception) {
                item["publishTime"]?.jsonPrimitive?.contentOrNull ?: "2000-01-01 00:00:00"
            }
        } else {
            item["publishTime"]?.jsonPrimitive?.contentOrNull ?: "2000-01-01 00:00:00"
        }

        return ArticleItem(
            id = artId,
            title = title,
            channel = channelName,
            sendTime = sendTime,
        )
    }

    fun fetchAllChannelsArticles(
        cookies: Map<String, String>,
        channels: List<ChannelConfig>,
        startDate: String,
        endDate: String,
        disableProxy: Boolean,
        isCancelled: () -> Boolean = { false },
        onLog: (LogKind, String) -> Unit = { _, _ -> },
        onChannelProgress: (Int, Int, String) -> Unit = { _, _, _ -> },
    ): List<ArticleItem> {
        val uniqueMap = linkedMapOf<String, ArticleItem>()
        channels.forEachIndexed { idx, ch ->
            if (isCancelled()) return@forEachIndexed
            onChannelProgress(idx + 1, channels.size, ch.name)
            onLog(LogKind.INFO, "[频道拉取] 正在拉取频道 [${ch.name}]...")
            val arts = fetchChannelArticles(
                cookies = cookies,
                channel = ch,
                startDate = startDate,
                endDate = endDate,
                disableProxy = disableProxy,
                isCancelled = isCancelled,
                onLog = onLog,
            )
            onLog(LogKind.INFO, "    ↳ 找到 ${arts.size} 篇对应日期范围文章")
            for (a in arts) {
                if (!uniqueMap.containsKey(a.id)) {
                    uniqueMap[a.id] = a
                }
            }
        }
        return uniqueMap.values.sortedBy { it.sendTime }
    }

    fun getArticleDetail(token: String, articleId: String, disableProxy: Boolean): JsonObject {
        val url = detailUrl.toHttpUrl().newBuilder()
            .addQueryParameter("id", articleId)
            .build()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .header("token", token)
            .get()
            .build()
        executeWithRetry(req, disableProxy).use { resp ->
            if (resp.code == 401 || resp.code == 403) {
                throw AuthExpiredException("新闻 Token 已过期 (${resp.code})")
            }
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}")
            }
            val obj = jsonParser.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            if (isTokenExpiredPayload(obj)) {
                val msg = obj["msg"]?.jsonPrimitive?.contentOrNull ?: "登录超时"
                throw AuthExpiredException("新闻 Token 已失效 ($msg)")
            }
            return obj
        }
    }

    fun likeArticle(token: String, articleId: String, disableProxy: Boolean): JsonObject {
        val body = """{"id":"$articleId"}""".toRequestBody(JSON_MEDIA)
        val req = Request.Builder()
            .url(likeUrl)
            .header("User-Agent", "Mozilla/5.0")
            .header("token", token)
            .post(body)
            .build()
        executeWithRetry(req, disableProxy).use { resp ->
            if (resp.code == 401 || resp.code == 403) {
                throw AuthExpiredException("新闻 Token 已过期 (${resp.code})")
            }
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}")
            }
            val obj = jsonParser.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            if (isTokenExpiredPayload(obj)) {
                val msg = obj["msg"]?.jsonPrimitive?.contentOrNull ?: "登录超时"
                throw AuthExpiredException("新闻 Token 已失效 ($msg)")
            }
            return obj
        }
    }
}
