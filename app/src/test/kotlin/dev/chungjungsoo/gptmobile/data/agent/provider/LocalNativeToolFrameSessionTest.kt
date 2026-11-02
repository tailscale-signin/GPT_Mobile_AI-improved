package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.withNormalizedAssistantText
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNativeToolFrameSessionTest {
    @Test fun parserDiagnosticsDistinguishDisabledUnknownAndIncompleteWithoutPayloads() = runTest {
        val cases = listOf(
            Triple(emptyList(), frame, "TOOL_PROTOCOL_TOOLS_DISABLED"),
            Triple(listOf(tool), frame.replace("github_context", "private_unknown_name"), "TOOL_PROTOCOL_UNKNOWN_TOOL"),
            Triple(listOf(tool), frame.removeSuffix("</tool_call>"), "TOOL_PROTOCOL_INCOMPLETE_FRAME")
        )
        for ((tools, input, expected) in cases) {
            val failure = session(ProviderEvent.TextDelta(input), ProviderEvent.Completed).streamRound(tools, emptyList()).toList().filterIsInstance<ProviderEvent.Failed>().single()
            assertEquals(expected, failure.errorCode)
            assertFalse(failure.failureDetails.toString().contains("private_unknown_name"))
            assertFalse(failure.failureDetails.toString().contains("arguments"))
            assertEquals("false", failure.failureDetails?.get("dispatched")?.jsonPrimitive?.content)
        }
    }

    @Test fun baseOperationResolvesOnlyOneAdvertisedConnection() = runTest {
        val schema = Json.parseToJsonElement("""{"properties":{"action":{"type":"string"}}}""").jsonObject
        val first = AgentToolDefinition("github_context__one", "", schema)
        val second = first.copy(name = "github_context__two")
        val forms = listOf(frame, "<tool_call><function=github_context><parameter=action>status</parameter></function></tool_call>")
        for (text in forms) {
            val unique = session(ProviderEvent.TextDelta(text), ProviderEvent.Completed).streamRound(listOf(first), emptyList()).toList()
            assertEquals(first.name, unique.filterIsInstance<ProviderEvent.ToolCall>().single().name)
            val ambiguous = session(ProviderEvent.TextDelta(text), ProviderEvent.Completed).streamRound(listOf(first, second), emptyList()).toList()
            assertTrue(ambiguous.any { it is ProviderEvent.Failed })
            assertFalse(ambiguous.any { it is ProviderEvent.ToolCall })
        }
    }

    private val tool = AgentToolDefinition("github_context", "Repository status", JsonObject(emptyMap()))
    private val frame = """<tool_call>{"name":"github_context","arguments":{"action":"status"}}</tool_call>"""

    private fun session(vararg events: ProviderEvent): AgentProviderSession = LocalNativeToolFrameSession(object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(*events)
    }).withNormalizedAssistantText()

    @Test fun splitLocalFrameBecomesOneAuthorizedCallBeforeTheProseGuard() = runTest {
        val events = session(*frame.chunked(3).map { ProviderEvent.TextDelta(it) }.toTypedArray(), ProviderEvent.Completed)
            .streamRound(listOf(tool), emptyList()).toList()
        assertEquals("github_context", events.filterIsInstance<ProviderEvent.ToolCall>().single().name)
        assertFalse(events.any { it is ProviderEvent.TextDelta || it is ProviderEvent.Failed })
        assertEquals(ProviderEvent.Completed, events.last())
    }

    @Test fun disabledUnknownMalformedAndMixedFramesNeverDispatch() = runTest {
        val cases = listOf(
            emptyList<AgentToolDefinition>() to arrayOf(ProviderEvent.TextDelta(frame), ProviderEvent.Completed),
            listOf(tool) to arrayOf(ProviderEvent.TextDelta(frame.replace("github_context", "github_write")), ProviderEvent.Completed),
            listOf(tool) to arrayOf(ProviderEvent.TextDelta("<tool_call>{}"), ProviderEvent.Completed),
            listOf(tool) to arrayOf(ProviderEvent.ToolCall("native", tool.name, JsonObject(emptyMap())), ProviderEvent.TextDelta(frame), ProviderEvent.Completed)
        )
        for ((tools, input) in cases) {
            val events = session(*input).streamRound(tools, emptyList()).toList()
            assertTrue(events.any { it is ProviderEvent.Failed })
            assertFalse(events.any { it is ProviderEvent.ToolCall || it is ProviderEvent.Completed })
        }
    }

    @Test fun transportFailureCannotExecuteACompleteBufferedCall() = runTest {
        val events = session(ProviderEvent.TextDelta(frame), ProviderEvent.Failed("Connection reset"))
            .streamRound(listOf(tool), emptyList()).toList()
        assertTrue(events.any { it is ProviderEvent.Failed })
        assertFalse(events.any { it is ProviderEvent.ToolCall || it is ProviderEvent.Completed })
    }

    @Test fun qwenFunctionFramesPreserveStringsAndTypedArgumentsAcrossEveryChunk() = runTest {
        val definition = tool.copy(inputSchema = Json.parseToJsonElement("""{"properties":{"action":{"type":"string"},"page":{"type":"integer"},"enabled":{"type":"boolean"},"files":{"type":"array"}}}""").jsonObject)
        val xml = "<tool_call>\n<function=github_context>\n<parameter=action>\nstatus\n</parameter>\n<parameter=page>\n2\n</parameter>\n<parameter=enabled>\nTrue\n</parameter>\n<parameter=files>\n[\"a.kt\"]\n</parameter>\n</function>\n</tool_call>"
        val events = session(*xml.map { ProviderEvent.TextDelta(it.toString()) }.toTypedArray(), ProviderEvent.Completed)
            .streamRound(listOf(definition), emptyList()).toList()
        val call = events.filterIsInstance<ProviderEvent.ToolCall>().single()
        assertEquals(Json.parseToJsonElement("""{"action":"status","page":2,"enabled":true,"files":["a.kt"]}"""), call.arguments)
        assertFalse(events.any { it is ProviderEvent.Failed || it is ProviderEvent.TextDelta })
        val indented = xml.substringBefore("<parameter=page>").replace("status", "  exact source\n    indentation") + "</function></tool_call>"
        val preserved = session(ProviderEvent.TextDelta(indented), ProviderEvent.Completed).streamRound(listOf(definition), emptyList()).toList()
        assertEquals("  exact source\n    indentation", preserved.filterIsInstance<ProviderEvent.ToolCall>().single().arguments.getValue("action").jsonPrimitive.content)
    }

    @Test fun qwenDuplicatesNestedCallsTruncationUnknownParametersAndProseNeverDispatch() = runTest {
        val definition = tool.copy(inputSchema = Json.parseToJsonElement("""{"properties":{"action":{"type":"string"}}}""").jsonObject)
        val parameter = "<parameter=action>status</parameter>"
        val xml = "<tool_call><function=github_context>$parameter</function></tool_call>"
        for (invalid in listOf(xml.replace(parameter, parameter + parameter), xml + xml, xml.dropLast(1), xml.replace("action>", "unknown>"), "Example: $xml", "```xml\n$xml\n```")) {
            val events = session(ProviderEvent.TextDelta(invalid), ProviderEvent.Completed).streamRound(listOf(definition), emptyList()).toList()
            assertFalse(events.any { it is ProviderEvent.ToolCall })
        }
        val disabled = session(ProviderEvent.TextDelta(xml), ProviderEvent.Completed).streamRound(emptyList(), emptyList()).toList()
        assertTrue(disabled.any { it is ProviderEvent.Failed })
        assertFalse(disabled.any { it is ProviderEvent.ToolCall })
    }

    @Test fun ordinaryProseAndNativeCallsRetainTheirOriginalMeaning() = runTest {
        val text = session(ProviderEvent.TextDelta("# Repository status\n\nReady."), ProviderEvent.Completed)
            .streamRound(listOf(tool), emptyList()).toList()
        assertEquals("# Repository status\n\nReady.", text.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        val call = ProviderEvent.ToolCall("native", tool.name, JsonObject(emptyMap()))
        assertEquals(listOf(call, ProviderEvent.Completed), session(call, ProviderEvent.Completed).streamRound(listOf(tool), emptyList()).toList())
    }
}
