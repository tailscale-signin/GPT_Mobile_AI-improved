package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.withNormalizedAssistantText
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNativeToolFrameSessionTest {
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

    @Test fun ordinaryProseAndNativeCallsRetainTheirOriginalMeaning() = runTest {
        val text = session(ProviderEvent.TextDelta("# Repository status\n\nReady."), ProviderEvent.Completed)
            .streamRound(listOf(tool), emptyList()).toList()
        assertEquals("# Repository status\n\nReady.", text.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        val call = ProviderEvent.ToolCall("native", tool.name, JsonObject(emptyMap()))
        assertEquals(listOf(call, ProviderEvent.Completed), session(call, ProviderEvent.Completed).streamRound(listOf(tool), emptyList()).toList())
    }
}
