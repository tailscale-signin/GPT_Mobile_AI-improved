package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyProfileEligibilityTest {
    private fun profile(
        type: ClientType,
        url: String = "",
        model: String = "test-model"
    ) = PlatformV2(
        name = "Test profile",
        compatibleType = type,
        apiUrl = url,
        model = model
    )

    @Test
    fun masterOff_keepsExistingProfilesSelectable() {
        val remote = profile(ClientType.GOOGLE, "https://generativelanguage.googleapis.com")
        assertTrue(PrivacyProfileEligibility.evaluate(remote, false).eligible)
    }

    @Test
    fun masterOn_allowsPrivateOnDeviceInference() {
        assertTrue(PrivacyProfileEligibility.evaluate(profile(ClientType.LITERT_LM), true).eligible)
        assertTrue(PrivacyProfileEligibility.evaluate(profile(ClientType.LLAMA), true).eligible)
        assertTrue(PrivacyProfileEligibility.evaluate(profile(ClientType.LLAMA, "http://192.168.1.20:8080"), true).eligible)
        assertTrue(PrivacyProfileEligibility.evaluate(profile(ClientType.OLLAMA, "http://100.101.22.33:11434"), true).eligible)
    }

    @Test
    fun masterOn_rejectsUnverifiedRemoteGateway() {
        assertFalse(PrivacyProfileEligibility.evaluate(profile(ClientType.LLAMA, "https://cloud-proxy.example.com/v1"), true).eligible)
        assertFalse(PrivacyProfileEligibility.evaluate(profile(ClientType.OLLAMA, "http://8.8.8.8:11434"), true).eligible)
        assertFalse(PrivacyProfileEligibility.evaluate(profile(ClientType.OLLAMA, "http://host.invalid:11434"), true).eligible)
    }

    @Test
    fun masterOn_rejectsAllFreeServicesWithoutEndpointAndAccountVerification() {
        FreeAiProvider.entries.forEach { provider ->
            val decision = PrivacyProfileEligibility.evaluate(profile(ClientType.FREE, provider.apiUrl, provider.model), true)
            assertFalse("${provider.displayName} must fail closed: ${decision.explanation}", decision.eligible)
            assertTrue(decision.explanation.isNotBlank())
        }
    }

    @Test
    fun masterOn_identifiesKiloAutoAndVolunteerWorkerRisk() {
        val kilo = PrivacyProfileEligibility.freeDecision(FreeAiProvider.KILO, "kilo-auto/free")
        val horde = PrivacyProfileEligibility.freeDecision(FreeAiProvider.AI_HORDE, "automatic")
        assertTrue(kilo.explanation.contains("routing", ignoreCase = true) || kilo.explanation.contains("route", ignoreCase = true))
        assertTrue(horde.explanation.contains("worker", ignoreCase = true))
        assertFalse(kilo.eligible)
        assertFalse(horde.eligible)
    }

    @Test
    fun masterOn_rejectsUnknownFreeRoutingAndStandardCloudModels() {
        assertFalse(PrivacyProfileEligibility.evaluate(profile(ClientType.FREE, "https://unrecognized.example/v1"), true).eligible)
        assertFalse(PrivacyProfileEligibility.evaluate(profile(ClientType.OPENROUTER, "https://openrouter.ai/api/v1", "openrouter/free"), true).eligible)
        assertFalse(PrivacyProfileEligibility.evaluate(profile(ClientType.GOOGLE), true).eligible)
        assertFalse(PrivacyProfileEligibility.evaluate(profile(ClientType.GROQ), true).eligible)
    }

    @Test
    fun categoryDefaultsAreAllEnabledWithMasterOff() {
        val state = PrivacyModeState()
        assertFalse(state.enabled)
        assertTrue(state.categories.containsAll(PrivacyCategory.entries))
        assertFalse(state.protects(PrivacyCategory.SECRETS))
        assertTrue(state.copy(enabled = true).protects(PrivacyCategory.SECRETS))
    }

    @Test
    fun privateGatewayRequiresLocalOrTailscaleTargetNotUserInfo() {
        assertTrue(PrivacyProfileEligibility.privateGateway("http://127.0.0.1:8080"))
        assertTrue(PrivacyProfileEligibility.privateGateway("https://laptop.tailnet.ts.net:443"))
        assertFalse(PrivacyProfileEligibility.privateGateway("https://example.com"))
        assertFalse(PrivacyProfileEligibility.privateGateway("http://username:pass@127.0.0.1:8080"))
        assertFalse(PrivacyProfileEligibility.privateGateway("https://192.168.0.999:8080"))
    }
}
