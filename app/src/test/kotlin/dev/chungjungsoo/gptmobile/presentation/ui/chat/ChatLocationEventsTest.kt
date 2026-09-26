package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatLocationEventsTest {
    @Test
    fun `combined answer keeps the lead and other candidate location results after synthesis`() {
        val lead = MessageV2(
            content = "Combined answer",
            platformType = "lead",
            currentRunId = "combined-final",
            revisions = listOf(AssistantRevision("Lead candidate", createdAt = 1, runId = "lead-run")),
            combinedSources = listOf(
                CombinedModelResponse("lead", "Lead", content = "Lead candidate"),
                CombinedModelResponse("other", "Other", content = "Other candidate")
            )
        )
        val other = MessageV2(content = "Other candidate", platformType = "other", currentRunId = "other-run")
        val events = mapOf(
            "lead-run" to listOf(location("lead-run", 20)),
            "other-run" to listOf(location("other-run", 10)),
            "unrelated-turn" to listOf(location("unrelated-turn", 30))
        )

        val result = locationEventsForResponse(lead, listOf(lead, other), true, emptySet(), events)

        assertEquals(listOf("other-run", "lead-run"), result.map { it.runId })
    }

    @Test
    fun `later retries cannot replace the location used by an existing combined answer`() {
        val lead = MessageV2(
            content = "Combined answer",
            platformType = "lead",
            currentRunId = "combined-final",
            combinedSources = listOf(CombinedModelResponse("other", "Other", content = "Original candidate"))
        )
        val other = MessageV2(
            content = "New candidate",
            platformType = "other",
            currentRunId = "retry",
            revisions = listOf(AssistantRevision("Original candidate", createdAt = 1, runId = "original"))
        )
        val events = mapOf("original" to listOf(location("original", 10)), "retry" to listOf(location("retry", 20)))

        val result = locationEventsForResponse(lead, listOf(lead, other), true, setOf("lead", "other"), events)

        assertEquals(listOf("original"), result.map { it.runId })
    }

    @Test
    fun `separate replies use only the selected revision location`() {
        val selected = MessageV2(
            content = "Latest",
            platformType = "lead",
            currentRunId = "latest",
            activeRevisionIndex = 0,
            revisions = listOf(AssistantRevision("Older", createdAt = 1, runId = "older"))
        )
        val events = mapOf("older" to listOf(location("older", 10)), "latest" to listOf(location("latest", 20)))

        val result = locationEventsForResponse(selected, listOf(selected), false, setOf("lead"), events)

        assertEquals(listOf("older"), result.map { it.runId })
    }

    @Test
    fun `pending synthesis only shows results from participating profiles`() {
        val selected = MessageV2(content = "Lead", platformType = "lead", currentRunId = "lead-run")
        val paused = MessageV2(content = "Paused", platformType = "paused", currentRunId = "paused-run")
        val events = mapOf("lead-run" to listOf(location("lead-run", 10)), "paused-run" to listOf(location("paused-run", 20)))

        val result = locationEventsForResponse(selected, listOf(selected, paused), true, setOf("lead"), events)

        assertEquals(listOf("lead-run"), result.map { it.runId })
    }

    private fun location(runId: String, time: Long) = ToolEvent(
        eventId = "$runId-location", runId = runId, sequence = 0, callId = "$runId-call",
        connectionUidSnapshot = null, connectionNameSnapshot = null,
        toolName = "device_location", modelToolName = "device_location", arguments = "{}",
        result = "{\"latitude\":43.65,\"longitude\":-79.38}", resultType = "JSON",
        status = ToolEventStatus.COMPLETED, startedAt = time, completedAt = time
    )
}
