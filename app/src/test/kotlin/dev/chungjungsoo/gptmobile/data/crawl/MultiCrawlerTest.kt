package dev.chungjungsoo.gptmobile.data.crawl

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiCrawlerTest {
    private val scope = CrawlScope("profile", 12, "12:1:100", "permission-v1", true)
    private fun reader(read: (String) -> AgentToolResult) = object : AgentTool {
        override val definition = AgentToolDefinition(
            "read_url",
            "Reader",
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { put("url", buildJsonObject { put("type", "string") }) })
            }
        )
        override suspend fun execute(callId: String, arguments: JsonObject) = read(arguments.getValue("url").toString().trim('"')).copy(callId = callId)
    }
    private val robots = reader { AgentToolResult("robots", ToolResultContent.Text("User-agent: *\nDisallow: /private\n"), false) }

    @Test fun cachedReaderCannotIgnoreExplicitDomainRestrictions() = runBlocking {
        val directory = Files.createTempDirectory("crawler-domain-cache").toFile()
        try {
            val store = CrawlStore(directory)
            var reads = 0
            val delegate = object : AgentTool {
                override val definition = robots.definition
                override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                    reads++
                    if ("excludeDomains" in arguments) return AgentToolResult(callId, ToolResultContent.Text("outside allowed domain scope"), true)
                    return AgentToolResult(
                        callId,
                        ToolResultContent.Json(
                            buildJsonObject {
                                put("url", "https://example.org/page")
                                put("content", "Captured evidence")
                            }
                        ),
                        false
                    )
                }
            }
            val tool = CachedPageReader(store, scope, delegate) { true }
            val args = buildJsonObject { put("url", "https://example.org/page") }
            assertFalse(tool.execute("first", args).isError)
            assertTrue(tool.execute("cached", args).sharedResult)
            assertEquals(1, reads)
            assertTrue(tool.execute("restricted", JsonObject(args + ("excludeDomains" to JsonArray(listOf(JsonPrimitive("example.org")))))).isError)
            assertEquals(2, reads)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun robotsAndSourceScopeDoNotExpandAcrossHostsOrPrivateAddresses() {
        assertFalse(CrawlPolicy.robotsAllows("User-agent: *\nDisallow: /admin\nAllow: /admin/public\n", "https://example.org/admin/secrets"))
        assertTrue(CrawlPolicy.robotsAllows("User-agent: *\nDisallow: /admin\nAllow: /admin/public\n", "https://example.org/admin/public/file"))
        assertNull(CrawlPolicy.inScope("https://other.example/page", setOf("example.org")))
        assertThrows(IllegalArgumentException::class.java) { CrawlPolicy.url("http://127.0.0.1/private") }
        assertNotEquals(CrawlPolicy.url("https://example.org/A?x=1"), CrawlPolicy.url("https://example.org/a?x=2"))
    }

    @Test fun deepCrawlFollowsScopedLinksAndReusesCommittedPagesAfterRestart() = runBlocking {
        val directory = Files.createTempDirectory("crawl-test").toFile()
        try {
            var reads = 0
            val engine = reader { url ->
                reads++
                AgentToolResult(
                    "read",
                    ToolResultContent.Json(
                        buildJsonObject {
                            put("url", url)
                            put("content", "Evidence from $url. ".repeat(20))
                            put("links", JsonArray(listOf(JsonPrimitive("https://example.org/second"), JsonPrimitive("https://other.example/ignored"))))
                        }
                    ),
                    false
                )
            }
            val store = CrawlStore(directory)
            fun tool(storage: CrawlStore) = MultiCrawlerTool(storage, scope, listOf(CrawlEngine("native", "Native", engine)), robots, 10, 2, { true }).withChildBudget { it }
            val started = tool(store).execute(
                "start",
                buildJsonObject {
                    put("action", "start")
                    put("mode", "deep")
                    put("seeds", JsonArray(listOf(JsonPrimitive("https://example.org/first"))))
                }
            )
            assertFalse(started.content.toString(), started.isError)
            val jobId = ((started.content as ToolResultContent.Json).value.jsonObject.getValue("jobId") as JsonPrimitive).content
            assertEquals(2, reads)
            assertEquals("COMPLETED", store.read(jobId)!!.state)
            val restored = CrawlStore(directory)
            tool(restored).execute(
                "resume",
                buildJsonObject {
                    put("action", "resume")
                    put("jobId", jobId)
                }
            )
            assertEquals(2, reads)
            assertEquals(2, restored.read(jobId)!!.pageIds.size)
            val denied = MultiCrawlerTool(restored, scope.copy(revision = "changed"), emptyList(), robots, 10, 2, { true }).execute(
                "inspect",
                buildJsonObject {
                    put("action", "results")
                    put("jobId", jobId)
                }
            )
            assertTrue(denied.isError)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun smartModeDoesNotFollowLinksAndAlternativeReaderPreservesProvenance() = runBlocking {
        val directory = Files.createTempDirectory("crawl-alternative").toFile()
        try {
            var second = 0
            val failed = reader { AgentToolResult("bad", ToolResultContent.Text("Decode failed"), true, errorCode = "PARSE_FAILED") }
            val good = reader { url ->
                second++
                AgentToolResult(
                    "ok",
                    ToolResultContent.Json(
                        buildJsonObject {
                            put("url", url)
                            put("content", "A usable captured article. ".repeat(20))
                            put("links", JsonArray(listOf(JsonPrimitive("https://example.org/next"))))
                        }
                    ),
                    false
                )
            }
            val store = CrawlStore(directory)
            val tool = MultiCrawlerTool(store, scope, listOf(CrawlEngine("first", "First", failed), CrawlEngine("second", "Second", good)), robots, 5, 2, { true }).withChildBudget { it }
            val result = tool.execute(
                "smart",
                buildJsonObject {
                    put("action", "start")
                    put("seeds", JsonArray(listOf(JsonPrimitive("https://example.org/start"))))
                }
            )
            assertFalse(result.isError)
            assertEquals(1, second)
            assertTrue(result.content.toString().contains("second"))
            assertEquals(1, store.list().single().pageIds.size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun childReadersShareTheRootBudgetAndStopBeforeAnotherRequest() = runBlocking {
        val directory = Files.createTempDirectory("crawler-budget").toFile()
        try {
            var reads = 0
            val engine = reader { url ->
                reads++
                AgentToolResult(
                    "read",
                    ToolResultContent.Json(
                        buildJsonObject {
                            put("url", url)
                            put("content", "Evidence text")
                        }
                    ),
                    false
                )
            }
            val store = CrawlStore(directory)
            val budget = dev.chungjungsoo.gptmobile.data.agent.ToolExecutionBudget(dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits(maxToolCalls = 1, finalResponseToolCallReserve = 0, maxConcurrentTools = 1))
            val tool = budget.bind(MultiCrawlerTool(store, scope.copy(revision = "budget-case"), listOf(CrawlEngine("native", "Native", engine)), robots, 10, 2, { true }))
            tool.execute(
                "start",
                buildJsonObject {
                    put("action", "start")
                    put("seeds", JsonArray(listOf(JsonPrimitive("https://budget.example.org/first"))))
                }
            )
            assertEquals(0, reads)
            assertEquals("PAUSED", store.list().single().state)
            assertTrue(store.list().single().notice.contains("budget"))
            assertTrue(store.list().single().visited.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun encodedPathsKeepTheirIdentityAndPercentEscapes() {
        assertEquals("https://example.org/a%2Fb?q=x%2Fy", CrawlPolicy.url("https://example.org/a%2Fb?q=x%2Fy#ignored"))
    }
}
