package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.provider.RequestConstraints
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerRewriteConstraintsTest {
    @Test fun rewriteKeepsAcceptedReasoningWhileDroppingMaximumPreference() {
        val original = RequestConstraints(allowReasoning = true, maximizeReasoning = true)
        val rewrite = original.withoutTools().forRole("answer_contract_rewrite")
        assertTrue(rewrite.allowReasoning)
        assertFalse(rewrite.maximizeReasoning)
        assertFalse(rewrite.allowTools)
        assertTrue(original.forRole("primary").allowReasoning)
        assertTrue(original.forRole("primary").maximizeReasoning)
    }
}
