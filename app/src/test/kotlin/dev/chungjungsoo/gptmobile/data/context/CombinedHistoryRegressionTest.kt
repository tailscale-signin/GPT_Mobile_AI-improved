package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.agent.CombinedQualityReport
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.research.researchHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedHistoryRegressionTest {
    private fun candidate(uid: String, text: String, status: String) = MessageV2(content = text, platformType = uid, currentRunId = "candidate:$uid", timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, combinedTurn = CombinedTurnHistory("root", "policy", listOf("p", "h"), status, uid))))
    private fun final(text: String, report: CombinedQualityReport) = MessageV2(content = text, platformType = "p", currentRunId = "combined-synthesis:done", combinedSources = listOf(CombinedModelResponse("h", "Helper", content = "Candidate statement", ancestryIds = setOf("original-work"))), timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, combinedQuality = report.copy(answerDigest = researchHash(text)))))

    @Test fun completedJokeDraftsDoNotBecomeASharedFinalWhenPrimaryFailed() {
        val messages = listOf(candidate("p", "", "CANDIDATE_FAILED"), candidate("h", "A complete original joke.", "CANDIDATE_COMPLETED"))
        assertTrue(messages.hasCombinedHistory())
        assertNull(messages.canonicalCombinedAssistant())
    }

    @Test fun stoppedAndMergeUnavailableMarkersNeverFallBackToIndividualDrafts() {
        for (status in listOf("CANDIDATE_CANCELED", "CANDIDATE_INTERRUPTED", "MERGE_UNAVAILABLE", "CANDIDATE_COMPLETED")) {
            val rows = listOf(candidate("h", "An undelivered candidate", status))
            assertTrue(rows.hasCombinedHistory())
            assertNull(rows.canonicalCombinedAssistant())
        }
    }

    @Test fun partialDeliveryTakesPrecedenceOverLegacyCompletedFlag() {
        val history = listOf(final("Unfinished answer", CombinedQualityReport(complete = true, deliveredScopeStatus = "PARTIAL"))).canonicalCombinedAssistant()!!
        assertEquals("Unfinished answer", history.content)
        assertEquals("PARTIAL", history.timeline.single().combinedQuality?.deliveredScopeStatus)
    }

    @Test fun failedCorrectionCannotBeRecappedAsEstablishedAndAncestryIsStable() {
        val history = listOf(final("An earlier erroneous assertion", CombinedQualityReport(complete = true, deliveredScopeStatus = "PARTIAL", correctionRequired = true))).canonicalCombinedAssistant()!!
        assertEquals("An earlier erroneous assertion", history.content)
        assertTrue(history.timeline.single().combinedQuality!!.correctionRequired)
        assertEquals(setOf("original-work"), history.combinedSources.single().ancestryIds)
    }

    @Test fun deliveryWithLimitsDoesNotClaimFactualVerification() {
        val history = listOf(final("The evidence is unavailable.", CombinedQualityReport(complete = true, deliveredScopeStatus = "COMPLETED_WITH_LIMITS", verificationStatus = "EVIDENCE_UNAVAILABLE"))).canonicalCombinedAssistant()!!
        assertEquals("The evidence is unavailable.", history.content)
        assertEquals("EVIDENCE_UNAVAILABLE", history.timeline.single().combinedQuality?.verificationStatus)
    }

    @Test fun writerUnavailableStatusAndEvidenceOnlyFallbackAreNotReusedAsAssistantAnswers() {
        val status = final("A unified answer could not be completed.", CombinedQualityReport(status = "SYNTHESIS_UNAVAILABLE"))
        assertNull(listOf(status).canonicalCombinedAssistant())
        val evidenceOnly = final("Evidence remains saved for retry.", CombinedQualityReport(status = "PARTIAL")).copy(
            timeline = listOf(
                AssistantTimelineItem(
                    AssistantTimelineItemType.NOTICE,
                    combinedQuality = CombinedQualityReport(status = "PARTIAL"),
                    combinedFallback = dev.chungjungsoo.gptmobile.data.agent.CombinedFallbackReceipt("EVIDENCE_ONLY", null, null, null, researchHash("Evidence remains saved for retry."), "snapshot", "run")
                )
            )
        )
        assertNull(listOf(evidenceOnly).canonicalCombinedAssistant())
    }

    @Test fun selectedArchivedCombinedRevisionRetainsItsDeliveryIdentityAfterRegeneration() {
        val historical = AssistantRevision("Prior merged final", createdAt = 1, runId = "combined-synthesis:old", timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, combinedQuality = CombinedQualityReport(answerDigest = researchHash("Prior merged final"), complete = true))))
        val row = candidate("p", "New candidate", "CANDIDATE_PENDING").copy(revisions = listOf(historical), activeRevisionIndex = 0)
        assertEquals("Prior merged final", listOf(row).canonicalCombinedAssistant()!!.content)
    }

    @Test fun archivedAnswerUsesItsOwnSourceAncestryRatherThanTheNewDrafts() {
        val previous = AssistantRevision("Prior merged final", createdAt = 1, runId = "combined-synthesis:old", timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, combinedQuality = CombinedQualityReport(answerDigest = researchHash("Prior merged final"), complete = true))), combinedSources = listOf(CombinedModelResponse("old", "Old", content = "Old source", ancestryIds = setOf("old-evidence"))))
        val current = final("Current merged final", CombinedQualityReport(complete = true)).copy(revisions = listOf(previous), activeRevisionIndex = 0)
        val history = listOf(current).canonicalCombinedAssistant()!!
        assertEquals("Prior merged final", history.content)
        assertEquals(setOf("old-evidence"), history.combinedSources.single().ancestryIds)
    }

    @Test fun staleEvidenceReportCannotBeCarriedIntoChangedText() {
        val row = final("Original", CombinedQualityReport(complete = true)).copy(content = "Changed")
        assertThrows(IllegalArgumentException::class.java) { listOf(row).canonicalCombinedAssistant() }
    }

    @Test fun ordinaryMultiModelHistoryKeepsTheExistingSelectionPath() {
        assertNull(listOf(MessageV2(content = "Ordinary response", platformType = "p")).canonicalCombinedAssistant())
    }
}
