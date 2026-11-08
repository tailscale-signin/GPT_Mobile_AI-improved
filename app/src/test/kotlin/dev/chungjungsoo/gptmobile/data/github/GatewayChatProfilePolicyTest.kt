package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayChatProfilePolicyTest {
    private val gateway = PlatformV2(uid = "gateway", name = "Gateway", compatibleType = ClientType.LLAMA, apiUrl = "http://192.168.1.4:8090", model = "selected-model")

    @Test fun providerFamilyAndEndpointAloneNeverGrantGatewaySupport() {
        val ordinaryOllama = gateway.copy(uid = "ordinary", compatibleType = ClientType.OLLAMA)
        assertTrue(GatewayChatProfilePolicy.configured(gateway))
        assertTrue(GatewayChatProfilePolicy.configured(ordinaryOllama))
        assertTrue(GatewayChatProfilePolicy.eligible(listOf(gateway, ordinaryOllama), emptySet()).isEmpty())
    }

    @Test fun knownProfilesRemainEligibleWithoutAReadinessObservation() {
        val second = gateway.copy(uid = "second", name = "Second gateway", compatibleType = ClientType.OLLAMA)
        assertEquals(listOf(gateway, second), GatewayChatProfilePolicy.eligible(listOf(gateway, second), setOf(gateway.uid, second.uid)))
        assertEquals(listOf(second), GatewayChatProfilePolicy.eligible(listOf(gateway, second), setOf(second.uid)))
    }

    @Test fun disabledMissingModelAmbiguousIdentityAndChangedProviderAreExcluded() {
        val invalid = listOf(
            gateway.copy(enabled = false),
            gateway.copy(model = ""),
            gateway.copy(model = " model"),
            gateway.copy(apiUrl = "https://user:secret@example.com"),
            gateway.copy(apiUrl = "http://192.168.1.4:8090?token=secret"),
            gateway.copy(compatibleType = ClientType.OPENAI),
            gateway.copy(apiUrl = "https://public.example.com")
        )
        invalid.forEach {
            assertFalse(GatewayChatProfilePolicy.configured(it))
            assertTrue(GatewayChatProfilePolicy.eligible(listOf(it), setOf(it.uid)).isEmpty())
        }
        assertTrue(GatewayChatProfilePolicy.eligible(listOf(gateway, gateway.copy(name = "Duplicate")), setOf(gateway.uid)).isEmpty())
    }
}
