package dev.chungjungsoo.gptmobile.data.research.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResearchReviewScoreCalculatorTest {
    private fun artifact() = StudyReviewArtifact("The trial found an uncertain effect.", "Small sample.", "primary", listOf("p"), ResearchReviewScoreCalculator.reliabilityDomains.mapIndexed { i, name -> ReviewDomain(name, if (i < 3) 3 else 2, "Reported methods", listOf("p")) }, ResearchReviewScoreCalculator.accuracyDomains.mapIndexed { i, name -> ReviewDomain(name, if (i == 0) 3 else 4, "Supported by the source", listOf("p")) })

    @Test fun lowerComponentDeterminesOverall() {
        val score = ResearchReviewScoreCalculator.calculate(artifact(), true, StudyDesign.RANDOMIZED_TRIAL, false)
        assertEquals(65, score.reliability)
        assertEquals(95, score.accuracy)
        assertEquals(65, score.overall)
    }

    @Test fun abstractNeverReceivesReliability() {
        val score = ResearchReviewScoreCalculator.calculate(artifact(), false, StudyDesign.RANDOMIZED_TRIAL, false)
        assertNull(score.reliability)
        assertNull(score.overall)
        assertEquals(95, score.accuracy)
    }

    @Test fun missingDomainCannotImproveScore() {
        val item = artifact()
        assertNull(ResearchReviewScoreCalculator.calculate(item.copy(reliability = item.reliability.dropLast(1)), true, StudyDesign.OBSERVATIONAL, false).overall)
        assertNull(ResearchReviewScoreCalculator.calculate(item.copy(accuracy = item.accuracy.mapIndexed { i, d -> if (i == 0) d.copy(grade = null) else d }), true, StudyDesign.OBSERVATIONAL, false).overall)
    }

    @Test fun criticalFlagsAndRetractionSuspendRanking() {
        val item = artifact()
        listOf(item.copy(criticalConcern = true), item.copy(summaryContradiction = true), item.copy(integrityConcern = true)).forEach { assertNull(ResearchReviewScoreCalculator.calculate(it, true, StudyDesign.RANDOMIZED_TRIAL, false).overall) }
        assertNull(ResearchReviewScoreCalculator.calculate(item, true, StudyDesign.RANDOMIZED_TRIAL, true).overall)
    }

    @Test fun protocolsAndCommentaryHaveNoObservedResultReliability() {
        listOf(StudyDesign.PROTOCOL, StudyDesign.COMMENTARY, StudyDesign.UNKNOWN).forEach { assertNull(ResearchReviewScoreCalculator.calculate(artifact(), true, it, false).reliability) }
    }

    @Test fun changingLimitationInvalidatesSummaryReuse() {
        val item = artifact()
        org.junit.Assert.assertNotEquals(ResearchReviewScoreCalculator.summaryHash(item), ResearchReviewScoreCalculator.summaryHash(item.copy(limitation = "Unmeasured confounding.")))
    }
}
