package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.research.researchHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedBundleValidationTest {
    private val text = "The northern garden contains twelve apple trees."
    private val revision = "revision"
    private val span = CombinedCoverageLink(0, text.length, researchHash(text))
    private val source = CombinedModelResponse("source", "Source", content = text, claimExtractionVersion = 4)

    private fun report(): CombinedQualityReport {
        val mapping = CombinedAnswerMappings.get(text, listOf(source))
        val assessment = CombinedFinalCheck.check(text, listOf(source), CombinedEvidenceLedger(), CombinedAnswerPolicy.from("Describe the garden"), revisionId = revision)
        return CombinedQualityReport(
            version = 6,
            answerDigest = researchHash(text),
            revisionId = revision,
            wordCount = visibleWordCount(text),
            finalAssessment = true,
            assessmentStage = "FINAL_DISPLAY",
            writerAnswerDigest = researchHash(text),
            writerAssessment = CombinedAssessmentSnapshot(researchHash(text), revision, visibleWordCount(text)),
            writerCharacters = text.length,
            displayedCharacters = text.length,
            claimMapping = mapping,
            fullyLinkedClaims = 1,
            passages = CombinedAnswerMappings.passages(text, mapping),
            decisions = assessment.decisions,
            verificationReceipts = assessment.receipts,
            sourceDispositions = CombinedSourcePreservation.account(text, listOf(source)).dispositions
        )
    }

    @Test fun finalBundleValidatesEveryTextBoundAssessmentAndMetric() {
        CombinedAnswerPublication.validate(text, report())
    }

    @Test fun staleReceiptOrDecisionCannotBePublishedWithANewBodyDigest() {
        val report = report()
        assertTrue(report.verificationReceipts.isNotEmpty())
        val staleReceipt = report.copy(verificationReceipts = report.verificationReceipts.map { it.copy(revisionId = "old-revision") })
        assertEquals("COMBINED_FINAL_EVIDENCE_REVISION_MISMATCH", runCatching { CombinedAnswerPublication.validate(text, staleReceipt) }.exceptionOrNull()?.message)
        val staleDecision = report.copy(decisions = listOf(CombinedClaimReview("C1.1", "INCLUDED", "old link", listOf(span.copy(textDigest = "old-body")))))
        assertEquals("COMBINED_FINAL_DECISION_RANGE_MISMATCH", runCatching { CombinedAnswerPublication.validate(text, staleDecision) }.exceptionOrNull()?.message)
    }

    @Test fun hiddenClaimRangesAreValidatedEvenWhenRenderedLinksStillMatch() {
        val report = report()
        val mapping = report.claimMapping!!
        val corrupt = mapping.copy(claims = mapping.claims.map { it.copy(links = listOf(span.copy(endUtf16 = text.length + 1))) })
        assertTrue(corrupt.links.all { it.matches(text) })
        assertFalse(corrupt.matches(text))
        assertEquals("COMBINED_FINAL_MAPPING_DIGEST_MISMATCH", runCatching { CombinedAnswerPublication.validate(text, report.copy(claimMapping = corrupt)) }.exceptionOrNull()?.message)
    }

    @Test fun writerAndDisplayedMetricsCannotSilentlyReferToDifferentStages() {
        val report = report()
        val badCounts = report.copy(locallyAddedCharacters = 20)
        assertEquals("COMBINED_FINAL_ASSEMBLY_METRICS_MISMATCH", runCatching { CombinedAnswerPublication.validate(text, badCounts) }.exceptionOrNull()?.message)
        val staleWriter = report.copy(writerAssessment = report.writerAssessment!!.copy(answerDigest = "previous-draft"))
        assertEquals("COMBINED_WRITER_ASSESSMENT_MISMATCH", runCatching { CombinedAnswerPublication.validate(text, staleWriter) }.exceptionOrNull()?.message)
        assertEquals("COMBINED_FINAL_SOURCE_METRICS_MISMATCH", runCatching { CombinedAnswerPublication.validate(text, report.copy(fullyLinkedClaims = 0)) }.exceptionOrNull()?.message)
    }
}
