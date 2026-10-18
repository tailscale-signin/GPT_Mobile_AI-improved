package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalPromptToolBridgeTest {
    @Test fun ordinaryCodeAndJsonStreamBeforeCompletionAcrossEverySplit() {
        listOf("```kotlin\nfun greet() = \"Hi\"", "{\"temperature\": 24}", "{\"type\":\"message\",\"content\":\"Hi\"}").forEach { text ->
            for (split in 0..text.length) {
                val output = LocalPromptToolOutput()
                val visible = output.accept(text.take(split)) + output.accept(text.drop(split))
                assertEquals(text, visible)
                assertEquals("", output.buffered())
            }
        }
    }

    @Test fun toolEnvelopeNeverLeaksAcrossChunkBoundariesOrOverflow() {
        val text = "{\"type\":\"tool_call\",\"name\":\"web_search\",\"arguments\":{}}"
        for (split in 0..text.length) {
            val output = LocalPromptToolOutput()
            assertEquals("", output.accept(text.take(split)) + output.accept(text.drop(split)))
        }
        val output = LocalPromptToolOutput()
        assertEquals("", output.accept(text.dropLast(2) + " ".repeat(70000)))
        assertEquals(true, LocalPromptToolBridge.parseResult(output.buffered(), setOf("web_search")) is PromptToolParseResult.Rejected)
    }

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

    @Test fun acceptsGemmaStringToolCallAndRejectsUnknownTool() {
        val parsed = LocalPromptToolBridge.parse(
            """{"tool_call":"web_search","arguments":{"query":"weather"}}""",
            setOf("web_search")
        )
        assertEquals("""{"query":"weather"}""", parsed?.argumentsJson)
        assertNull(
            LocalPromptToolBridge.parse(
                """{"tool_call":"delete_everything","arguments":{}}""",
                setOf("web_search")
            )
        )
    }

    @Test fun canOmitSchemasWhenRuntimeHasNativeToolSupport() {
        val prompt = LocalPromptToolBridge.instruction(listOf(tool), includeSchemas = false)
        assertEquals(true, prompt.contains("web_search"))
        assertEquals(false, prompt.contains("\"parameters\":"))
    }

    @Test fun ordinaryAnswerStreamsBeforeCompletionAndDoesNotAccumulate() {
        val output = LocalPromptToolOutput()
        assertEquals("A partial answer", output.accept("A partial answer"))
        assertEquals(" remains visible", output.accept(" remains visible"))
        assertEquals("", output.buffered())
    }

    @Test fun toolRequestsRemainHiddenAcrossEveryChunkSize() {
        val payload = "```json\n{\"tool_call\":\"web_search\",\"arguments\":{\"query\":\"weather\"}}\n```"
        for (size in 1..payload.length) {
            val output = LocalPromptToolOutput()
            assertEquals("", payload.chunked(size).joinToString("") { output.accept(it) })
            assertEquals("web_search", LocalPromptToolBridge.parse(output.buffered(), setOf("web_search"))?.name)
        }
    }

    @Test fun nativeFunctionEnvelopesRemainHiddenAndExecuteAcrossChunkBoundaries() {
        val payloads = listOf(
            """<tool_call>{"name":"web_search","arguments":{"query":"weather"}}</tool_call>""",
            """[TOOL_CALLS][{"name":"web_search","arguments":{"query":"weather"}}]""",
            """{"tool_calls":[{"type":"function","function":{"name":"web_search","arguments":"{\"query\":\"weather\"}"}}]}"""
        )
        payloads.forEach { payload ->
            for (size in 1..payload.length) {
                val output = LocalPromptToolOutput()
                assertEquals("", payload.chunked(size).joinToString("") { output.accept(it) })
                assertEquals("{\"query\":\"weather\"}", LocalPromptToolBridge.parse(output.buffered(), setOf("web_search"))?.argumentsJson)
            }
        }
    }

    @Test fun rejectsUnknownTaggedCallsAndIncompleteOrMultipleFunctionCalls() {
        listOf(
            """<tool_call>{"name":"unknown","arguments":{}}</tool_call>""",
            """<tool_call>{"name":"web_search","arguments":{}}""",
            """{"tool_calls":[{"function":{"name":"web_search","arguments":{}}},{"function":{"name":"web_search","arguments":{}}}]}""",
            """{"tool_calls":[{"function":{"name":"web_search","arguments":"not json"}}]}"""
        ).forEach { assertNull(LocalPromptToolBridge.parse(it, setOf("web_search"))) }
    }

    @Test fun absentToolsProduceNoCatalogAndNonToolTagsStreamNormally() {
        assertEquals(true, LocalPromptToolBridge.instruction(emptyList()).contains("No tools are authorized"))
        val output = LocalPromptToolOutput()
        assertEquals("", output.accept("<"))
        assertEquals("<p>Answer</p>", output.accept("p>Answer</p>"))
    }

    @Test fun parsesGemmaCallMarkupAndClassifiesUnauthorizedCalls() {
        val call = LocalPromptToolBridge.parseResult(
            "<|tool_call>call:web_search{query:latest news on the economy}",
            setOf("web_search")
        ) as PromptToolParseResult.Call
        assertEquals("web_search", call.value.name)
        assertEquals("""{"query":"latest news on the economy"}""", call.value.argumentsJson)
        assertEquals(
            PromptToolParseResult.Rejected("tools_disabled"),
            LocalPromptToolBridge.parseResult("<|tool_call>call:web_search{query:news}", emptySet())
        )
    }

    @Test fun gemmaControlMarkupStaysBufferedAcrossChunkBoundaries() {
        val payload = "<|tool_call>call:web_search{query:news}"
        for (size in 1..payload.length) {
            val output = LocalPromptToolOutput()
            assertEquals("", payload.chunked(size).joinToString("") { output.accept(it) })
            assertEquals("web_search", LocalPromptToolBridge.parse(output.buffered(), setOf("web_search"))?.name)
        }
    }
}
