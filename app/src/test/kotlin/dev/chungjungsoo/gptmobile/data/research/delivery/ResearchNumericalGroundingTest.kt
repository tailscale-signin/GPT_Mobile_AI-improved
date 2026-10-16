package dev.chungjungsoo.gptmobile.data.research.delivery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchNumericalGroundingTest {
    private val passage = "The trial enrolled 20 participants. Scores were 9 versus 14 points after 2 weeks."

    @Test fun sampleCountCannotBecomeAnOutcomeInTheSamePaper() {
        assertTrue(ResearchNumericalGrounding.issues("Scores improved by 20 points.", listOf(passage)).contains("NUMERICAL_OUTCOME_UNIT_MISMATCH"))
    }

    @Test fun orderedGroupComparisonCannotBeReversed() {
        assertTrue(ResearchNumericalGrounding.issues("Scores were 14 versus 9 points.", listOf(passage)).contains("NUMERICAL_COMPARATOR_ORDER_MISMATCH"))
    }

    @Test fun unitsAndTimepointsCannotBeBorrowedFromAnotherValue() {
        assertTrue(ResearchNumericalGrounding.issues("Follow-up was 20 weeks.", listOf(passage)).isNotEmpty())
        assertTrue(ResearchNumericalGrounding.issues("Scores were 14 mg.", listOf(passage)).isNotEmpty())
    }

    @Test fun equivalentFormattingAndParticipantNounsAreAccepted() {
        assertEquals(emptyList<String>(), ResearchNumericalGrounding.issues("20 patients had scores of 9.0 versus 14.0 points after 2 weeks.", listOf(passage)))
    }
}
