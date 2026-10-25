package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedManifestReference
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.research.researchHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class CombinedRetainedRecoveryTest {
    // Exact public-history answer and final report fields from the recovered Portugal
    // example. User/profile/run identifiers and unrelated export records are omitted.
    private val answer = "Portugal’s history runs from ancient Lusitanian tribes through Roman rule, Visigothic kingdoms, and Moorish occupation before Afonso Henriques founded the kingdom in 1139. The 15th and 16th centuries brought the Age of Discoveries, when navigators such as Vasco da Gama reached India and Brazil was claimed in 1500, making Portugal a global maritime power. After the 1580 succession crisis placed it under Spanish Habsburg rule until 1640, the House of Braganza restored independence. The 1755 Lisbon earthquake prompted Pombaline reforms. In the 19th century, Brazil gained independence and liberal reforms reshaped the monarchy. The 1910 revolution ended the monarchy; a 1926 coup led to Salazar’s Estado Novo until the 1974 Carnation Revolution restored democracy and decolonization followed. Portugal joined the European Economic Community in 1986 and adopted the euro in 1999. Today it is one of Europe’s oldest nation-states, a democratic EU member known for tourism, wine, and renewable energy, with cultural ties to former territories."
    private val recorded = CombinedRecoveryFailure.fromCode("COMBINED_PARTIAL")
    private val report = CombinedQualityReport(
        version = 5,
        answerDigest = "f5ab639f1e20d5e485218eb2f1eedfcae4af3a14ee1bcd2b8ab388c66fa95ca0",
        complete = false,
        status = "CORRECTION_REQUIRED",
        wordCount = 156,
        correctionRequired = true,
        finalAssessment = true,
        lengthCompliance = "UNMET",
        topicCoverage = "UNRESOLVED",
        protocolComplete = true
    )

    private fun message(quality: CombinedQualityReport = report) = MessageV2(
        id = 3,
        chatId = 1,
        content = answer,
        platformType = "history",
        currentRunId = "combined-synthesis:retained",
        timeline = listOf(
            AssistantTimelineItem(AssistantTimelineItemType.NOTICE, combinedQuality = quality),
            AssistantTimelineItem(AssistantTimelineItemType.NOTICE, combinedFailure = recorded)
        )
    )

    @Test fun originalCompletedPortugalReportRequiresReviewWithoutRewritingItsReceipt() {
        val saved = message()
        assertEquals(report.answerDigest, researchHash(answer))
        val effective = requireNotNull(CombinedRecoveryFailure.retained(saved))
        assertEquals(CombinedRecoveryCategory.REQUIREMENTS_UNMET, effective.category)
        assertEquals("REVIEW_COMPLETED_ANSWER", effective.decision)
        assertEquals("COMPLETE_NEEDS_CORRECTION", effective.deliveryStatus)
        assertFalse(effective.rotateWriter)
        assertSame(recorded, saved.timeline.last().combinedFailure)
        assertEquals(CombinedRecoveryCategory.INCOMPLETE_RECORD, recorded.category)
    }

    @Test fun staleOrIncompleteReportsDoNotReclassifyTransportFailure() {
        for (quality in listOf(report.copy(answerDigest = researchHash("Another revision")), report.copy(answerDigest = ""), report.copy(protocolComplete = false), report.copy(finalAssessment = false))) {
            assertSame(recorded, CombinedRecoveryFailure.retained(message(quality)))
        }
        assertSame(recorded, CombinedRecoveryFailure.retained(message().copy(content = answer + " changed")))
    }

    @Test fun archivedSelectionUsesItsOwnReportAndAnswer() {
        val archived = message()
        val latest = message(report.copy(protocolComplete = false)).copy(
            content = "New partial answer",
            currentRunId = "combined-synthesis:new",
            revisions = listOf(AssistantRevision(archived.content, createdAt = 1, runId = archived.currentRunId, timeline = archived.timeline)),
            activeRevisionIndex = 0
        )
        assertEquals(CombinedRecoveryCategory.REQUIREMENTS_UNMET, CombinedRecoveryFailure.retained(latest)?.category)
        assertSame(recorded, CombinedRecoveryFailure.retained(latest.copy(activeRevisionIndex = -1)))
    }

    @Test fun compactManifestMustMatchTheSelectedOwnerAndAssessmentRevision() {
        val quality = report.copy(revisionId = "revision")
        val reference = CombinedManifestReference("document", "combined-synthesis:retained", "revision", answer.length, 1, report.answerDigest)
        val compact = message(quality).copy(content = "", combinedManifest = reference)
        assertEquals(CombinedRecoveryCategory.REQUIREMENTS_UNMET, CombinedRecoveryFailure.retained(compact)?.category)
        assertSame(recorded, CombinedRecoveryFailure.retained(compact.copy(combinedManifest = reference.copy(runId = "other"))))
        assertSame(recorded, CombinedRecoveryFailure.retained(compact.copy(combinedManifest = reference.copy(revisionId = "other"))))
        assertSame(recorded, CombinedRecoveryFailure.retained(compact.copy(combinedManifest = reference.copy(bodyDigest = researchHash("other")))))
        assertSame(recorded, CombinedRecoveryFailure.retained(compact.copy(content = "changed", combinedManifest = reference.copy(materialized = true))))
    }

    @Test fun absentTypedLegacyFailureCanUseItsOriginalTerminalCode() {
        val legacy = message().copy(timeline = message().timeline.take(1))
        assertEquals(CombinedRecoveryCategory.REQUIREMENTS_UNMET, CombinedRecoveryFailure.retained(legacy, "COMBINED_PARTIAL: required checks")?.category)
        val denied = CombinedRecoveryFailure.fromCode("AUTHENTICATION_REJECTED")
        assertSame(denied, CombinedRecoveryFailure.retained(message().copy(timeline = message().timeline.take(1) + AssistantTimelineItem(AssistantTimelineItemType.NOTICE, combinedFailure = denied))))
    }
}
