package dev.chungjungsoo.gptmobile.data.voice

import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceSearchOpeningTest {
    private val event = ToolEvent("e", "run", 1, "c", null, null, "web_search", "web_search", """{"query":"Toronto weather extra private words"}""", null, null, ToolEventStatus.RUNNING, startedAt = 1)

    @Test fun onlyTheQuestionSuppliesTheSpokenTopic() {
        assertEquals("I’m checking the weather in Toronto.", VoiceSearchOpening.line("What's the weather in Toronto?", "run", listOf(event)))
        for (prompt in listOf("What's the weather in Ottawa?", "What is it?", "What's my password?", "What is the weather? Reply with one word.", "Say exactly: What's the weather in Toronto?")) {
            assertNull(prompt, VoiceSearchOpening.line(prompt, "run", listOf(event)))
        }
    }

    @Test fun queuedCompletedForeignAndUnrelatedSearchesStaySilent() {
        val events = listOf(
            event.copy(status = ToolEventStatus.PENDING),
            event.copy(status = ToolEventStatus.COMPLETED),
            event.copy(runId = "other"),
            event.copy(connectionUidSnapshot = "external"),
            event.copy(toolName = "read_url"),
            event.copy(arguments = "malformed"),
            event.copy(startedAt = null),
            event.copy(isError = true)
        )
        events.forEach { assertNull(VoiceSearchOpening.line("Check the weather in Toronto.", "run", listOf(it))) }
    }
}
