package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.ACTIVE_REVISION_LATEST
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class CombinedRetrySelectionTest {
    private val old = AssistantRevision(content = "Retained useful answer", createdAt = 1, runId = "combined-synthesis:old")
    private val current = MessageV2(platformType = "p", content = "", currentRunId = "combined-synthesis:new", activeRevisionIndex = 0, revisions = listOf(old))
    private val snapshot = CombinedInputSnapshot(userMessage = MessageV2(platformType = null, content = "Request"), history = emptyList(), replies = emptyList(), sources = emptyList(), prompt = "frozen", profileUid = "p", model = "m", systemPrompt = "", configKey = "key", resumeFromRunId = old.runId, deliveryProtocol = "PASSAGES_V2")
    private val run = AgentRun("combined-synthesis:new", 1, 2, 3, "p", "CUSTOM", "m", synthesisSnapshot = Json.encodeToString(CombinedInputSnapshot.serializer(), snapshot))

    private fun candidate(complete: Boolean = true, issues: List<String> = emptyList()) = current.copy(
        content = "Validated replacement",
        activeRevisionIndex = ACTIVE_REVISION_LATEST,
        timeline = listOf(
            AssistantTimelineItem(
                AssistantTimelineItemType.NOTICE,
                combinedQuality = CombinedQualityReport(
                    answerDigest = researchHash("Validated replacement"),
                    complete = complete,
                    deliveredScopeStatus = if (complete) "COMPLETED" else "PARTIAL",
                    issues = issues
                )
            )
        )
    )

    @Test fun cancellationFailureAndIncompleteReplacementKeepVisibleAnswer() {
        for (status in listOf(null, "CANCELED", "INTERRUPTED", "FAILED")) {
            assertEquals(old.content, preserveCombinedRetrySelection(current, candidate(), run, status).effectiveContent())
        }
        assertEquals(old.content, preserveCombinedRetrySelection(current, candidate(false), run, "COMPLETED").effectiveContent())
        assertEquals(old.content, preserveCombinedRetrySelection(current, candidate(issues = listOf("INVALID_DELIVERY_RECORD")), run, "COMPLETED").effectiveContent())
    }

    @Test fun validatedTerminalReplacementSelectsNewRevision() {
        val accepted = preserveCombinedRetrySelection(current, candidate(), run, "COMPLETED")
        assertEquals(ACTIVE_REVISION_LATEST, accepted.activeRevisionIndex)
        assertEquals("Validated replacement", accepted.effectiveContent())
        assertEquals(old, accepted.revisions.single())
    }

    @Test fun staleReportCannotSelectChangedCandidate() {
        assertEquals(old.content, preserveCombinedRetrySelection(current, candidate().copy(content = "Changed after validation"), run, "COMPLETED").effectiveContent())
    }
}
