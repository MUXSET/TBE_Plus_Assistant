package com.muxset.tbeplus

import com.muxset.tbeplus.data.ChannelConfig
import com.muxset.tbeplus.engine.AuthExpiredException
import com.muxset.tbeplus.engine.TbeaClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TbeaClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: TbeaClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = TbeaClient(
            pubaccUrl = server.url("/pubacc_v2/api/card/getArticleList").toString(),
            detailUrl = server.url("/api/article/detail").toString(),
            likeUrl = server.url("/api/article/addDigg").toString(),
            checkLoginUrl = server.url("/space/c/rest/user/checkLogin").toString(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `pinned old article on page 1 does not truncate page 2 and extracts URL numeric ID`() {
        val oldPinnedMs = LocalDateTime.of(2025, 1, 1, 8, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val nov20Ms = LocalDateTime.of(2026, 11, 20, 12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val nov02Ms = LocalDateTime.of(2026, 11, 2, 9, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val oct10Ms = LocalDateTime.of(2026, 10, 10, 9, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val page1Items = buildString {
            append("""{"title":"置顶旧文","url":"https://tbeanews.tbea.com/?id=999","publishTimeStamp":$oldPinnedMs},""")
            append("""{"title":"11月20日","url":"https://tbeanews.tbea.com/?id=1001","publishTimeStamp":$nov20Ms}""")
            for (i in 0 until 23) {
                append(""",{"title":"Filler-$i","url":"https://tbeanews.tbea.com/?id=${2000 + i}","publishTimeStamp":$nov20Ms}""")
            }
        }
        val page2Items = """
            {"title":"11月2日","url":"https://tbeanews.tbea.com/news/read?foo=1&id=1002","publishTimeStamp":$nov02Ms},
            {"title":"10月10日","url":"https://tbeanews.tbea.com/?id=1003","publishTimeStamp":$oct10Ms}
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"data":[$page1Items]}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"data":[$page2Items]}"""))

        val articles = client.fetchChannelArticles(
            cookies = mapOf("cu" to "1"),
            channel = ChannelConfig("测试频道", "XT-1"),
            startDate = "2026-11-01",
            endDate = "2026-11-24",
            disableProxy = true,
            pageSize = 25,
        )

        val ids = articles.map { it.id }.toSet()
        assertFalse(ids.contains("999"))
        assertFalse(ids.contains("1003"))
        assertTrue(ids.contains("1001"))
        assertTrue(ids.contains("1002"))
    }

    @Test(expected = AuthExpiredException::class)
    fun `fetchChannelArticles throws AuthExpiredException on HTML login redirect`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<!DOCTYPE html><html><body>Login</body></html>"),
        )
        client.fetchChannelArticles(
            cookies = mapOf("cu" to "1"),
            channel = ChannelConfig("C", "XT-1"),
            startDate = "2026-11-01",
            endDate = "2026-11-24",
            disableProxy = true,
        )
    }

    @Test(expected = AuthExpiredException::class)
    fun `getArticleDetail throws AuthExpiredException on code -1`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":-1,"msg":"登录超时"}"""))
        client.getArticleDetail("expired_tok", "1001", disableProxy = true)
    }

    @Test
    fun `checkEjiaSessionValid detects HTML 200 as false and JSON success as true`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<!DOCTYPE html><html></html>"))
        assertEquals(false, client.checkEjiaSessionValid(mapOf("cu" to "1"), disableProxy = true))

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        assertEquals(true, client.checkEjiaSessionValid(mapOf("cu" to "1"), disableProxy = true))
    }
}
