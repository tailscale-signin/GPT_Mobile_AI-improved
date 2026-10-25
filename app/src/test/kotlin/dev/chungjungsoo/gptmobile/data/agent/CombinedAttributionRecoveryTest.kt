package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.research.researchHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedAttributionRecoveryTest {
    private val first = "The initial source describes how saved results preserve their original attribution."
    private val second = "The additional source explains that completed remote jobs do not need another generation."
    private val source = CombinedModelResponse("valid", "Valid", content = first + "\n\n" + second, claimExtractionVersion = 4)

    @Test fun losingAnEarlierSourceDoesNotRenumberTheRemainingContributorClaims() {
        val corrupt = CombinedModelResponse("corrupt", "Corrupt", content = "The stored body was changed after its source was captured.", sourceDigest = researchHash("Original body"), claimExtractionVersion = 4)
        val report = CombinedAttributionRecovery.reconstruct(source.content, listOf(corrupt, source), "revision")
        val mapping = requireNotNull(report.claimMapping)
        assertTrue(mapping.links.isNotEmpty())
        assertEquals(setOf("valid"), report.includedProfileUids)
        assertTrue(mapping.claims.flatMap { it.aliases }.all { it.startsWith("C2.") })
        assertTrue(mapping.claims.flatMap { it.aliases }.any { it == "C2.1" })
    }

    @Test fun provisionalPrefixAttributionIsRebuiltForTheWholeSavedBodyWithoutInventingCompletion() {
        val previous = CombinedQualityReport(answerDigest = researchHash(source.content), revisionId = "revision", passages = listOf(CombinedPassageRecord(0, first.length, researchHash(first), setOf("valid"))))
        assertTrue(CombinedAttributionRecovery.needed(previous))
        val report = CombinedAttributionRecovery.reconstruct(source.content, listOf(source), "revision", previous)
        assertEquals(CombinedAttributionRecovery.RECOVERED, report.status)
        assertFalse(report.complete)
        assertFalse(report.finalAssessment)
        assertTrue(report.claimMapping!!.links.any { it.start > first.length })
    }

    @Test fun legacyCompletionDoesNotCertifyStaleChecksAndOnlyExactRevisionReceiptsSurvive() {
        val span = CombinedCoverageLink(0, first.length, researchHash(first))
        val receipt = CombinedVerificationReceipt("receipt", "C1.1", researchHash(source.content), "revision", span, emptySet(), emptyMap(), "UNVERIFIED")
        val previous = CombinedQualityReport(
            answerDigest = researchHash(source.content),
            revisionId = "revision",
            status = "COMPLETED",
            complete = true,
            finalAssessment = true,
            factualAccuracyVerified = true,
            semanticCoverageVerified = true,
            verificationReceipts = listOf(receipt, receipt.copy(id = "stale", answerDigest = researchHash(first)))
        )
        val report = CombinedAttributionRecovery.reconstruct(source.content, listOf(source), "revision", previous)
        assertTrue(report.complete)
        assertFalse(report.finalAssessment)
        assertFalse(report.factualAccuracyVerified)
        assertFalse(report.semanticCoverageVerified)
        assertEquals("LEGACY_UNCONFIRMED", report.assessmentStage)
        assertEquals(listOf(receipt), report.verificationReceipts)
        assertTrue("LEGACY_FINAL_ASSESSMENT_UNCONFIRMED" in report.issues)
        assertTrue(CombinedAttributionRecovery.reconstruct(source.content, listOf(source), "new-revision", previous).verificationReceipts.isEmpty())
    }
}
