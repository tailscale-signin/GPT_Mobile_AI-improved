package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.catalog.McpPresetCatalog
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun marketplaceSearchFixtures(): List<JsonObject> = requireNotNull(
    WebSearchEngineAdapterTest::class.java.getResourceAsStream("/web_search/marketplace-contracts.json")
).bufferedReader().use { reader -> (Json.parseToJsonElement(reader.readText()) as JsonArray).map { it.jsonObject } }

class WebSearchEngineAdapterTest {
    @Test
    fun `multi search recognizes web schemas without hiding memory search`() {
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("query", buildJsonObject { put("type", "string") }) })
            put("required", JsonArray(listOf(JsonPrimitive("query"))))
        }
        org.junit.Assert.assertNotNull(WebSearchEngineAdapter.forTool("multi_search", AgentToolDefinition("multi_search", "Search several web search engines", schema)))
        org.junit.Assert.assertNull(WebSearchEngineAdapter.forTool("multi_search", AgentToolDefinition("multi_search", "Search stored memory records", schema)))
    }

    @Test
    fun `every marketplace search tool has a tested provider contract`() {
        val expected = McpPresetCatalog.presets.flatMap { preset -> preset.webSearchToolNames.map { preset.id to it } }.toSet()
        assertEquals(
            setOf("builtin-web", "exa-mcp", "tavily-mcp", "firecrawl-mcp", "jina-mcp", "brave-search", "bright-data", "searxng-mcp", "free-search-mcp"),
            expected.map { it.first }.toSet()
        )
        val actual = marketplaceSearchFixtures().map { it.getValue("preset").jsonPrimitive.content to it.getValue("name").jsonPrimitive.content }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `marketplace schemas map query counts domains dates and batch parameters`() {
        val request = Json.parseToJsonElement("""{"query":"local model speed","maxResults":2,"includeDomains":["example.org"],"recencyDays":2}""").jsonObject
        val clock = Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC)
        marketplaceSearchFixtures().forEach { fixture ->
            val name = fixture.getValue("name").jsonPrimitive.content
            val definition = AgentToolDefinition("mcp__provider__$name", "Web search", fixture.getValue("schema").jsonObject)
            val adapter = requireNotNull(WebSearchEngineAdapter.forTool(name, definition)) { name }
            assertEquals(name, fixture["expectedArguments"], adapter.arguments(request, clock))
            assertEquals(name, fixture.getValue("unsupportedRecency").jsonPrimitive.content == "false", adapter.supportsRecency)
        }
    }

    @Test
    fun `published marketplace response formats produce reusable sources`() {
        marketplaceSearchFixtures().forEach { fixture ->
            val name = fixture.getValue("name").jsonPrimitive.content
            val sources = extractSearchSources(parseSearchPayload(fixture.getValue("response").jsonPrimitive.content))
            assertEquals(name, 1, sources.size)
            assertEquals(name, fixture["url"], sources.single()["url"])
            assertTrue(name, sources.single().getValue("snippet").jsonPrimitive.content.contains("snippet"))
            assertTrue(name, sources.single().getValue("title").jsonPrimitive.content.isNotBlank())
        }
    }

    @Test
    fun `private specialized and unsatisfied required schemas stay outside web search`() {
        val schema = Json.parseToJsonElement("""{"properties":{"query":{"type":"string"}},"required":["query"]}""").jsonObject
        for (name in listOf("slack_search", "github_search_code", "brave_news_search", "firecrawl_scrape", "search_arxiv", "cloudflare_radar_search")) {
            assertNull(name, WebSearchEngineAdapter.forTool(name, AgentToolDefinition(name, "Search", schema)))
        }
        assertTrue(WebSearchEngineAdapter.forTool("web_search_images", AgentToolDefinition("web_search_images", "Search", schema)) != null)
        val scoped = Json.parseToJsonElement("""{"properties":{"query":{"type":"string"},"workspace_id":{"type":"string"}},"required":["query","workspace_id"]}""").jsonObject
        assertNull(WebSearchEngineAdapter.forTool("web_search", AgentToolDefinition("web_search", "Search the web", scoped)))
        val invalidQuery = Json.parseToJsonElement("""{"properties":{"query":{"type":"boolean"}},"required":["query"]}""").jsonObject
        assertNull(WebSearchEngineAdapter.forTool("web_search", AgentToolDefinition("web_search", "Search the web", invalidQuery)))
    }

    @Test
    fun `free search uses structured output a fresh cache window and honest time range bounds`() {
        val fixture = marketplaceSearchFixtures().first { it["name"] == JsonPrimitive("free_search__search") }
        val adapter = requireNotNull(WebSearchEngineAdapter.forTool("free_search__search", AgentToolDefinition("free_search__search", "Web search", fixture["schema"]!!.jsonObject)))
        for ((days, expected) in listOf(1 to "day", 2 to "week", 7 to "week", 14 to "month", 60 to "year", 365 to "year")) {
            val args = adapter.arguments(
                buildJsonObject {
                    put("query", "test")
                    put("recencyDays", days)
                },
                Clock.systemUTC()
            )
            assertEquals(JsonPrimitive(expected), args["freshness"])
            assertEquals(JsonPrimitive("json"), args["format"])
            assertEquals(JsonPrimitive(0.25), args["max_age_hours"])
            assertFalse(args.containsKey("engines"))
        }
        assertFalse(adapter.supportsRecencyFor(400))
        assertNull(
            adapter.arguments(
                buildJsonObject {
                    put("query", "test")
                    put("recencyDays", 400)
                },
                Clock.systemUTC()
            )["freshness"]
        )
        val privateSearch = AgentToolDefinition("search", "Search private records", Json.parseToJsonElement("""{"properties":{"query":{"type":"string"}},"required":["query"]}""").jsonObject)
        assertNull(WebSearchEngineAdapter.forTool("search", privateSearch))
    }

    @Test
    fun `string arrays q aliases and older Firecrawl source objects are supported`() {
        val request = buildJsonObject { put("query", "Compose") }
        for ((name, schema, expected) in listOf(
            Triple("search_web", """{"properties":{"query":{"type":"array","items":{"type":"string"}}},"required":["query"]}""", """{"query":["Compose"]}"""),
            Triple("duckduckgo_search", """{"properties":{"q":{"type":"string"},"num_results":{"type":"integer"}},"required":["q"]}""", """{"q":"Compose","num_results":10}"""),
            Triple("firecrawl_search", """{"properties":{"query":{"type":"string"},"sources":{"type":"array","items":{"type":"object","properties":{"type":{"type":"string"}}}}},"required":["query"]}""", """{"query":"Compose","sources":[{"type":"web"}]}""")
        )) {
            val definition = AgentToolDefinition(name, "Web search", Json.parseToJsonElement(schema).jsonObject)
            assertEquals(Json.parseToJsonElement(expected), requireNotNull(WebSearchEngineAdapter.forTool(name, definition)).arguments(request, Clock.systemUTC()))
        }
    }

    @Test
    fun `joined YAML and labeled results preserve separate sources and multiline descriptions`() {
        val text = """
            title: 'First: result'
            url: https://example.org/one
            description: >-
              First line
              second line
            title: "Second result"
            url: https://example.org/two
            description: Second snippet
        """.trimIndent()
        val sources = extractSearchSources(parseSearchPayload(text))
        assertEquals(2, sources.size)
        assertEquals("First: result", sources[0].getValue("title").jsonPrimitive.content)
        assertEquals("First line\nsecond line", sources[0].getValue("snippet").jsonPrimitive.content)
        assertEquals("https://example.org/two", sources[1].getValue("url").jsonPrimitive.content)
        assertFalse(matchesSearchDomains("https://example.org.attacker.test/page", listOf("example.org"), emptyList()))
        assertFalse(matchesSearchDomains("javascript:alert(1)", emptyList(), emptyList()))
        assertTrue(matchesSearchDomains("https://docs.example.org/page", listOf("example.org"), emptyList()))
    }

    @Test
    fun `SearXNG refill advances pageno without inventing week support`() {
        val fixture = marketplaceSearchFixtures().single { it["name"] == JsonPrimitive("searxng_web_search") }
        val definition = AgentToolDefinition("searxng_web_search", "Web search", fixture.getValue("schema").jsonObject)
        val adapter = requireNotNull(WebSearchEngineAdapter.forTool("searxng_web_search", definition))
        val request = buildJsonObject {
            put("query", "captions")
            put("recencyDays", 7)
        }
        val args = adapter.arguments(request, Clock.systemUTC())
        assertEquals(JsonPrimitive("month"), args["time_range"])
        assertEquals(JsonPrimitive(1), args["pageno"])
        assertEquals(JsonPrimitive(2), adapter.nextPage(request, Clock.systemUTC())?.get("pageno"))
    }
}
