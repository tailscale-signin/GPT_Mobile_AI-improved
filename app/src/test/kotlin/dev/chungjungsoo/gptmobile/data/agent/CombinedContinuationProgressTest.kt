package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class CombinedContinuationProgressTest {
    @Test fun emptyCandidateDoesNotAdvance() {
        assertEquals(CombinedContinuationProgress.Result.EMPTY, CombinedContinuationProgress.assess("Saved answer", "  "))
    }

    @Test fun whitespaceOnlyChangesDoNotAdvance() {
        assertEquals(CombinedContinuationProgress.Result.REPEATED, CombinedContinuationProgress.assess("First paragraph. Second paragraph.", " First  paragraph.  Second paragraph. "))
    }

    @Test fun shorterRepeatedPrefixDoesNotAdvance() {
        assertEquals(CombinedContinuationProgress.Result.REPEATED, CombinedContinuationProgress.assess("First. Second.", "First."))
    }

    @Test fun appendedTextAdvances() {
        assertEquals(CombinedContinuationProgress.Result.ADVANCED, CombinedContinuationProgress.assess("First.", "First. Second."))
    }

    @Test fun overlappingNewSuffixAdvances() {
        assertEquals(CombinedContinuationProgress.Result.ADVANCED, CombinedContinuationProgress.assess("First. Second.", "Second. Third."))
    }

    @Test fun suffixOnlyDoesNotAdvance() {
        assertEquals(CombinedContinuationProgress.Result.REPEATED, CombinedContinuationProgress.assess("First. Second.", "Second."))
    }
}
