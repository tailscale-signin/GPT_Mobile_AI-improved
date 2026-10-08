package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolExecutionBudget
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiEngineSearchToolTest {
    @Test fun `every selected engine starts before any engine finishes`() = runBlocking {
        val allStarted = CompletableDeferred<Unit>()
        var started = 0
        val budget = ToolExecutionBudget(AgentRunLimits())
        val engines = (1..7).map { index ->
            engine("engine-$index") { id, _ ->
                if (++started == 7) allStarted.complete(Unit)
                withTimeout(1000) { allStarted.await() }
                AgentToolResult(id, ToolResultContent.Text("Title: Evidence\nURL: https://example.org/$index\nDescription: Result"), false)
            }
        }.map { it.copy(tool = budget.bind(it.tool)) }
        val result = MultiEngineSearchTool(engines, engineTimeoutMillis = 2000).execute("parallel", buildJsonObject { put("query", "evidence") })
        assertEquals(7, started)
        assertEquals(7, ((result.content as ToolResultContent.Json).value.jsonObject["results"] as JsonArray).size)
    }

    @Test fun `oversampled candidates replace duplicate pages before collective crawling`() = runBlocking {
        val crawled = mutableListOf<List<String>>()
        val engines = (1..2).map { index ->
            engine("engine-$index", extraProperties = buildJsonObject { put("maxResults", buildJsonObject { put("type", "integer") }) }) { id, args ->
                assertEquals(JsonPrimitive(6), args["maxResults"])
                val changed = args["query"] == JsonPrimitive("follow up")
                val urls = listOf("shared", "unique-${index}a", "unique-${index}b") + if (changed) listOf("new-$index") else emptyList()
                AgentToolResult(
                    id,
                    ToolResultContent.Json(
                        buildJsonObject {
                            put(
                                "results",
                                JsonArray(
                                    urls.map { url ->
                                        buildJsonObject {
                                            put("url", "https://example.org/$url")
                                            put("title", url)
                                        }
                                    }
                                )
                            )
                        }
                    ),
                    false
                )
            }
        }
        val tool = MultiEngineSearchTool(engines, afterSearch = { _, sources ->
            crawled += sources.map { it.getValue("url").jsonPrimitive.content }
            buildJsonObject { put("inspected", sources.size) }
        })
        val first = tool.execute(
            "first",
            buildJsonObject {
                put("query", "evidence")
                put("maxResults", 2)
            }
        )
        val firstPayload = (first.content as ToolResultContent.Json).value.jsonObject
        assertEquals(4, (firstPayload["results"] as JsonArray).size)
        assertEquals(4, crawled.single().distinct().size)
        val second = tool.execute(
            "second",
            buildJsonObject {
                put("query", "follow up")
                put("maxResults", 2)
            }
        )
        val secondSources = (second.content as ToolResultContent.Json).value.jsonObject["results"] as JsonArray
        assertTrue(secondSources.any { it.jsonObject.getValue("url").jsonPrimitive.content.endsWith("new-1") })
        assertTrue(secondSources.any { it.jsonObject.getValue("url").jsonPrimitive.content.endsWith("new-2") })
        assertTrue(crawled.last().none { it in crawled.first() })
        assertEquals(crawled.last().size, crawled.last().distinct().size)
    }

    @Test fun `canonical URLs keep encoded boundaries repeated queries and distinct pages`() {
        org.junit.Assert.assertNotEquals(canonicalSearchUrl("https://example.org/a%2Fb"), canonicalSearchUrl("https://example.org/a/b"))
        org.junit.Assert.assertNotEquals(canonicalSearchUrl("https://example.org/a?id=1"), canonicalSearchUrl("https://example.org/a?id=2"))
        org.junit.Assert.assertNotEquals(canonicalSearchUrl("https://example.org/a?tag=1&tag=2"), canonicalSearchUrl("https://example.org/a?tag=2&tag=1"))
        assertEquals(canonicalSearchUrl("https://EXAMPLE.org:443/a?b=2&a=1&utm_source=x#top"), canonicalSearchUrl("https://example.org/a?a=1&b=2"))
    }

    @Test
    fun `built-in provider provenance survives aggregation and duplicate URLs`() = runBlocking {
        fun provider(name: String, connection: String) = engine(connection) { id, _ ->
            AgentToolResult(
                id,
                ToolResultContent.Json(
                    buildJsonObject {
                        put("engines", JsonArray(listOf(JsonPrimitive(name))))
                        put(
                            "results",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("url", "https://example.org/same")
                                        put("title", "Same source")
                                        put("engine", name)
                                    }
                                )
                            )
                        )
                    }
                ),
                false
            )
        }
        val result = MultiEngineSearchTool(listOf(provider("DuckDuckGo", "Built-in search"), provider("Brave Search", "Brave MCP")))
            .execute("provenance", buildJsonObject { put("query", "reference") })
        val value = (result.content as ToolResultContent.Json).value.jsonObject
        val sources = value["results"] as JsonArray

        assertEquals(1, sources.size)
        val providers = sources.single().jsonObject["engines"] as JsonArray
        assertTrue(providers.map { it.jsonPrimitive.content }.containsAll(listOf("DuckDuckGo", "Brave Search")))
        assertEquals("DuckDuckGo", (value["engines"] as JsonArray).first().jsonObject["engines"]?.let { (it as JsonArray).single().jsonPrimitive.content })
    }

    @Test
    fun `one stalled engine preserves other engines and all enabled engines are queried`() = runBlocking {
        val queried = mutableSetOf<String>()
        val engines = (1..4).map { index ->
            engine("engine-$index") { id, _ ->
                queried += id
                if (index == 2) kotlinx.coroutines.awaitCancellation()
                AgentToolResult(id, ToolResultContent.Text("Title: Evidence\nURL: https://example.org/$index\nDescription: Result"), false)
            }
        }
        val result = MultiEngineSearchTool(engines, engineTimeoutMillis = 30L).execute("fanout", buildJsonObject { put("query", "one query") })
        assertEquals(4, queried.size)
        assertFalse(result.isError)
        val value = (result.content as ToolResultContent.Json).value.jsonObject
        assertEquals(3, (value["results"] as JsonArray).size)
        assertEquals(4, (value["engines"] as JsonArray).size)
    }

    @Test
    fun `an inner MCP timeout does not cancel successful sibling searches`() = runBlocking {
        val tool = MultiEngineSearchTool(
            listOf(
                engine("inner-timeout") { _, _ -> kotlinx.coroutines.withTimeout(10L) { kotlinx.coroutines.awaitCancellation() } },
                engine("working") { id, _ -> AgentToolResult(id, ToolResultContent.Text("Title: Evidence\nURL: https://example.org/ok\nDescription: Result"), false) }
            ),
            engineTimeoutMillis = 1000L
        )
        assertFalse(tool.execute("nested", buildJsonObject { put("query", "same query") }).isError)
    }

    @Test
    fun `parent cancellation is not swallowed as an engine failure`() = runBlocking {
        val tool = MultiEngineSearchTool(listOf(engine("slow") { _, _ -> kotlinx.coroutines.awaitCancellation() }), engineTimeoutMillis = 10_000L)
        val result = kotlinx.coroutines.withTimeoutOrNull(30L) {
            tool.execute("cancel", buildJsonObject { put("query", "cancel me") })
        }
        org.junit.Assert.assertNull(result)
    }

    @Test
    fun `all enabled marketplace engines dispatch through one web search and normalize results`() = runBlocking {
        val queried = mutableSetOf<String>()
        val fixtures = marketplaceSearchFixtures()
        val engines = fixtures.map { fixture ->
            val name = fixture.getValue("name").jsonPrimitive.content
            val tool = object : AgentTool {
                override val definition = AgentToolDefinition("mcp__provider__$name", "Web search", fixture.getValue("schema").jsonObject)
                override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                    queried += name
                    val expected = JsonObject(
                        fixture.getValue("expectedArguments").jsonObject.mapValues { (key, value) ->
                            if (key in setOf("maxResults", "max_results", "numResults", "limit", "num", "count")) JsonPrimitive(6) else value
                        }
                    )
                    assertEquals(name, expected, arguments)
                    return AgentToolResult(callId, ToolResultContent.Text(fixture.getValue("response").jsonPrimitive.content), false)
                }
            }
            ResolvedAgentTool(tool, name, name, name, tool.definition.name)
        }
        assertEquals(listOf("web_search"), aggregateWebSearch(engines).map { it.modelToolName })
        val responses = listOf(engines).map { batch ->
            MultiEngineSearchTool(batch, Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC)).execute(
                "all",
                buildJsonObject {
                    put("query", "local model speed")
                    put("maxResults", 2)
                    put("includeDomains", JsonArray(listOf(JsonPrimitive("example.org"))))
                    put("recencyDays", 2)
                }
            )
        }
        assertTrue(responses.none { it.isError })
        assertEquals(fixtures.size, queried.size)
        val payloads = responses.map { (it.content as ToolResultContent.Json).value.jsonObject }
        assertEquals(fixtures.size, payloads.sumOf { (it["results"] as JsonArray).size })
        val statuses = payloads.flatMap { (it["engines"] as JsonArray).map { status -> status.jsonObject } }
        assertTrue(statuses.all { it.getValue("status") == JsonPrimitive("completed") })
        assertEquals(4, statuses.count { "unsupportedFilters" in it })
    }

    @Test
    fun `repeated successful research reuses full evidence but changed queries execute`() = runBlocking {
        var calls = 0
        val tool = MultiEngineSearchTool(
            listOf(
                engine("search") { id, _ ->
                    calls++
                    AgentToolResult(id, ToolResultContent.Text("Title: Evidence\nURL: https://example.org/evidence\nDescription: Full exact evidence"), false)
                }
            )
        )
        val arguments = buildJsonObject { put("query", "same query") }
        val first = tool.execute("first", arguments)
        val repeated = tool.execute("second", arguments)
        assertEquals(first.content, repeated.content)
        assertEquals("second", repeated.callId)
        assertTrue(repeated.sharedResult)
        assertEquals(1, calls)
        tool.execute("changed", buildJsonObject { put("query", "different query") })
        assertEquals(2, calls)
    }

    @Test
    fun `failed searches are never cached`() = runBlocking {
        var calls = 0
        val tool = MultiEngineSearchTool(
            listOf(
                engine("search") { id, _ ->
                    calls++
                    AgentToolResult(id, ToolResultContent.Text("Temporarily unavailable"), true)
                }
            )
        )
        val arguments = buildJsonObject { put("query", "same query") }
        tool.execute("first", arguments)
        tool.execute("second", arguments)
        assertEquals(2, calls)
    }

    private fun engine(
        name: String,
        realToolName: String = "web_search",
        extraProperties: JsonObject = JsonObject(emptyMap()),
        action: suspend (String, JsonObject) -> AgentToolResult
    ): ResolvedAgentTool {
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition(
                name,
                "Web search",
                buildJsonObject {
                    put(
                        "properties",
                        buildJsonObject {
                            put("query", buildJsonObject { put("type", "string") })
                            extraProperties.forEach { (key, value) -> put(key, value) }
                        }
                    )
                    put("required", JsonArray(listOf(JsonPrimitive("query"))))
                }
            )
            override suspend fun execute(callId: String, arguments: JsonObject) = action(callId, arguments)
        }
        return ResolvedAgentTool(tool, name, name, realToolName, name)
    }

    @Test fun `official Brave MCP parameters and separate JSON blocks integrate as web search`() = runBlocking {
        val brave = engine(
            "Brave Search",
            "brave_web_search",
            buildJsonObject {
                put("count", buildJsonObject { put("type", "integer") })
                put("freshness", buildJsonObject { put("type", "string") })
                put("result_filter", buildJsonObject { put("type", "array") })
            }
        ) { id, args ->
            assertEquals(JsonPrimitive("Compose site:example.org"), args["query"])
            assertEquals(JsonPrimitive(6), args["count"])
            assertEquals(JsonPrimitive("2026-07-30to2026-08-01"), args["freshness"])
            assertEquals(JsonArray(listOf(JsonPrimitive("web"))), args["result_filter"])
            assertFalse(args.containsKey("maxResults"))
            assertFalse(args.containsKey("includeDomains"))
            mapMcpToolResult(
                id,
                CallToolResult(
                    content = listOf(
                        TextContent("""{"title":"One","url":"https://example.org/one","description":"First source"}"""),
                        TextContent("""{"title":"Two","url":"https://docs.example.org/two","description":"Second source"}"""),
                        TextContent("""{"title":"Other host","url":"https://example.org.other/three","description":"Excluded source"}""")
                    )
                )
            )
        }
        assertTrue(brave.isWebSearchEngine())
        val result = MultiEngineSearchTool(listOf(brave), Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC))
            .execute(
                "brave",
                buildJsonObject {
                    put("query", "Compose")
                    put("maxResults", 2)
                    put("includeDomains", JsonArray(listOf(JsonPrimitive("example.org"))))
                    put("recencyDays", 2)
                }
            )
        assertFalse(result.isError)
        val sources = (result.content as ToolResultContent.Json).value.jsonObject["results"] as JsonArray
        assertEquals(2, sources.size)
        assertEquals("First source", sources.first().jsonObject.getValue("snippet").jsonPrimitive.content)
        assertEquals("Brave Search", sources.first().jsonObject.getValue("engine").jsonPrimitive.content)
        assertTrue(result.content.toString().contains("https://docs.example.org/two"))
        assertFalse(result.content.toString().contains("example.org.other"))
    }

    @Test fun `invalid aggregate parameters never dispatch search requests`() = runBlocking {
        var calls = 0
        val tool = MultiEngineSearchTool(
            listOf(
                engine("Brave") { id, _ ->
                    calls++
                    AgentToolResult(id, ToolResultContent.Text("unexpected"), false)
                }
            )
        )
        for (arguments in listOf(
            buildJsonObject { put("query", true) },
            buildJsonObject {
                put("query", "news")
                put("maxResults", "2")
            },
            buildJsonObject {
                put("query", "news")
                put("maxResults", 0)
            },
            buildJsonObject {
                put("query", "news")
                put("includeDomains", JsonArray(listOf(JsonPrimitive(12))))
            }
        )) {
            assertTrue(tool.execute("invalid", arguments).isError)
        }
        assertEquals(0, calls)
    }

    @Test fun `all selected engines run and duplicate sources retain provenance despite a failed engine`() = runBlocking {
        val queried = mutableSetOf<String>()
        fun search(name: String, url: String) = engine(name) { id, args ->
            queried += name
            assertEquals(JsonPrimitive("Compose"), args["query"])
            AgentToolResult(
                id,
                ToolResultContent.Json(
                    buildJsonObject {
                        put(
                            "results",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("title", name)
                                        put("url", url)
                                        put("snippet", "Found")
                                    }
                                )
                            )
                        )
                    }
                ),
                false
            )
        }
        val broken = engine("broken") { _, _ ->
            queried += "broken"
            error("unavailable")
        }
        val result = MultiEngineSearchTool(listOf(search("one", "https://example.org/doc?utm_source=one"), search("two", "https://example.org/doc#section"), broken))
            .execute("parent", buildJsonObject { put("query", "Compose") })
        assertEquals(setOf("one", "two", "broken"), queried)
        assertFalse(result.isError)
        val json = (result.content as ToolResultContent.Json).value.jsonObject
        val sources = json["results"] as JsonArray
        assertEquals(1, sources.size)
        assertEquals(JsonArray(listOf(JsonPrimitive("one"), JsonPrimitive("two"))), sources.first().jsonObject["engines"])
        assertEquals(3, (json["engines"] as JsonArray).size)
        assertEquals("parent", result.callId)
    }

    @Test fun `child permissions and shared call budget cannot be bypassed by fanout`() = runBlocking {
        var dispatched = 0
        val budget = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 2))
        val engines = (1..4).map { index ->
            val resolved = engine("engine$index") { id, _ ->
                dispatched++
                AgentToolResult(id, ToolResultContent.Text("source"), false)
            }
            resolved.copy(tool = budget.bind(resolved.tool, authorize = { _, _ -> index != 1 }))
        }
        val result = MultiEngineSearchTool(engines).execute("parent", buildJsonObject { put("query", "news") })
        assertEquals(1, dispatched)
        assertTrue(result.toolCallBudgetExhausted)
        assertFalse(result.outputBudgetExhausted)
        assertEquals(2, result.toolCallBudgetUsed)
        assertEquals(2, result.toolCallBudgetLimit)
        assertFalse(result.isError)
    }

    @Test fun `exhausted byte budget skips all engine executions`() = runBlocking {
        var calls = 0
        val engines = (1..5).map { number ->
            engine("engine$number") { id, _ ->
                calls++
                AgentToolResult(id, ToolResultContent.Text("source"), false)
            }
        }
        val result = MultiEngineSearchTool(engines, canExecute = { false }, remainingBytes = { 0 })
            .execute("empty", buildJsonObject { put("query", "news") })
        assertEquals(0, calls)
        assertTrue(result.isError)
        assertTrue(result.outputBudgetExhausted)
    }

    @Test fun `explicit web search descriptions are eligible even with a nonstandard name`() {
        val web = engine("web") { _, _ -> error("not executed") }
        assertTrue(web.isWebSearchEngine())
        assertTrue(web.copy(realToolName = "slack_search").isWebSearchEngine())
        assertTrue(web.copy(realToolName = "github_search_code").isWebSearchEngine())
        assertTrue(web.copy(realToolName = "brave_web_search").isWebSearchEngine())
        assertEquals(listOf("web_search"), aggregateWebSearch(listOf(web)).map { it.modelToolName })
    }

    @Test fun `one timed out engine cannot discard the other engine evidence`() = runBlocking {
        var slowClosed = false
        val slow = engine("slow") { _, _ ->
            try {
                awaitCancellation()
            } finally {
                slowClosed = true
            }
        }
        val fast = engine("fast") { id, _ ->
            AgentToolResult(id, ToolResultContent.Text("Title: Evidence\nURL: https://example.org/fast\nDescription: Complete"), false)
        }
        val result = withTimeout(1_000) {
            MultiEngineSearchTool(listOf(slow, fast), engineTimeoutMillis = 25L)
                .execute("timeout", buildJsonObject { put("query", "test") })
        }
        assertTrue(slowClosed)
        assertFalse(result.isError)
        val json = (result.content as ToolResultContent.Json).value.jsonObject
        assertEquals(1, (json["results"] as JsonArray).size)
        assertTrue((json["engines"] as JsonArray).any { it.jsonObject["status"] == JsonPrimitive("unavailable") })
    }

    @Test fun `optional crawling failure does not turn search evidence into an error`() = runBlocking {
        val search = engine("search") { id, _ ->
            AgentToolResult(id, ToolResultContent.Text("Title: Evidence\nURL: https://example.org/doc\nDescription: Complete"), false)
        }
        val result = MultiEngineSearchTool(listOf(search), afterSearch = { _, _ -> error("Crawler failed") })
            .execute("crawl", buildJsonObject { put("query", "test") })
        assertFalse(result.isError)
        val json = (result.content as ToolResultContent.Json).value.jsonObject
        assertEquals(1, (json["results"] as JsonArray).size)
        assertEquals(JsonPrimitive("unavailable"), json.getValue("pages").jsonObject["status"])
    }

    @Test fun `parent cancellation propagates rather than becoming engine failure`() = runBlocking {
        var closed = false
        val hanging = engine("hanging") { _, _ ->
            try {
                awaitCancellation()
            } finally {
                closed = true
            }
        }
        var cancelled = false
        try {
            withTimeout(50L) {
                MultiEngineSearchTool(listOf(hanging), engineTimeoutMillis = 10_000L)
                    .execute("cancel", buildJsonObject { put("query", "test") })
            }
        } catch (_: TimeoutCancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertTrue(closed)
    }

    @Test fun `sequential mode still visits every unique selected engine`() = runBlocking {
        val calls = mutableListOf<String>()
        val engines = (1..4).map { index ->
            engine("engine$index") { id, _ ->
                calls += "engine$index"
                AgentToolResult(id, ToolResultContent.Text("source"), false)
            }
        }
        MultiEngineSearchTool(engines + engines.first(), parallel = false)
            .execute("sequential", buildJsonObject { put("query", "test") })
        assertEquals(listOf("engine1", "engine2", "engine3", "engine4"), calls)
    }
}
