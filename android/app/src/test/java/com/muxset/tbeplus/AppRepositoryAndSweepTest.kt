package com.muxset.tbeplus

import com.muxset.tbeplus.data.AppRepository
import com.muxset.tbeplus.data.AuthResult
import com.muxset.tbeplus.engine.SweepEngine
import com.muxset.tbeplus.engine.TbeaClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.ZoneId

class AppRepositoryAndSweepTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `multi-account cache and stats are strictly isolated and persisted`() {
        val dir = tempFolder.newFolder("repo")
        val repo1 = AppRepository(dir)
        repo1.addOrUpdateAccount("alice", "passA", AuthResult("tokA", mapOf("cu" to "A"), "A"))
        repo1.addOrUpdateAccount("bob", "passB", AuthResult("tokB", mapOf("cu" to "B"), "B"), activate = false)

        repo1.markLiked("1001", username = "alice")
        repo1.markLikedBatch(listOf("2001", "2002"), username = "bob")
        repo1.saveMonthlyStats(50, 10, username = "alice")

        // 重新从磁盘加载
        val repo2 = AppRepository(dir)
        assertEquals("alice", repo2.getActiveUsername())
        assertTrue(repo2.isLiked("1001", username = "alice"))
        assertFalse(repo2.isLiked("1001", username = "bob"))
        assertEquals(2, repo2.getCacheSize("bob"))
        assertEquals(10, repo2.getCurrentMonthStats("alice")?.monthlyLiked)
        assertNull(repo2.getCurrentMonthStats("bob"))

        // 删除账号同步清理缓存并允许删空
        assertEquals("bob", repo2.removeAccount(1))
        assertEquals(0, repo2.getCacheSize("bob"))
        assertEquals("alice", repo2.removeAccount(0))
        assertNull(repo2.getActiveUsername())
    }

    @Test
    fun `partial custom date sweep does not overwrite full monthly stats`() {
        val dir = tempFolder.newFolder("sweep")
        val repo = AppRepository(dir)
        repo.addOrUpdateAccount("100001", "pwd", AuthResult("tok", mapOf("cu" to "1"), "1"))
        repo.saveMonthlyStats(88, 80, username = "100001")

        val today = LocalDate.now()
        val monthStart = today.withDayOfMonth(1)
        val yesterdayOrEarlier = if (today.dayOfMonth > 2) today.minusDays(1) else monthStart
        val artTs = yesterdayOrEarlier.atTime(10, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("checkLogin") ->
                        MockResponse().setResponseCode(200).setBody("""{"success":true}""")
                    path.contains("detail") && path.contains("8141") ->
                        MockResponse().setResponseCode(200).setBody("""{"code":1,"data":{"is_digg":true}}""")
                    path.contains("getArticleList") ->
                        MockResponse().setResponseCode(200).setBody(
                            """{"success":true,"data":[{"title":"测试文章","url":"https://tbeanews.tbea.com/?id=5001","publishTimeStamp":$artTs}]}""",
                        )
                    path.contains("detail") ->
                        MockResponse().setResponseCode(200).setBody("""{"code":1,"data":{"is_digg":false}}""")
                    path.contains("addDigg") ->
                        MockResponse().setResponseCode(200).setBody("""{"code":1,"msg":"点赞成功"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val client = TbeaClient(
            pubaccUrl = server.url("/pubacc_v2/api/card/getArticleList").toString(),
            detailUrl = server.url("/api/article/detail").toString(),
            likeUrl = server.url("/api/article/addDigg").toString(),
            checkLoginUrl = server.url("/space/c/rest/user/checkLogin").toString(),
        )
        val engine = SweepEngine(repo, client)

        // 仅扫月初 1 天（非截止今天，除非今天正好是 1 号）
        if (today.dayOfMonth > 1) {
            val outcome = engine.runSweep(
                startDate = monthStart.toString(),
                endDate = yesterdayOrEarlier.toString(),
                username = "100001",
            )
            assertEquals(1, outcome.liked)
            val ms = repo.getCurrentMonthStats("100001")
            assertNotNull(ms)
            assertEquals(88, ms?.monthlyTotal)
            assertEquals(80, ms?.monthlyLiked)
        }
        assertTrue(repo.isLiked("5001", "100001"))
    }
}
