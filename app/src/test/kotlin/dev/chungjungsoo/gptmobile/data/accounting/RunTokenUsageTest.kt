package dev.chungjungsoo.gptmobile.data.accounting

import org.junit.Assert.assertEquals
import org.junit.Test

class RunTokenUsageTest {
    @Test fun allPassesAreCountedOnceAndEstimatesRemainSeparate() {
        fun row(id: String, kind: String, input: Long?, output: Long?) = ModelInvocation(id, "run", "turn", "local", "model", kind, 9000, 9000, inputTokenSource = "provider_reported", outputTokenSource = "provider_reported", observedInputTokens = input, observedOutputTokens = output)
        val primary = row("primary", "primary", 100, 20)
        val usage = RunTokenUsage.from(listOf(primary, primary, row("review", "github-review", 50, 5), row("extract", "github-claim-extraction", 30, 3), row("repair", "recovery", 40, 4), row("unknown", "recovery", null, null)))
        assertEquals(220, usage.inputTokens)
        assertEquals(32, usage.outputTokens)
        assertEquals(252, usage.measuredSubtotalTokens)
        assertEquals(null, usage.totalTokens)
        assertEquals(1, usage.unmeasuredInvocations)
    }

    @Test fun missingUsageIsNotZeroAndZeroIsStillMeasured() {
        val unknown = ModelInvocation("id", "run", "turn", "local", "model", "primary", 100, 100)
        assertEquals(null, RunTokenUsage.from(listOf(unknown)).totalTokens)
        val measured = unknown.copy(observedInputTokens = 0, observedOutputTokens = 0, inputTokenSource = "gateway_reported", outputTokenSource = "gateway_reported")
        assertEquals(0, RunTokenUsage.from(listOf(measured)).totalTokens)
    }

    @Test fun retainedNoDispatchReceiptsProveZeroWhileAnEmptyOrMixedLedgerDoesNot() {
        val rejected = ModelInvocation("rejected", "run", "turn", "local", "model", "primary", 0, 0, status = "NOT_DISPATCHED", inputTokenSource = "not_dispatched", outputTokenSource = "not_dispatched")
        val knownZero = RunTokenUsage.from(listOf(rejected, rejected))
        assertEquals(0, knownZero.inputTokens)
        assertEquals(0, knownZero.outputTokens)
        assertEquals(0, knownZero.totalTokens)
        assertEquals(0, knownZero.unmeasuredInvocations)
        assertEquals("NOT_DISPATCHED", knownZero.basis)
        assertEquals(null, RunTokenUsage.from(emptyList()).totalTokens)
        val unknown = rejected.copy(id = "unknown", status = "FAILED", inputTokenSource = "estimated", outputTokenSource = "estimated")
        val mixed = RunTokenUsage.from(listOf(rejected, unknown))
        assertEquals(null, mixed.totalTokens)
        assertEquals(1, mixed.unmeasuredInvocations)
        val conflicting = rejected.copy(inputTokens = 100, observedInputTokens = 100)
        assertEquals(null, RunTokenUsage.from(listOf(conflicting)).totalTokens)
    }

    @Test fun overflowSaturatesWithoutBecomingMeasuredZero() {
        val first = ModelInvocation(
            "first", "run", "turn", "local", "model", "primary", 100, 100,
            observedInputTokens = Long.MAX_VALUE,
            observedOutputTokens = Long.MAX_VALUE,
            inputTokenSource = "provider_reported",
            outputTokenSource = "provider_reported"
        )
        val usage = RunTokenUsage.from(listOf(first, first.copy(id = "second")))
        assertEquals(Int.MAX_VALUE, usage.inputTokens)
        assertEquals(Int.MAX_VALUE, usage.outputTokens)
        assertEquals(Int.MAX_VALUE, usage.totalTokens)
        assertEquals(0, usage.unmeasuredInvocations)
    }

    @Test fun invalidAndEstimatedCountersDoNotMakeUsageComplete() {
        val invalid = ModelInvocation(
            "invalid", "run", "turn", "local", "model", "primary", 100, 100,
            observedInputTokens = -1,
            observedOutputTokens = 0,
            inputTokenSource = "provider_reported",
            outputTokenSource = "provider_reported"
        )
        val estimated = invalid.copy(id = "estimated", observedInputTokens = 200, observedOutputTokens = 80, inputTokenSource = "estimated", outputTokenSource = "estimated")
        val measured = invalid.copy(id = "measured", observedInputTokens = 20, observedOutputTokens = 5)
        val usage = RunTokenUsage.from(listOf(invalid, estimated, measured))
        assertEquals(20, usage.inputTokens)
        assertEquals(5, usage.outputTokens)
        assertEquals(25, usage.measuredSubtotalTokens)
        assertEquals(null, usage.totalTokens)
        assertEquals(2, usage.unmeasuredInvocations)
    }
}
