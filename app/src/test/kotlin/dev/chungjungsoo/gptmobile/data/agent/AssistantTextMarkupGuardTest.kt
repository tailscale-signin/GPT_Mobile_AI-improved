package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantTextMarkupGuardTest {
    @Test fun splitRawToolMarkupIsWithheldBeforeItReachesTheConsumer() = runBlocking {
        val events = flowOf(
            ProviderEvent.TextDelta("A useful sentence. <tool_"),
            ProviderEvent.TextDelta("call name=browser.search>{\"query\":\"private\"}</tool_call> leaked payload"),
            ProviderEvent.Completed
        ).withAssistantTextMarkupGuard().toList()

        assertEquals("A useful sentence. ", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertTrue(events.filterIsInstance<ProviderEvent.Failed>().single().message.contains("unparsed tool-call markup"))
        assertFalse(events.filterIsInstance<ProviderEvent.TextDelta>().any { "private" in it.text || "leaked" in it.text })
    }

    @Test fun ordinaryTextAndSeparateStructuredToolCallsPassThrough() = runBlocking {
        val tool = ProviderEvent.ToolCall("call-1", "search", kotlinx.serialization.json.buildJsonObject {})
        val events = flowOf(ProviderEvent.TextDelta("The word invoke is ordinary prose."), tool, ProviderEvent.Completed)
            .withAssistantTextMarkupGuard().toList()

        assertEquals("The word invoke is ordinary prose.", events.filterIsInstance<ProviderEvent.TextDelta>().single().text)
        assertTrue(events.any { it === tool })
        assertTrue(events.any { it == ProviderEvent.Completed })
        assertTrue(events.filterIsInstance<ProviderEvent.Failed>().isEmpty())
    }
}
