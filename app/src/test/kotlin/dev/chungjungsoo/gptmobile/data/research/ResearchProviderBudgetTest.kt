package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.context.TokenBudgetSettings
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchProviderBudgetTest {
    private fun profile(provider: FreeAiProvider) = PlatformV2(name = provider.displayName, compatibleType = ClientType.FREE, apiUrl = provider.apiUrl, model = provider.model)

    @Test fun fixedProviderReservesActualAllowanceForSmallExtractionTarget() {
        assertEquals(1500, ResearchProviderBudget.output(profile(FreeAiProvider.POLLINATIONS), 512))
    }

    @Test fun fixedProviderCannotOverrideUserOutputLimit() {
        assertTrue(runCatching { ResearchProviderBudget.output(profile(FreeAiProvider.POLLINATIONS).copy(maxTokens = 512), 512) }.isFailure)
        assertTrue(runCatching { ResearchProviderBudget.output(profile(FreeAiProvider.POLLINATIONS), 512, 1000) }.isFailure)
    }

    @Test fun customAndLocalProfilesAlsoHonorConfiguredOutputLimits() {
        val custom = PlatformV2(uid = "custom", name = "Custom", compatibleType = ClientType.OPENAI, maxTokens = 512)
        assertEquals(512, ResearchProviderBudget.output(custom, 6144))
        assertEquals(256, ResearchProviderBudget.output(custom, 6144, 256))
    }

    @Test fun hordeExtractionRespectsRealOutputCeiling() {
        assertEquals(256, ResearchProviderBudget.output(profile(FreeAiProvider.AI_HORDE), 512))
    }

    @Test fun requestAndOutputAndSafetyFitFiniteContextIncludingUnknownRouteDefault() {
        assertEquals(7428, ResearchProviderBudget.inputCapacity(8000, 300))
        assertEquals(2324, ResearchProviderBudget.inputCapacity(ResearchProviderBudget.UNKNOWN_CONTEXT_TOKENS, 1500))
        assertEquals(0, ResearchProviderBudget.inputCapacity(256, 512))
    }

    @Test fun qwenSmallStageAdmitsReasoningAllowanceBeforeDispatchWithoutChangingTheProfile() {
        val selected = PlatformV2(uid = "selected", name = "Gateway", compatibleType = ClientType.LLAMA, model = "qwen3.5-9b", maxTokens = 4096)
        val allowance = ResearchProviderBudget.request(selected, 512, TokenBudgetSettings(outputTokens = 3072))
        assertEquals(2048, allowance.outputTokens)
        assertEquals(4096, selected.maxTokens)
        assertEquals(2048, ResearchProviderBudget.output(selected, allowance.outputTokens, 3072))
    }

    @Test fun reasoningAllowanceCannotOverrideProfileOrGlobalHardCaps() {
        val selected = PlatformV2(uid = "selected", name = "Gateway", compatibleType = ClientType.LLAMA, model = "qwen3.5-9b")
        for ((profileCap, configuredCap) in listOf(512 to 4096, 4096 to 512, 1024 to 4096, 4096 to 1024)) {
            val result = runCatching { ResearchProviderBudget.request(selected.copy(maxTokens = profileCap), 512, TokenBudgetSettings(outputTokens = configuredCap)) }
            assertTrue("profile=$profileCap configured=$configuredCap", result.exceptionOrNull()?.message.orEmpty().contains("RESEARCH_OUTPUT_ALLOWANCE"))
        }
        assertEquals(2048, ResearchProviderBudget.request(selected.copy(maxTokens = 2048), 512, TokenBudgetSettings(outputTokens = 2048)).outputTokens)
        val nonReasoning = selected.copy(model = "compact-instruct", maxTokens = 512)
        assertEquals(256, ResearchProviderBudget.request(nonReasoning, 512, TokenBudgetSettings(outputTokens = 256)).outputTokens)
    }

    @Test fun nonFreePacketsReserveTheCompleteReasoningAndSystemAllowanceInsideTheSmallestContext() {
        val selected = PlatformV2(uid = "selected", name = "Gateway", compatibleType = ClientType.LLAMA, model = "qwen3.5-9b", maxTokens = 4096)
        val allowance = ResearchProviderBudget.request(selected, 512, TokenBudgetSettings(contextTokens = 8192, profileContextCeilings = mapOf(selected.uid to 4096)))
        assertTrue(allowance.bounded)
        assertEquals(2048, allowance.outputTokens)
        assertTrue(allowance.inputTokens in 1 until 2048)
        val exhausted = ResearchProviderBudget.request(selected, 512, TokenBudgetSettings(contextTokens = 2048))
        assertEquals(0, exhausted.inputTokens)
    }

    @Test fun knownProviderCeilingCannotSilentlyShrinkTheAdmittedReasoningEnvelope() {
        val selected = PlatformV2(name = "Gateway", compatibleType = ClientType.LLAMA, apiUrl = "http://127.0.0.1:8182/v1", model = "qwen3.5-budget-ceiling", maxTokens = 4096)
        dev.chungjungsoo.gptmobile.data.agent.provider.ProviderOutputLimits.learn(
            dev.chungjungsoo.gptmobile.data.agent.provider.ProviderRouteCapacity.key(selected),
            "max_tokens is too large; maximum 512 output tokens",
            4096
        )
        val result = runCatching { ResearchProviderBudget.request(selected, 512, TokenBudgetSettings()) }
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("RESEARCH_OUTPUT_ALLOWANCE"))
    }
}
