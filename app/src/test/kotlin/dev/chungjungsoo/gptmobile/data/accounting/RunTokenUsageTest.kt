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
        assertEquals(252, usage.totalTokens)
        assertEquals(1, usage.unmeasuredInvocations)
    }

    @Test fun missingUsageIsNotZeroAndZeroIsStillMeasured() {
        val unknown = ModelInvocation("id", "run", "turn", "local", "model", "primary", 100, 100)
        assertEquals(null, RunTokenUsage.from(listOf(unknown)).totalTokens)
        val measured = unknown.copy(observedInputTokens = 0, observedOutputTokens = 0, inputTokenSource = "gateway_reported", outputTokenSource = "gateway_reported")
        assertEquals(0, RunTokenUsage.from(listOf(measured)).totalTokens)
    }
}
