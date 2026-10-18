package dev.chungjungsoo.gptmobile.util

import dev.chungjungsoo.gptmobile.data.agent.CombinedEvidenceLedger
import dev.chungjungsoo.gptmobile.data.agent.CombinedQualityReport
import dev.chungjungsoo.gptmobile.data.agent.CombinedReplacementCandidate
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedReplacementReducerTest {
    @Test fun replacementWaitsForDurableHandoffAndReplacesEqualLengthTextAndCitations() = runBlocking {
        val before = "Old text"
        val after = "New text"
        val candidate = candidate(before, after)
        val handoff = ApiState.CombinedReplacement(candidate)
        var visible = ""
        var saved = emptyList<AssistantTimelineItem>()
        val result = flow {
            emit(ApiState.Success(before))
            emit(ApiState.CombinedQuality(candidate.report.copy(answerDigest = researchHash(before), revisionId = "old", correctionRequired = true)))
            emit(handoff)
            assertTrue(handoff.acknowledgement.await())
            emit(ApiState.Done)
        }.collectApiStateUpdates(
            onUpdate = { content, _, timeline ->
                visible = content
                saved = timeline
            },
            onCombinedReplacement = {
                assertEquals(before, visible)
                assertEquals("old", saved.single { item -> item.combinedQuality != null }.combinedQuality?.revisionId)
                true
            }
        )
        assertEquals(ApiStateFlowOutcome.Completed, result)
        assertEquals(after, visible)
        assertEquals(after, saved.filter { it.type == AssistantTimelineItemType.TEXT }.joinToString("") { it.content })
        assertEquals(candidate.report, saved.single { it.combinedQuality != null }.combinedQuality)
    }

    @Test fun rejectedHandoffKeepsTheInvalidatedPriorRevisionWithoutAppendingTheRepair() = runBlocking {
        val candidate = candidate("Retained draft", "Rejected replacement")
        val original = candidate.report.copy(answerDigest = candidate.previousDigest, revisionId = "old", complete = false, correctionRequired = true)
        val handoff = ApiState.CombinedReplacement(candidate)
        var visible = ""
        var report: CombinedQualityReport? = null
        flow {
            emit(ApiState.Success("Retained draft"))
            emit(ApiState.CombinedQuality(original))
            emit(handoff)
            assertFalse(handoff.acknowledgement.await())
            emit(ApiState.Done)
        }.collectApiStateUpdates(onUpdate = { content, _, timeline ->
            visible = content
            report = timeline.lastOrNull { it.combinedQuality != null }?.combinedQuality
        })
        assertEquals("Retained draft", visible)
        assertEquals(original, report)
    }

    private fun candidate(before: String, after: String): CombinedReplacementCandidate = CombinedReplacementCandidate(
        "old",
        researchHash(before),
        "new",
        after,
        CombinedQualityReport(answerDigest = researchHash(after), revisionId = "new", complete = true),
        CombinedEvidenceLedger(),
        "MATERIAL_CONTRADICTION"
    )
}
