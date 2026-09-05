package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.dto.buildEndpoint
import io.ktor.client.plugins.logging.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkClientTest {

    @Test
    fun `network logging avoids request body logging`() {
        assertEquals(LogLevel.HEADERS, NetworkClient.resolveNetworkLogLevel())
    }

    @Test
    fun `sensitive provider credential headers are sanitized case insensitively`() {
        assertTrue(NetworkClient.isSensitiveHeader("Authorization"))
        assertTrue(NetworkClient.isSensitiveHeader("authorization"))
        assertTrue(NetworkClient.isSensitiveHeader("x-goog-api-key"))
        assertTrue(NetworkClient.isSensitiveHeader("X-Goog-Api-Key"))
        assertTrue(NetworkClient.isSensitiveHeader("x-api-key"))
        assertTrue(NetworkClient.isSensitiveHeader("X-API-KEY"))
        assertTrue(NetworkClient.isSensitiveHeader("Mcp-Session-Id"))
        assertTrue(NetworkClient.isSensitiveHeader("mcp-session-id"))
        assertFalse(NetworkClient.isSensitiveHeader("Content-Type"))
    }

    @Test
    fun `anthropic credential header is sanitized case insensitively`() {
        assertTrue(NetworkClient.isSensitiveHeader("x-api-key"))
        assertTrue(NetworkClient.isSensitiveHeader("X-Api-Key"))
    }

    @Test
    fun `provider request config buildEndpoint trims trailing slashes and spaces`() {
        val configWithTrailingSlash = ProviderRequestConfig(
            provider = dev.chungjungsoo.gptmobile.data.model.Provider.OPENAI,
            apiUrl = "https://api.openai.com/v1/  "
        )
        assertEquals("https://api.openai.com/v1/chat/completions", configWithTrailingSlash.buildEndpoint("chat/completions"))
        assertEquals("https://api.openai.com/v1/chat/completions", configWithTrailingSlash.buildEndpoint("/chat/completions"))

        val configClean = ProviderRequestConfig(
            provider = dev.chungjungsoo.gptmobile.data.model.Provider.GROQ,
            apiUrl = "https://api.groq.com/openai/v1"
        )
        assertEquals("https://api.groq.com/openai/v1/models", configClean.buildEndpoint("models"))
    }
}
