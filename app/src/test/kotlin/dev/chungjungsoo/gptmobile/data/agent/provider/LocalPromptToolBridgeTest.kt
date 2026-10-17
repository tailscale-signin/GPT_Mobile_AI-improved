package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalPromptToolBridgeTest {
    private val schema = buildJsonObject { put("type", "object") }
    private val tool = AgentToolDefinition("web_search", "Search the web", schema)

    @Test fun instructionListsOnlyRegisteredToolSchemas() {
        val prompt = LocalPromptToolBridge.instruction(listOf(tool))
        assertEquals(true, prompt.contains("web_search"))
        assertEquals(true, prompt.contains("\"parameters\":{\"type\":\"object\"}"))
    }

    @Test fun parsesRegisteredToolCall() {
        val parsed = LocalPromptToolBridge.parse(
            """{"type":"tool_call","name":"web_search","arguments":{"query":"weather"}}""",
            setOf("web_search")
        )
        assertEquals("web_search", parsed?.name)
        assertEquals("""{"query":"weather"}""", parsed?.argumentsJson)
    }

    @Test fun malformedTypeFallsBackWithoutThrowing() {
        listOf("{}", "[]", "null", "42", "true").forEach { type ->
            assertNull(
                LocalPromptToolBridge.parse(
                    """{"type":$type,"name":"web_search","arguments":{}}""",
                    setOf("web_search")
                )
            )
        }
    }

    @Test fun acceptsFencedNestedToolCallButRejectsUnknownAndOrdinaryJson() {
        val parsed = LocalPromptToolBridge.parse(
            """```json
{"tool_call":{"name":"web_search","arguments":{"query":"a { b }"}}}
```""",
            setOf("web_search")
        )
        assertEquals("web_search", parsed?.name)
        assertNull(LocalPromptToolBridge.parse("""{"type":"tool_call","name":"delete_everything","arguments":{}}""", setOf("web_search")))
        assertNull(LocalPromptToolBridge.parse("""{"name":"web_search","arguments":{}}""", setOf("web_search")))
    }
}
