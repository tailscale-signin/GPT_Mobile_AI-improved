package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolBudgetPolicyTest {

    @Test
    fun `executionLimit subtracts finalResponseToolCallReserve`() {
        val limits = AgentRunLimits(maxToolCalls = 20, finalResponseToolCallReserve = 2)
        assertEquals(18, ToolBudgetPolicy.executionLimit(limits))
    }

    @Test
    fun `executionLimit handles Int MAX_VALUE without underflow`() {
        val limits = AgentRunLimits(maxToolCalls = Int.MAX_VALUE, finalResponseToolCallReserve = 2)
        assertEquals(Int.MAX_VALUE, ToolBudgetPolicy.executionLimit(limits))
    }

    @Test
    fun `wrapUpThreshold calculates 1 fifth of maxToolCalls`() {
        assertEquals(4, ToolBudgetPolicy.wrapUpThreshold(AgentRunLimits(maxToolCalls = 20)))
        assertEquals(2, ToolBudgetPolicy.wrapUpThreshold(AgentRunLimits(maxToolCalls = 10)))
        assertEquals(1, ToolBudgetPolicy.wrapUpThreshold(AgentRunLimits(maxToolCalls = 5)))
        assertEquals(1, ToolBudgetPolicy.wrapUpThreshold(AgentRunLimits(maxToolCalls = 3)))
    }

    @Test
    fun `shouldEmitWrapUpNotice returns false when maxToolCalls is 2 or fewer`() {
        val limits = AgentRunLimits(maxToolCalls = 2, finalResponseToolCallReserve = 1)
        assertFalse(
            ToolBudgetPolicy.shouldEmitWrapUpNotice(
                executionLimit = 1,
                limits = limits,
                toolCallCount = 0,
                wrapUpNoticeEmitted = false
            )
        )
    }

    @Test
    fun `shouldEmitWrapUpNotice returns true within threshold window`() {
        val limits = AgentRunLimits(maxToolCalls = 10, finalResponseToolCallReserve = 0)
        // executionLimit = 10, threshold = 2. When toolCallCount = 8, remaining = 2 (in 1..2)
        assertTrue(
            ToolBudgetPolicy.shouldEmitWrapUpNotice(
                executionLimit = 10,
                limits = limits,
                toolCallCount = 8,
                wrapUpNoticeEmitted = false
            )
        )
        // Once notice emitted, it should not emit again
        assertFalse(
            ToolBudgetPolicy.shouldEmitWrapUpNotice(
                executionLimit = 10,
                limits = limits,
                toolCallCount = 9,
                wrapUpNoticeEmitted = true
            )
        )
    }

    @Test
    fun `remaining allowance is never negative or overflowed`() {
        assertEquals(0, ToolBudgetPolicy.remainingAllowance(10, 11))
        assertEquals(0, ToolBudgetPolicy.remainingAllowance(0, Int.MAX_VALUE))
        assertEquals(Int.MAX_VALUE, ToolBudgetPolicy.remainingAllowance(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(3, ToolBudgetPolicy.remainingAllowance(10, 7))
        assertEquals(1, ToolBudgetPolicy.remainingAllowance(1, Int.MIN_VALUE))
        assertEquals(0, ToolBudgetPolicy.remainingAllowance(Int.MIN_VALUE, 0))
        assertEquals(0, ToolBudgetPolicy.executionLimit(AgentRunLimits(maxToolCalls = Int.MIN_VALUE, finalResponseToolCallReserve = Int.MAX_VALUE)))
    }

    @Test
    fun `buildWrapUpPrompt includes remaining allowance`() {
        val prompt = ToolBudgetPolicy.buildWrapUpPrompt(3)
        assertTrue(prompt.contains("3 tool call(s) remaining"))
    }
}
