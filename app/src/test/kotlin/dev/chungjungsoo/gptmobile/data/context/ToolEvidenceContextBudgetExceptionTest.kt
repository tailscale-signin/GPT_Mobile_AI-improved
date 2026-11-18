package dev.chungjungsoo.gptmobile.data.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolEvidenceContextBudgetExceptionTest {
    @Test fun unknownToolRouteIsBudgetedBeforeTheRequestExhaustsOutput() {
        val configured = TokenBudgetSettings(contextTokens = Int.MAX_VALUE, outputTokens = 4096)
        val planned = ReplayAllowance.conservativeToolPlanningBudget(configured, true)
        assertEquals(4096, planned.contextTokens)
        assertEquals(768, planned.outputTokens)
        val admitted = ReplayAllowance.allocate(planned.contextTokens, 2100, planned.outputTokens, 4096)
        assertTrue(admitted.output > 0 && admitted.replay > 0)
        assertEquals(0, ReplayAllowance.allocate(Int.MAX_VALUE, 3802, 4096, 4096).output)
        assertSame(configured, ReplayAllowance.conservativeToolPlanningBudget(configured, false))
        val known = TokenBudgetSettings(contextTokens = 32768, outputTokens = 4096)
        assertSame(known, ReplayAllowance.conservativeToolPlanningBudget(known, true))
    }

    @Test fun positiveReplyAndEvidenceReturnTheSameAllocation() {
        val allocation = ReplayAllowance.allocate(5632, 2400, 4096, 4096)
        assertEquals(1309, allocation.output)
        assertEquals(1059, allocation.replay)
        assertSame(allocation, ToolEvidenceContextBudgetException.requireAllocation(allocation, 5632, 4096))
        assertEquals(5632L, allocation.input.toLong() + allocation.safety + allocation.output + allocation.replay)
    }

    @Test fun zeroEvidenceRejectsWithoutClaimingThatInputFilledTheContext() {
        val allocation = ReplayAllowance.allocate(769, 0, 513, 4096)
        assertEquals(513, allocation.output)
        assertEquals(0, allocation.replay)
        assertTrue(allocation.input.toLong() + allocation.safety < allocation.ceiling)
        val failure = reject(allocation, 769, 513)
        assertSame(allocation, failure.allocation)
        assertEquals("CONTEXT_CAPACITY_EXCEEDED", failure.code)
        assertEquals(CapacityProvenance.UNKNOWN, failure.capacityProvenance)
        assertEquals(0, failure.input)
        assertEquals(256, failure.safety)
        assertEquals(769, failure.ceiling)
        assertEquals(513, failure.output)
        assertEquals(0, failure.replay)
        assertEquals(769, failure.resolvedContextTokens)
        assertEquals(513, failure.requestedOutputTokens)
    }

    @Test fun oneTokenOfEvidencePreservesTheExistingStrictPositiveBoundary() {
        val allocation = ReplayAllowance.allocate(770, 0, 513, 4096)
        assertEquals(513, allocation.output)
        assertEquals(1, allocation.replay)
        assertSame(allocation, ToolEvidenceContextBudgetException.requireAllocation(allocation, 770, 513))
    }

    @Test fun nonpositiveRequestedOutputUsesOutputCodeDespiteEvidenceHeadroom() {
        for (requested in listOf(0, -1, Int.MIN_VALUE)) {
            val allocation = ReplayAllowance.allocate(4096, 0, requested, 4096)
            assertEquals(0, allocation.output)
            assertEquals(3277, allocation.replay)
            val failure = reject(allocation, 4096, requested)
            assertSame(allocation, failure.allocation)
            assertEquals("OUTPUT_CAPACITY_EXCEEDED", failure.code)
            assertEquals(requested, failure.requestedOutputTokens)
            assertEquals(CapacityProvenance.UNKNOWN, failure.capacityProvenance)
        }
    }

    @Test fun positiveRequestedOutputWithNoHeadroomUsesContextCode() {
        val allocation = ReplayAllowance.allocate(4096, 3277, 1, 4096)
        assertEquals(0, allocation.output)
        assertEquals(0, allocation.replay)
        assertEquals(allocation.ceiling.toLong(), allocation.input.toLong() + allocation.safety)
        val failure = reject(allocation, 4096, 1)
        assertEquals("CONTEXT_CAPACITY_EXCEEDED", failure.code)
        assertEquals(1, failure.requestedOutputTokens)
        assertSame(allocation, failure.allocation)
    }

    @Test fun bothZeroAllowancesRetainOverCapacityInputAndRequestedOutputClassification() {
        for (requested in listOf(0, 4096)) {
            val allocation = ReplayAllowance.allocate(5632, 9043, requested, 4096)
            assertEquals(0, allocation.output)
            assertEquals(0, allocation.replay)
            val failure = reject(allocation, 5632, requested)
            assertEquals(9043, failure.input)
            assertEquals(5632, failure.ceiling)
            assertEquals(2260, failure.safety)
            assertEquals(if (requested == 0) "OUTPUT_CAPACITY_EXCEEDED" else "CONTEXT_CAPACITY_EXCEEDED", failure.code)
            assertSame(allocation, failure.allocation)
        }
    }

    @Test fun unknownAndInvalidContextRetainTheActualConservativeFallback() {
        for (resolved in listOf(0, -1, Int.MIN_VALUE, Int.MAX_VALUE)) {
            for ((fallback, ceiling) in listOf(2048 to 2048, Int.MAX_VALUE to 4096, -1 to 0)) {
                val allocation = ReplayAllowance.allocate(resolved, 5000, 1024, fallback)
                val failure = reject(allocation, resolved, 1024)
                assertSame(allocation, failure.allocation)
                assertEquals(resolved, failure.resolvedContextTokens)
                assertEquals(ceiling, failure.ceiling)
                assertEquals(CapacityProvenance.CONSERVATIVE_POLICY, failure.capacityProvenance)
                assertEquals("CONTEXT_CAPACITY_EXCEEDED", failure.code)
            }
        }
    }

    @Test fun finiteIntegerBoundariesStayUnattributedAndKeepBoundedRecoveryGuidance() {
        for (resolved in listOf(1, Int.MAX_VALUE - 1)) {
            val allocation = ReplayAllowance.allocate(resolved, Int.MAX_VALUE, Int.MAX_VALUE, 4096)
            val failure = reject(allocation, resolved, Int.MAX_VALUE)
            assertEquals(CapacityProvenance.UNKNOWN, failure.capacityProvenance)
            assertEquals(resolved, failure.ceiling)
            assertEquals(Int.MAX_VALUE, failure.input)
            assertEquals(Int.MAX_VALUE, failure.requestedOutputTokens)
            assertEquals(0, failure.output)
            assertEquals(0, failure.replay)
            assertTrue(failure.input.toLong() + failure.safety > failure.ceiling)
            val message = requireNotNull(failure.message)
            assertTrue(message.length < 768)
            assertTrue(message.contains("allowance=$resolved"))
            assertTrue(message.contains("input=${Int.MAX_VALUE}"))
            assertTrue(message.contains("context and output limits"))
            assertTrue(message.contains("start a new request"))
            assertTrue(message.contains("Automatic retry is not allowed"))
            assertFalse(message.contains("verified", ignoreCase = true))
            assertFalse(message.contains("tokenizer", ignoreCase = true))
        }
        val normalized = ReplayAllowance.allocate(Int.MAX_VALUE - 1, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(0, normalized.input)
        assertTrue(normalized.output > 0 && normalized.replay > 0)
        assertEquals(24000, normalized.replay)
        assertEquals(6000, normalized.safety)
        assertTrue(normalized.input.toLong() + normalized.safety + normalized.output + normalized.replay <= normalized.ceiling)
        assertSame(normalized, ToolEvidenceContextBudgetException.requireAllocation(normalized, Int.MAX_VALUE - 1, Int.MAX_VALUE))
    }

    private fun reject(allocation: ReplayAllowance.Allocation, resolved: Int, requested: Int): ToolEvidenceContextBudgetException =
        assertThrows(ToolEvidenceContextBudgetException::class.java) {
            ToolEvidenceContextBudgetException.requireAllocation(allocation, resolved, requested)
        }
}
