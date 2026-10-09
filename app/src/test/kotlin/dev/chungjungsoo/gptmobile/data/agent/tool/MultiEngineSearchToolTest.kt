package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolExecutionBudget
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.withRunContext
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
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
    @Test fun `all enabled engines start together and crawler receives unique pages`() = runBlocking {
        val started = java.util.concurrent.atomic.AtomicInteger()
        val allStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        var crawled = 0
        val engines = (1..5).map { index ->
            engine("parallel-$index") { id, _ ->
                if (started.incrementAndGet() == 5) allStarted.complete(Unit)
                withTimeout(1_000) { allStarted.await() }
                AgentToolResult(id, ToolResultContent.Json(buildJsonObject { put("results", JsonArray(listOf(buildJsonObject { put("url", "https://example.org/shared") }, buildJsonObject { put("url", "https://example.org/unique-$index") }))) }), false)
            }
        }
        val result = MultiEngineSearchTool(engines, afterSearch = { _, pages ->
            assertEquals(6, pages.size)
            crawled++
            buildJsonObject { put("status", "completed") }
        }).execute("all", buildJsonObject { put("query", "test") })
        assertFalse(result.isError)
        assertEquals(5, started.get())
        assertEquals(1, crawled)
    }

    @Test fun `duplicate result slots are filled from a supported second page`() = runBlocking {
        val first = engine("first") { id, _ -> AgentToolResult(id, ToolResultContent.Text("Title: A\nURL: https://example.org/a\nDescription: A"), false) }
        val next = engine(
            "next",
            extraProperties = buildJsonObject {
                put(
                    "page",
                    buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", 2)
                    }
                )
            }
        ) { id, args ->
            AgentToolResult(id, ToolResultContent.Text("Title: Next\nURL: https://example.org/${if (args["page"] == JsonPrimitive(2)) "b" else "a"}\nDescription: Result"), false)
        }
        val result = MultiEngineSearchTool(listOf(first, next), policy = SearchMergePolicy(fetchLimitPerEngine = 1)).execute(
            "refill",
            buildJsonObject {
                put("query", "test")
                put("maxResults", 1)
            }
        )
        assertEquals(2, ((result.content as ToolResultContent.Json).value.jsonObject["results"] as JsonArray).size)
    }

    @Test fun `URL identity preserves distinct query pages and encoded paths`() {
        assertEquals(canonicalSearchUrl("https://example.org:443/a?b=2&utm_source=test&a=1"), canonicalSearchUrl("https://example.org/a?b=2&a=1"))
        assertFalse(canonicalSearchUrl("https://example.org/a#section") == canonicalSearchUrl("https://example.org/a"))
        assertFalse(canonicalSearchUrl("https://example.org/a?page=1") == canonicalSearchUrl("https://example.org/a?page=2"))
        assertFalse(canonicalSearchUrl("https://example.org/a%2Fb") == canonicalSearchUrl("https://example.org/a/b"))
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
                    val expected = fixture.getValue("expectedArguments").jsonObject
                    val properties = fixture.getValue("schema").jsonObject["properties"]!!.jsonObject
                    val fetched = expected.mapValues { (key, value) ->
                        if (key in setOf("maxResults", "max_results", "numResults", "num_results", "count", "limit", "num")) {
                            val field = properties[key] as? JsonObject
                            val minimum = (field?.get("minimum") as? JsonPrimitive)?.content?.toIntOrNull() ?: 1
                            val maximum = (field?.get("maximum") as? JsonPrimitive)?.content?.toIntOrNull() ?: 10
                            JsonPrimitive(10.coerceIn(minimum, maximum))
                        } else {
                            value
                        }
                    }
                    assertEquals(name, JsonObject(fetched), arguments)
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

    @Test fun `completion speed never changes source ordering or late provenance`() = runBlocking {
        for (seed in 1..8) {
            val engines = (0..3).map { owner ->
                engine("engine-$owner") { id, _ ->
                    kotlinx.coroutines.delay(((owner * seed + 3) % 7).toLong())
                    AgentToolResult(
                        id,
                        ToolResultContent.Json(
                            buildJsonObject {
                                put("results", JsonArray((1..10).map { rank -> buildJsonObject { put("url", "https://example.org/$owner-$rank") } }))
                            }
                        ),
                        false
                    )
                }
            }
            val result = MultiEngineSearchTool(engines).execute(
                "ordered-$seed",
                buildJsonObject {
                    put("query", "test")
                    put("totalResults", 20)
                }
            )
            val sources = ((result.content as ToolResultContent.Json).value.jsonObject["results"] as JsonArray)
            assertEquals((1..5).flatMap { rank -> (0..3).map { owner -> "https://example.org/$owner-$rank" } }, sources.map { it.jsonObject["url"]!!.jsonPrimitive.content })
        }
    }

    @Test fun `legacy caps new total quotas and local over-return bounds remain separate`() = runBlocking {
        val engines = (0..2).map { owner ->
            engine("owner-$owner") { id, _ ->
                AgentToolResult(id, ToolResultContent.Json(buildJsonObject { put("results", JsonArray((1..30).map { rank -> buildJsonObject { put("url", "https://example.org/$owner-$rank") } })) }), false)
            }
        }
        val tool = MultiEngineSearchTool(engines)
        suspend fun count(args: JsonObject) = ((tool.execute("quota", args).content as ToolResultContent.Json).value.jsonObject["results"] as JsonArray).size
        assertEquals(
            3,
            count(
                buildJsonObject {
                    put("query", "test")
                    put("maxResults", 1)
                }
            )
        )
        assertEquals(
            2,
            count(
                buildJsonObject {
                    put("query", "test")
                    put("totalResults", 2)
                }
            )
        )
        assertEquals(
            30,
            count(
                buildJsonObject {
                    put("query", "test")
                    put("totalResults", 50)
                }
            )
        )
        val single = MultiEngineSearchTool(engines.take(1)).execute(
            "single",
            buildJsonObject {
                put("query", "test")
                put("totalResults", 20)
            }
        )
        assertEquals(10, ((single.content as ToolResultContent.Json).value.jsonObject["results"] as JsonArray).size)
    }

    @Test fun `provider count caps and missing count fields are reflected without invented parameters`() = runBlocking {
        val capped = engine(
            "capped",
            extraProperties = buildJsonObject {
                put(
                    "count",
                    buildJsonObject {
                        put("type", "integer")
                        put("maximum", 3)
                    }
                )
            }
        ) { id, args ->
            assertEquals(JsonPrimitive(3), args["count"])
            AgentToolResult(id, ToolResultContent.Json(buildJsonObject { put("results", JsonArray((1..10).map { buildJsonObject { put("url", "https://example.org/cap-$it") } })) }), false)
        }
        val unsupported = engine("unsupported") { id, args ->
            assertEquals(setOf("query"), args.keys)
            AgentToolResult(id, ToolResultContent.Json(buildJsonObject { put("results", JsonArray(emptyList())) }), false)
        }
        val result = MultiEngineSearchTool(listOf(capped, unsupported)).execute(
            "caps",
            buildJsonObject {
                put("query", "test")
                put("totalResults", 20)
            }
        )
        val value = (result.content as ToolResultContent.Json).value.jsonObject
        assertEquals(3, (value["results"] as JsonArray).size)
        assertEquals(JsonPrimitive(3), (value["engines"] as JsonArray).first().jsonObject["effectiveCount"])
        assertEquals(JsonPrimitive("empty"), (value["engines"] as JsonArray).last().jsonObject["outcome"])
    }

    @Test fun `a full target never triggers pagination`() = runBlocking {
        var calls = 0
        val source = engine("pages", extraProperties = buildJsonObject { put("page", buildJsonObject { put("type", "integer") }) }) { id, args ->
            calls++
            assertFalse(args.containsKey("page"))
            AgentToolResult(id, ToolResultContent.Text("Title: A\nURL: https://example.org/a"), false)
        }
        MultiEngineSearchTool(listOf(source), policy = SearchMergePolicy(fetchLimitPerEngine = 1)).execute(
            "filled",
            buildJsonObject {
                put("query", "test")
                put("totalResults", 1)
            }
        )
        assertEquals(1, calls)
    }

    @Test fun `inner refill timeouts preserve initial evidence and status`() = runBlocking {
        var refillCalls = 0
        fun paged(name: String) = engine(name, extraProperties = buildJsonObject { put("page", buildJsonObject { put("type", "integer") }) }) { id, args ->
            if (args.containsKey("page")) {
                refillCalls++
                withTimeout(5) { awaitCancellation() }
            } else {
                AgentToolResult(id, ToolResultContent.Text("Title: A\nURL: https://example.org/a"), false, toolCallBudgetUsed = 2)
            }
        }
        val result = MultiEngineSearchTool(listOf(paged("one"), paged("two")), policy = SearchMergePolicy(fetchLimitPerEngine = 1)).execute(
            "refill-timeout",
            buildJsonObject {
                put("query", "test")
                put("maxResults", 1)
            }
        )
        assertFalse(result.isError)
        assertEquals(2, refillCalls)
        assertEquals(2, result.toolCallBudgetUsed)
        val value = (result.content as ToolResultContent.Json).value.jsonObject
        assertEquals(1, (value["results"] as JsonArray).size)
        assertTrue((value["engines"] as JsonArray).all { it.jsonObject["refill"]!!.jsonObject["outcome"] == JsonPrimitive("timeout") })
    }

    @Test fun `refill preserves original and additional shared budget snapshots`() = runBlocking {
        val paged = engine("pages", extraProperties = buildJsonObject { put("page", buildJsonObject { put("type", "integer") }) }) { id, args ->
            if (args.containsKey("page")) {
                AgentToolResult(id, ToolResultContent.Text("Budget exhausted"), true, outputBudgetExhausted = true, toolCallBudgetExhausted = true, toolCallBudgetUsed = 4, toolResultBudgetUsedBytes = 1000)
            } else {
                AgentToolResult(id, ToolResultContent.Text("Title: A\nURL: https://example.org/a"), false, toolCallBudgetUsed = 3, toolResultBudgetUsedBytes = 500)
            }
        }
        val result = MultiEngineSearchTool(listOf(paged), policy = SearchMergePolicy(fetchLimitPerEngine = 1)).execute(
            "budget-refill",
            buildJsonObject {
                put("query", "test")
                put("totalResults", 2)
            }
        )
        assertFalse(result.isError)
        assertTrue(result.outputBudgetExhausted)
        assertTrue(result.toolCallBudgetExhausted)
        assertEquals(4, result.toolCallBudgetUsed)
        assertEquals(1000, result.toolResultBudgetUsedBytes)
    }

    @Test fun `permission waiting is serial and outside provider deadlines`() = kotlinx.coroutines.test.runTest {
        val authorized = mutableListOf<Int>()
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        var dispatched = 0
        val budget = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 10, maxConcurrentTools = 3))
        val engines = (1..2).map { owner ->
            val resolved = engine("consent-$owner") { id, _ ->
                assertEquals(listOf(1, 2), authorized)
                if (++dispatched == 2) started.complete(Unit)
                started.await()
                AgentToolResult(id, ToolResultContent.Text("Title: Evidence\nURL: https://example.org/$owner"), false)
            }
            resolved.copy(
                tool = budget.bind(resolved.tool, authorize = { _, _ ->
                    kotlinx.coroutines.delay(9000)
                    authorized += owner
                    true
                })
            )
        }
        val result = MultiEngineSearchTool(engines, nanoTime = { testScheduler.currentTime * 1_000_000 }).execute("consent", buildJsonObject { put("query", "test") })
        assertFalse(result.isError)
        assertEquals(2, dispatched)
        assertEquals(2, result.toolCallBudgetUsed)
    }

    @Test fun `permission and configuration changes invalidate request reuse independently of URL grouping`() = runBlocking {
        var calls = 0
        var permitted = true
        var revision = "one"
        val source = engine("search") { id, _ ->
            calls++
            AgentToolResult(id, ToolResultContent.Text("Title: Evidence\nURL: https://example.org/a"), false)
        }.copy(canReuseResult = { permitted })
        val tool = MultiEngineSearchTool(listOf(source), policy = SearchMergePolicy(dedupeUrls = false), configurationRevision = { revision })
        val args = buildJsonObject { put("query", "test") }
        tool.execute("first", args)
        assertTrue(tool.execute("shared", args).sharedResult)
        assertEquals(1, calls)
        revision = "two"
        assertFalse(tool.execute("changed", args).sharedResult)
        assertEquals(2, calls)
        permitted = false
        assertFalse(tool.execute("revoked", args).sharedResult)
        assertEquals(3, calls)
    }

    @Test fun `no engines and invalid total counts produce outcomes without network work`() = runBlocking {
        val result = MultiEngineSearchTool(emptyList()).execute("none", buildJsonObject { put("query", "test") })
        assertTrue(result.isError)
        assertEquals(JsonPrimitive("no_engines"), (result.content as ToolResultContent.Json).value.jsonObject["outcome"])
        for (count in listOf(JsonPrimitive(0), JsonPrimitive(51), JsonPrimitive("20"))) {
            assertTrue(
                MultiEngineSearchTool(emptyList()).execute(
                    "invalid",
                    buildJsonObject {
                        put("query", "test")
                        put("totalResults", count)
                    }
                ).isError
            )
        }
    }

    @Test fun `parent cancellation during refill closes children and writes no cached search`() = runBlocking {
        var hanging = true
        var calls = 0
        var closed = 0
        var refillStarted = 0
        val bothRefills = kotlinx.coroutines.CompletableDeferred<Unit>()
        fun paged(name: String) = engine(name, extraProperties = buildJsonObject { put("page", buildJsonObject { put("type", "integer") }) }) { id, args ->
            calls++
            if (args.containsKey("page") && hanging) {
                if (++refillStarted == 2) bothRefills.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    closed++
                }
            } else {
                AgentToolResult(id, ToolResultContent.Text("Title: Source\nURL: https://example.org/${if (args.containsKey("page")) name else "shared"}"), false)
            }
        }
        val tool = MultiEngineSearchTool(listOf(paged("one"), paged("two")), policy = SearchMergePolicy(fetchLimitPerEngine = 1))
        val args = buildJsonObject {
            put("query", "test")
            put("maxResults", 1)
        }
        val pending = async { tool.execute("cancel-refill", args) }
        withTimeout(1000) { bothRefills.await() }
        pending.cancel()
        pending.join()
        assertTrue(pending.isCancelled)
        assertEquals(2, closed)
        hanging = false
        val recovered = tool.execute("fresh", args)
        assertFalse(recovered.sharedResult)
        assertFalse(recovered.isError)
        assertEquals(8, calls)
    }

    @Test fun `refill receives only remaining search time and preserves successful siblings`() = kotlinx.coroutines.test.runTest {
        var refillStarted = 0
        fun paged(name: String) = engine(name, extraProperties = buildJsonObject { put("page", buildJsonObject { put("type", "integer") }) }) { id, args ->
            if (args.containsKey("page")) {
                refillStarted++
                kotlinx.coroutines.delay(6000)
            } else {
                kotlinx.coroutines.delay(7000)
            }
            AgentToolResult(id, ToolResultContent.Text("Title: Source\nURL: https://example.org/${if (args.containsKey("page")) name else "shared"}"), false)
        }
        val tool = MultiEngineSearchTool(listOf(paged("one"), paged("two")), policy = SearchMergePolicy(fetchLimitPerEngine = 1), nanoTime = { testScheduler.currentTime * 1_000_000 })
        val result = tool.execute(
            "deadline",
            buildJsonObject {
                put("query", "test")
                put("maxResults", 1)
            }
        )
        assertFalse(result.isError)
        assertEquals(2, refillStarted)
        assertEquals(12_000L, testScheduler.currentTime)
        val value = (result.content as ToolResultContent.Json).value.jsonObject
        assertEquals(1, (value["results"] as JsonArray).size)
        assertTrue((value["engines"] as JsonArray).all { it.jsonObject["refill"]!!.jsonObject["outcome"] == JsonPrimitive("timeout") })
    }

    @Test fun `gateway engines retain ownership through wrappers and never join a client batch`() {
        val gateway = object : dev.chungjungsoo.gptmobile.data.agent.OwnedAgentTool {
            override val definition = engine("gateway") { _, _ -> error("unused") }.tool.definition
            override val executionOwner = dev.chungjungsoo.gptmobile.data.agent.AgentToolExecutionOwner.GATEWAY
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("Gateway tools must stay on their owner route")
        }
        val configured = ConfiguredPluginTool(gateway, dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings())
        val shared = SharedToolCallBroker().wrap("turn", "gateway", true, configured)
        val owned = ToolExecutionBudget(AgentRunLimits()).bind(shared.withRunContext("run"))
        assertEquals(dev.chungjungsoo.gptmobile.data.agent.AgentToolExecutionOwner.GATEWAY, (owned as dev.chungjungsoo.gptmobile.data.agent.OwnedAgentTool).executionOwner)
        val remote = ResolvedAgentTool(owned, "gateway", "Gateway", "web_search", "gateway")
        val client = engine("client") { _, _ -> error("Not executed") }
        val exposed = aggregateWebSearch(listOf(client, remote))
        assertEquals(2, exposed.size)
        assertTrue(remote in exposed)
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
            assertEquals(JsonPrimitive(10), args["count"])
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
        val result = MultiEngineSearchTool(listOf(search("one", "https://example.org/doc?utm_source=one"), search("two", "https://example.org/doc?utm_source=two"), broken))
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

    @Test fun `legacy sequential argument still starts all unique selected engines concurrently`() = runBlocking {
        val calls = mutableListOf<String>()
        val allStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val engines = (1..4).map { index ->
            engine("engine$index") { id, _ ->
                calls += "engine$index"
                if (calls.size == 4) allStarted.complete(Unit)
                withTimeout(1000) { allStarted.await() }
                AgentToolResult(id, ToolResultContent.Text("source"), false)
            }
        }
        MultiEngineSearchTool(engines + engines.first(), parallel = false)
            .execute("sequential", buildJsonObject { put("query", "test") })
        assertEquals(listOf("engine1", "engine2", "engine3", "engine4"), calls)
    }
}
