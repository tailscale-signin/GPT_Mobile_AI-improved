package dev.chungjungsoo.gptmobile.data.readiness

import dev.chungjungsoo.gptmobile.data.model.ClientType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConfiguredInferenceRouteTest {
    @Test fun phoneInferenceDoesNotRequireANetworkEndpoint() {
        assertEquals(ConfiguredInferenceRoute.PHONE, configuredInferenceRoute(ClientType.LITERT_LM, ""))
        assertFalse(ConfiguredInferenceRoute.PHONE.detail.contains("private", ignoreCase = true))
    }

    @Test fun providerNameAndPrivateAddressDoNotProveExecutionLocation() {
        assertEquals(ConfiguredInferenceRoute.NETWORK, configuredInferenceRoute(ClientType.OPENAI, "http://192.168.1.10:8090/v1"))
        assertEquals(ConfiguredInferenceRoute.NETWORK, configuredInferenceRoute(ClientType.OLLAMA, "https://models.example.test/v1"))
        assertEquals(ConfiguredInferenceRoute.NETWORK, configuredInferenceRoute(ClientType.LLAMA, "http://localhost:8080/v1"))
    }

    @Test fun onlyExactKnownApiHostsUseTheCloudLabel() {
        assertEquals(ConfiguredInferenceRoute.CLOUD, configuredInferenceRoute(ClientType.OPENAI, "https://API.OPENAI.COM/v1"))
        assertEquals(ConfiguredInferenceRoute.CLOUD, configuredInferenceRoute(ClientType.OPENROUTER, "https://openrouter.ai/api/v1"))
        assertEquals(ConfiguredInferenceRoute.NETWORK, configuredInferenceRoute(ClientType.OPENAI, "https://api.openai.com.example.test/v1"))
        assertEquals(ConfiguredInferenceRoute.NETWORK, configuredInferenceRoute(ClientType.CUSTOM, "https://proxy.example.test/openrouter.ai"))
    }

    @Test fun gatewayIdentityIsRequiredAndDoesNotImplyPrivateInference() {
        val url = "http://192.168.1.10:8090/v1"
        assertEquals(ConfiguredInferenceRoute.NETWORK, configuredInferenceRoute(ClientType.LLAMA, url))
        val observed = configuredInferenceRoute(ClientType.LLAMA, url, gatewayIdentityObserved = true)
        assertEquals(ConfiguredInferenceRoute.GATEWAY, observed)
        assertFalse(observed.label.contains("private", ignoreCase = true))
        assertFalse(observed.label.contains("ready", ignoreCase = true))
    }

    @Test fun malformedEndpointsAndEmbeddedCredentialsNeverBecomeCloudLabels() {
        for (url in listOf("", "api.openai.com/v1", "file:///models", "https://secret@api.openai.com/v1", "not a URI")) {
            val result = configuredInferenceRoute(ClientType.OPENAI, url, gatewayIdentityObserved = true)
            assertEquals(ConfiguredInferenceRoute.MISSING, result)
            assertFalse(result.label.contains("secret"))
            assertFalse(result.detail.contains("secret"))
        }
    }
}
