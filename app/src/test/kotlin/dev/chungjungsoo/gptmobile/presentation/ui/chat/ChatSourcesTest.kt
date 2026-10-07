package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.agent.ToolResultCheckpoint
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventResultType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSourcesTest {
    @Test
    fun `multi-search combines engines and keeps every distinct page`() {
        val payload = """{
            "engines":[{"engine":"Brave MCP","tool":"brave_search","status":"completed"},
                       {"engine":"Google","status":"completed"},
                       {"engine":"Bing","status":"unavailable"}],
            "results":[{"url":"https://en.wikipedia.org/wiki/Android","title":"Android","engines":["Google","Brave MCP"]},
                       {"url":"https://en.wikipedia.org/wiki/Kotlin","title":"Kotlin"}]
        }"""

        val result = collectChatSources("", listOf(event(payload)))

        assertEquals(listOf("Brave Search", "Google"), result.engines)
        assertEquals(listOf("Android", "Kotlin"), result.sources.map { it.title })
    }

    @Test
    fun `large source lists are not truncated at thirty or reduced to one per site`() {
        val payload = buildJsonObject {
            put(
                "results",
                JsonArray(
                    (1..65).map { index ->
                        buildJsonObject {
                            put("url", "https://example.org/paper/$index")
                            put("title", "Paper $index")
                        }
                    }
                )
            )
        }.toString()

        val result = collectChatSources("", listOf(event(payload)))

        assertEquals(65, result.sources.size)
        assertEquals("Paper 65", result.sources.last().title)
    }

    @Test
    fun `tracking fragments and duplicate events collapse without replacing the original link`() {
        val first = event("""{"results":[{"url":"https://www.example.org/a?utm_source=search&id=7#overview"}]}""")
        val second = event("""{"results":[{"url":"https://example.org/a?id=7#details","title":"Useful title"}]}""", id = "second")

        val result = collectChatSources("[Citation](https://example.org/a?id=7)", listOf(first, first, second))

        assertEquals(1, result.sources.size)
        assertEquals("Useful title", result.sources.single().title)
        assertEquals("https://www.example.org/a?utm_source=search&id=7#overview", result.sources.single().url)
        assertEquals("example.org/a", result.sources.single().compactLink)
    }

    @Test
    fun `meaningful query parameters keep different source pages distinct`() {
        val result = collectChatSources("https://example.org/article?id=1 https://example.org/article?id=2", emptyList())

        assertEquals(2, result.sources.size)
    }

    @Test
    fun `nested MCP text blocks and local evidence expose real references`() {
        val nested = buildJsonObject {
            put(
                "content",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", "text")
                            put("text", """{"kind":"local_evidence","sources":[{"id":"S1","url":"https://nasa.gov/science","title":"NASA science"}]}""")
                        }
                    )
                )
            )
        }.toString()

        val result = collectChatSources("Based on [S1].", listOf(event(nested, tool = "delegate_to_model")))

        assertEquals("NASA science", result.sources.single().title)
        assertEquals("nasa", chatSourceBrand(result.sources.single().host)?.id)
    }

    @Test
    fun `checkpointed research uses complete payload rather than the abbreviated display`() {
        val source = event(
            ToolResultCheckpoint.encode(
                """{"sources":[{"url":"https://nasa.gov/science","title":"Saved source"}]}""",
                "JSON",
                "Research complete."
            ),
            tool = "delegate_to_model"
        ).copy(resultType = ToolEventResultType.CHECKPOINT)

        assertEquals("Saved source", collectChatSources("", listOf(source)).sources.single().title)
    }

    @Test
    fun `reader arguments retain the gathered URL without listing outbound article links`() {
        val result = collectChatSources(
            "",
            listOf(event("""{"url":"https://example.org/article","title":"Article","content":"See [Advertisement](https://ads.example.net/buy)."}""", tool = "read_url"))
        )
        assertEquals(listOf("https://example.org/article"), result.sources.map { it.url })

        val plainRead = event("Article text. [Advertisement](https://ads.example.net/buy).", tool = "read_url", arguments = """{"urls":["https://example.org/a","https://example.org/b"]}""")
        assertEquals(2, collectChatSources("", listOf(plainRead)).sources.size)
    }

    @Test
    fun `failed pending and unrelated tool results never become gathered sources`() {
        val payload = """{"url":"https://example.org/source"}"""
        val events = listOf(
            event(payload).copy(isError = true),
            event(payload, id = "pending").copy(status = ToolEventStatus.RUNNING),
            event(payload, id = "failed").copy(status = ToolEventStatus.FAILED),
            event(payload, id = "unrelated", tool = "create_issue")
        )

        assertTrue(collectChatSources("", events).isEmpty)
    }

    @Test
    fun `unsafe schemes and malformed links are ignored`() {
        val payload = """{"results":[{"url":"javascript:alert(1)"},{"url":"file:///secret"},
            {"url":"https://user:password@example.org/private"},{"url":"https://[broken"},
            {"url":"https://example.org/good","title":"Good source"}]}"""

        assertEquals(listOf("Good source"), collectChatSources("", listOf(event(payload))).sources.map { it.title })
    }

    @Test
    fun `answer citations handle punctuation and preserve balanced URL parentheses`() {
        val result = collectChatSources("[Topic](https://en.wikipedia.org/wiki/Topic_(subject)). Bare https://example.org/read.", emptyList())

        assertEquals(listOf("https://en.wikipedia.org/wiki/Topic_(subject)", "https://example.org/read"), result.sources.map { it.url })
        assertEquals("Topic", result.sources.first().title)
    }

    @Test
    fun `compact link omits tracking but opening retains the original URL`() {
        val source = collectChatSources("https://www.example.org/path%20name?utm_source=a#section", emptyList()).sources.single()

        assertEquals("example.org/path%20name", source.compactLink)
        assertEquals("https://www.example.org/path%20name?utm_source=a#section", source.url)
    }

    @Test
    fun `labeled search text preserves source titles`() {
        val result = collectChatSources("", listOf(event("Title: Kotlin guide\nURL: https://kotlinlang.org/docs/home.html\nDescription: Reference")))

        assertEquals("Kotlin guide", result.sources.single().title)
    }

    @Test
    fun `engine strings are deduplicated across nested results`() {
        val payload = """{"engines":["DuckDuckGo","duckduckgo"],"results":[{"url":"https://example.org","engine":"DDG"}]}"""

        assertEquals(listOf("DuckDuckGo"), collectChatSources("", listOf(event(payload))).engines)
    }

    @Test
    fun `provider metadata replaces generic built-in labels in the footer`() {
        val payload = """{"engines":[{"engine":"Built-in search","engines":["DuckDuckGo"],"status":"completed"}],
            "results":[{"url":"https://example.org","engine":"DuckDuckGo","engines":["Built-in search","DuckDuckGo"]}]}"""

        assertEquals(listOf("DuckDuckGo"), collectChatSources("", listOf(event(payload))).engines)
    }

    @Test
    fun `custom search connections remain visible without invented provider names`() {
        val search = event("""{"results":[{"url":"https://example.org"}]}""").copy(connectionNameSnapshot = "Ultra Search")

        assertEquals(listOf("Ultra Search"), collectChatSources("", listOf(search)).engines)
    }

    @Test
    fun `catalog provides over thirty distinct sites and longest matching domain wins`() {
        assertTrue(chatSourceBrands.size > 30)
        assertEquals(chatSourceBrands.size, chatSourceBrands.map { it.id }.distinct().size)
        assertEquals("googlescholar", chatSourceBrand("scholar.google.com")?.id)
        assertEquals("android", chatSourceBrand("developer.android.com")?.id)
        assertEquals("wikipedia", chatSourceBrand("en.wikipedia.org")?.id)
        assertNull(chatSourceBrand("wikipedia.org.example.net"))
        assertNull(chatSourceBrand("unknown.example.net"))
        assertFalse(chatSearchEngineBrands.isEmpty())
    }

    @Test
    fun `short engine aliases do not brand arbitrary custom names`() {
        assertNull(chatSearchEngineBrand("Example Search"))
        assertEquals("exa", chatSearchEngineBrand("mcp_exa_search")?.id)
    }

    private fun event(result: String, id: String = "event", tool: String = "web_search", arguments: String = "{}") = ToolEvent(
        eventId = id, runId = "run", sequence = 0, callId = "call-$id",
        connectionUidSnapshot = null, connectionNameSnapshot = null,
        toolName = tool, modelToolName = tool, arguments = arguments,
        result = result, resultType = "JSON", status = ToolEventStatus.COMPLETED
    )
}
