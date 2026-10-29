package dev.chungjungsoo.gptmobile.presentation.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactApprovalSummaryTest {
    @Test fun summaryLimitsLongMultiSentenceProposalsToTwoSentences() {
        val summary = compactApprovalSummary(3, "fix/layout", "Fix viewport sizing. Rebuild everything! More details?\nFull diff follows")
        assertEquals("3 changed files on fix/layout. Fix viewport sizing.", summary)
    }

    @Test fun emptyAndLongCommitMessagesStayBounded() {
        assertTrue(compactApprovalSummary(1, "main", "").contains("1 changed file"))
        assertTrue(compactApprovalSummary(50, "a".repeat(100), "b".repeat(1000)).length < 260)
    }
}
