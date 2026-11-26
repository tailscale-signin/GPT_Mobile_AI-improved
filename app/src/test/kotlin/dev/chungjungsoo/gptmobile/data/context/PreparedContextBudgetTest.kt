package dev.chungjungsoo.gptmobile.data.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedContextBudgetTest {
    @Test fun optionalReferenceUsesResidualSpaceAfterHistoryToolOutputAndExactReplay() {
        val plan = ContextPlan(emptyList(), "", emptyList(), 1536, 512, "", promptTokens = 3400)
        val allowance = PreparedContextBudget.optionalTokens(plan, 5632, 4096)
        val fits = "source: pinned blob and line range"
        val tooLarge = "x".repeat(allowance * 3 + 1)
        val selected = PreparedContextBudget.sections(listOf(tooLarge, fits), allowance)
        assertTrue(fits in selected)
        assertFalse(tooLarge in selected)
        val prompt = plan.promptTokens.toLong() + plan.toolResultBytes / 3 + allowance + 64
        assertTrue(prompt + RequestAdmission.estimateSafety(prompt) + 512 <= 5632)
        assertEquals(0, PreparedContextBudget.optionalTokens(plan.copy(promptTokens = 8914), 8192, 512))
        assertEquals("", PreparedContextBudget.sections(listOf(fits), 0))
    }
}
