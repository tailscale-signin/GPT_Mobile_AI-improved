package dev.chungjungsoo.gptmobile.data.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineeringAdaptivePolicyTest {
    @Test fun disabledAndUnrelatedRequestsPreserveBudgets() {
        val original = TokenBudgetSettings(contextTokens = 4096, outputTokens = 1024)
        assertEquals(original, EngineeringAdaptivePolicy.budgets(EngineeringProfileSettings(), "fix GitHub", original))
        assertEquals(original, EngineeringAdaptivePolicy.budgets(EngineeringProfileSettings(mode = EngineeringMode.ADAPTIVE), "write a poem", original))
    }

    @Test fun escalationCannotExpandSmallContextOrExplicitOutputCap() {
        val original = TokenBudgetSettings(contextTokens = 1024, outputTokens = 512)
        val config = EngineeringProfileSettings(mode = EngineeringMode.ADAPTIVE)
        val result = EngineeringAdaptivePolicy.budgets(config, "GitHub multi-file migration with failed tests", original)
        assertEquals(EngineeringMode.ENGINEERING_MAX, EngineeringAdaptivePolicy.mode(config, "GitHub multi-file migration with failed tests"))
        assertEquals(1024, result.contextTokens)
        assertEquals(512, result.outputTokens)
    }

    @Test fun routineDiscoveryUsesLessBudgetThanImplementation() {
        val config = EngineeringProfileSettings(mode = EngineeringMode.ADAPTIVE)
        val original = TokenBudgetSettings()
        val quick = EngineeringAdaptivePolicy.budgets(config, "GitHub list branches", original)
        val deep = EngineeringAdaptivePolicy.budgets(config, "GitHub implement a fix", original)
        assertTrue(quick.contextTokens < deep.contextTokens)
        assertTrue(quick.outputTokens < deep.outputTokens)
    }
}
