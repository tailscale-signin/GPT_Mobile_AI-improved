package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedSynthesisInputsTest {
    private fun message(uid: String, content: String) = MessageV2(content = content, platformType = uid, currentRunId = uid)
    private fun run(id: String, status: String = AgentRunStatus.COMPLETED) = AgentRun(id, 1, 1, 2, id, "test", "test", status = status)
    private fun inputs(messages: List<MessageV2>, runs: Map<String, AgentRun> = messages.mapNotNull { it.currentRunId }.associateWith { run(it) }) =
        combinedSynthesisInputs(messages, runs, emptyMap(), emptyMap())

    @Test fun helperContributionsUseLatestResponsesEvenWhenViewingAnOldRevision() {
        val lead = message("lead", "Primary finding.")
        val helper = message("helper", "Additional finding.").copy(revisions = listOf(AssistantRevision("Old finding.", createdAt = 1)), activeRevisionIndex = 0)
        val sources = requireNotNull(inputs(listOf(lead, helper)))
        assertEquals(listOf("Primary finding.", "Additional finding."), sources.map { it.content })
        val merged = mergeCombinedResponses(sources)
        assertTrue(merged.contains("Primary finding."))
        assertTrue(merged.contains("Additional finding."))
    }

    @Test fun synthesisWaitsForEveryActiveProfileAndRunRecord() {
        val messages = listOf(message("lead", "Primary"), message("helper", "Partial"))
        assertNull(inputs(messages, mapOf("lead" to run("lead"), "helper" to run("helper", AgentRunStatus.RUNNING))))
        assertNull(inputs(messages, mapOf("lead" to run("lead"))))
    }

    @Test fun completedSynthesisDoesNotRepeatUntilAHelperChanges() {
        val sources = listOf(CombinedModelResponse("lead", "AI", content = "Primary"), CombinedModelResponse("helper", "AI", content = "Old helper"))
        val lead = message("lead", "Combined").copy(currentRunId = "combined-synthesis:1", combinedSources = sources)
        assertNull(inputs(listOf(lead, message("helper", "Old helper"))))
        assertEquals(listOf("Primary", "New helper"), inputs(listOf(lead, message("helper", "New helper")))?.map { it.content })
    }

    @Test fun failedOrCanceledSynthesisNeverAutomaticallyLoopsOnUnchangedSources() {
        val sources = listOf(CombinedModelResponse("lead", "AI", content = "Primary"), CombinedModelResponse("helper", "AI", content = "Helper"))
        val lead = message("lead", "").copy(currentRunId = "combined-synthesis:1", combinedSources = sources)
        listOf(AgentRunStatus.FAILED, AgentRunStatus.CANCELED, AgentRunStatus.INTERRUPTED).forEach { status ->
            val runs = mapOf("combined-synthesis:1" to run("combined-synthesis:1", status), "helper" to run("helper"))
            assertNull(inputs(listOf(lead, message("helper", "Helper")), runs))
            assertEquals(listOf("Primary", "Changed helper"), inputs(listOf(lead, message("helper", "Changed helper")), runs)?.map { it.content })
        }
    }
}
