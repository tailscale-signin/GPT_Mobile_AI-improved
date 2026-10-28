package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CombinedQualityDashboardTest {
    private fun report(evaluated: Boolean = true) = CombinedQualityReport(
        answerDigest = "answer",
        finalAssessment = true,
        wordCount = 50,
        deliveredScopeStatus = "PARTIAL",
        includedProfileUids = setOf("a", "b"),
        claimMapping = CombinedAnswerMapping(
            answerDigest = "answer",
            corpusDigest = "corpus",
            evaluated = evaluated,
            claims = listOf(
                CombinedMappedClaim("1", "one", emptySet(), setOf("a", "b"), if (evaluated) "PARAPHRASE" else "NOT_EVALUATED"),
                CombinedMappedClaim("2", "two", emptySet(), setOf("a"), "PARTIAL"),
                CombinedMappedClaim("3", "three", emptySet(), setOf("b"), "UNMATCHED")
            ),
            links = emptyList()
        )
    )

    @Test fun sharedClaimsCountForBothProfilesWithoutInventingExclusiveShares() {
        val result = requireNotNull(CombinedQualityDashboard.from(report(), "answer"))
        assertEquals(3, result.claims)
        assertEquals(1, result.shared)
        assertEquals(listOf(50, 50), result.profiles.map { it.linkedPercent })
        assertEquals(1, result.partial)
        assertEquals(1, result.unresolved)
        assertNull(result.usage)
    }

    @Test fun staleAndUnevaluatedCoverageCannotProduceSuccessPercentages() {
        assertNull(CombinedQualityDashboard.from(report(), "different revision"))
        val result = requireNotNull(CombinedQualityDashboard.from(report(false), "answer"))
        assertFalse(result.evaluated)
        result.profiles.forEach { assertNull(it.linkedPercent) }
    }
}
