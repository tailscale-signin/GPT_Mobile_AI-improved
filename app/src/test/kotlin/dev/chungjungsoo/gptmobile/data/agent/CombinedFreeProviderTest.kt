package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.free.FreeCatalogState
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.data.network.FreeAiRequestLimiter
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CombinedFreeProviderTest {
    @Before fun reset() {
        FreeCatalogState.resetForTest()
        FreeAiRequestLimiter.shared.initialize(Files.createTempDirectory("combined-free").resolve("cooldowns.json").toFile())
    }

    @Test fun classicIsEligibleAsAContributorWithItsNativeBound() {
        val profile = FreeAiProvider.POLLINATIONS.applyTo(PlatformV2(name = "Classic", enabled = true))
        assertNull(CombinedProviderEligibility.unavailableReason(profile, "Compare three techniques"))
        assertNotNull(CombinedProviderEligibility.unavailableReason(profile.copy(maxTokens = 1499)))
        assertNotNull(CombinedProviderEligibility.unavailableReason(FreeAiProvider.HUGGING_FACE.applyTo(profile)))
        FreeAiRequestLimiter.shared.reserve(FreeAiProvider.POLLINATIONS)
        assertNull(CombinedProviderEligibility.unavailableReason(profile, "Compare three techniques"))
    }

    @Test fun lowEffortFundsTheFixedAllowanceWithoutOverridingAHardRemainingLimit() {
        val accepted = CombinedPolicyResolver.accept(ConversationCombinedSettings(effort = 0), listOf("classic"), mapOf("classic" to "openai-fast"), "Say hello", singlePass = true)
        val caps = listOf(CombinedCapRecord("providerFixedOutputTokens", 1500, CombinedCapOrigin.PROVIDER, CombinedCapScope.INVOCATION, "Verified route", "classic"))
        val funded = CombinedPolicyResolver.resolve(accepted, requiredOutputTokens = 500, caps = caps)
        assertEquals(1500, funded.initialDraftOutputTokens)
        assertEquals(1500L, funded.baselineByOwner.getValue("contributor:classic").generatedTokens)
        val insufficient = CombinedPolicyResolver.resolve(accepted, requiredOutputTokens = 500, caps = caps, hardLimits = CombinedHardLimits(generatedTokens = 1999))
        assertTrue("classic" in insufficient.blockedParticipantUids)
        assertTrue(insufficient.baseline.generatedTokens + insufficient.finalizationReserve.generatedTokens <= 1999)
    }
}
