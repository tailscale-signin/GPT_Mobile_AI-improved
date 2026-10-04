package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class WebSearchResultsTest {
    @Test
    fun `failed engine detail is not accepted as research evidence`() {
        val result = Json.parseToJsonElement("""{"engines":[{"status":"unavailable","detail":"URL: https://provider.example/help"}],"results":[]}""")
        assertEquals(emptyList<kotlinx.serialization.json.JsonObject>(), extractSearchSources(result))
        assertEquals("", pageText(result))
    }

    @Test
    fun `MCP sources envelope is usable research evidence`() {
        val result = Json.parseToJsonElement("""{"structuredContent":{"sources":[{"url":"https://example.org/article","title":"Article","snippet":"Exact evidence"}]}}""")
        val sources = extractSearchSources(result)
        assertEquals(1, sources.size)
        assertEquals("Exact evidence", sources.single().getValue("snippet").jsonPrimitive.content)
    }

    @Test
    fun `aggregate engine prose is parsed without recovering provider metadata URLs`() {
        val result = Json.parseToJsonElement("""{"query":"test","engines":[{"status":"completed","detail":"Title: Article\nURL: https://example.org/article\nDescription: Verified snippet"}],"results":[]}""")
        assertEquals("https://example.org/article", extractSearchSources(result).single().getValue("url").jsonPrimitive.content)
    }
}
