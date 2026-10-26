package dev.chungjungsoo.gptmobile.data.research

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
}
