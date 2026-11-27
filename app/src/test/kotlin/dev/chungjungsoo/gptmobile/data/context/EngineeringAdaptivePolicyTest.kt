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

    @Test fun problemAnalysisDoesNotBecomeAQuickLookupBecauseItContainsList() {
        val config = EngineeringProfileSettings(mode = EngineeringMode.ADAPTIVE)
        assertEquals(EngineeringMode.DEEP, EngineeringAdaptivePolicy.mode(config, "List all repository problems and solutions"))
        val explicit = TokenBudgetSettings(contextTokens = 8192, outputTokens = 512)
        assertEquals(explicit, EngineeringAdaptivePolicy.budgets(config, "Review all repository bugs", explicit))
    }

    @Test fun unknownRouteReceivesConservativeCapacityBeforeAdaptivePresets() {
        val planned = EngineeringAdaptivePolicy.forPreparation(
            EngineeringProfileSettings(mode = EngineeringMode.ADAPTIVE),
            "Review the selected branch",
            TokenBudgetSettings(),
            boundGitHubWorkspace = true,
            toolEvidenceRequired = true
        )
        assertEquals(4096, planned.contextTokens)
        assertEquals(768, planned.outputTokens)
        val verified = TokenBudgetSettings(contextTokens = 5632, outputTokens = 512)
        assertEquals(verified, EngineeringAdaptivePolicy.forPreparation(EngineeringProfileSettings(mode = EngineeringMode.ADAPTIVE), "Review the branch", verified, true, true))
        assertEquals(TokenBudgetSettings(), EngineeringAdaptivePolicy.forPreparation(null, "A normal conversation", TokenBudgetSettings()))
    }

    @Test fun resolvedToolScopeCannotUseAnEarlierPresetAsVerifiedRouteCapacity() {
        val config = EngineeringProfileSettings(mode = EngineeringMode.ADAPTIVE, githubOnly = false)
        val provisional = EngineeringAdaptivePolicy.forPreparation(config, "List branches", TokenBudgetSettings())
        assertEquals(8192, provisional.contextTokens)
        val resolved = EngineeringAdaptivePolicy.forResolvedTools(config, "List branches", provisional, Int.MAX_VALUE, true, true, 3)
        assertEquals(4096, resolved.contextTokens)
        assertEquals(768, resolved.outputTokens)
        val explicit = TokenBudgetSettings(contextTokens = 2048, outputTokens = 128)
        assertEquals(explicit, EngineeringAdaptivePolicy.forResolvedTools(config, "List branches", explicit, 5632, true, true, 3))
    }

    @Test fun inferredRepositoryScopeResolvesBeforeOptionalContextAndKeepsSmallerTargets() {
        val config = EngineeringProfileSettings(mode = EngineeringMode.ADAPTIVE)
        val existing = TokenBudgetSettings(contextTokens = 65_536, outputTokens = 512)
        val resolved = EngineeringAdaptivePolicy.forResolvedTools(config, "List branches", existing, 65_536, true, true, 3)
        assertEquals(8192, resolved.contextTokens)
        assertEquals(512, resolved.outputTokens)
        val smaller = existing.copy(contextTokens = 4096)
        assertEquals(smaller, EngineeringAdaptivePolicy.forResolvedTools(config, "Review the source", smaller, 65_536, true, true, 3))
    }
}
