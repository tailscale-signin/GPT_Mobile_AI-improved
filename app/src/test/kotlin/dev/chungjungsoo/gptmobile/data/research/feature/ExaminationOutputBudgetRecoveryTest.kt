package dev.chungjungsoo.gptmobile.data.research.feature

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExaminationOutputBudgetRecoveryTest {
    @Test fun wrappedOutputLimitCanTriggerSliceSplitting() {
        val failure = IllegalStateException("provider failed", IllegalStateException("OUTPUT_LIMIT: max output tokens reached"))
        assertTrue(ExaminationOutputBudget.canSplit(failure))
    }

    @Test fun authenticationFailureMustNotSplitScientificEvidence() {
        assertFalse(ExaminationOutputBudget.canSplit(IllegalStateException("HTTP 401 Unauthorized")))
    }

    @Test fun sourceAccessFailureMustNotSplitScientificEvidence() {
        assertFalse(ExaminationOutputBudget.canSplit(IllegalStateException("HTTP 403 Forbidden")))
    }
}
