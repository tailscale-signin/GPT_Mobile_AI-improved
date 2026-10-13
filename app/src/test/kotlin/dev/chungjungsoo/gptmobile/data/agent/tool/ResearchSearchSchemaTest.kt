package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import java.time.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchSearchSchemaTest {
    @Test fun newsSearchUsesTheRequiredProviderQueryField() {
        val schema = Json.parseToJsonElement("""{"type":"object","properties":{"q":{"type":"string"},"query":{"type":"string"},"count":{"type":"integer"}},"required":["q"],"additionalProperties":false}""") as JsonObject
        val adapter = requireNotNull(WebSearchEngineAdapter.forTool("news_search", AgentToolDefinition("news_search", "Search current web news", schema)))
        val arguments = adapter.arguments(Json.parseToJsonElement("""{"query":"public research evidence","maxResults":5}""") as JsonObject, Clock.systemUTC())
        assertTrue("q" in arguments)
        assertFalse("query" in arguments)
        assertFalse("result_filter" in arguments)
    }

    @Test fun unknownRequiredArgumentsDoNotGetFabricated() {
        val schema = Json.parseToJsonElement("""{"type":"object","properties":{"q":{"type":"string"},"account":{"type":"string"}},"required":["q","account"]}""") as JsonObject
        assertNull(WebSearchEngineAdapter.forTool("news_search", AgentToolDefinition("news_search", "Search web news", schema)))
    }
}
