package dev.chungjungsoo.gptmobile.data.network

import io.ktor.client.plugins.logging.LogLevel
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkClientTest {

    @Test
    fun publicPeersCannotReceiveCleartextAndPrivateGatewaysCan() {
        for (host in listOf("127.0.0.1", "192.168.1.4", "100.64.0.2", "::1", "fd00::2")) {
            assertTrue(host, NetworkClient.isPrivateHttpPeer(java.net.InetAddress.getByName(host)))
        }
        for (host in listOf("203.0.113.1", "0.0.0.0", "224.0.0.1", "2001:db8::1", "100.128.0.1")) {
            assertFalse(host, NetworkClient.isPrivateHttpPeer(java.net.InetAddress.getByName(host)))
        }
        assertFalse(NetworkClient.isPrivateHttpPeer(null))
    }

    @Test
    fun resumableUploadsStayAtTheirAuthorizedOrigin() {
        val start = "https://generativelanguage.googleapis.com/upload/v1beta/files"
        requireSameUploadOrigin(start, "$start?upload_id=one")
        for (target in listOf("https://other.example/upload", "http://generativelanguage.googleapis.com/upload", "https://generativelanguage.googleapis.com:444/upload", "https://user@generativelanguage.googleapis.com/upload")) {
            assertTrue(runCatching { requireSameUploadOrigin(start, target) }.isFailure)
        }
    }

    @Test fun privateReadinessGrantBindsModelOriginMethodAndLifetime() = runTest {
        val endpoint = "http://127.0.0.1:8090/gateway/ready"
        val selected = "$endpoint?model=my-model".toHttpUrl()
        lateinit var queued: okhttp3.Request
        var saved: String? = null
        PrivateGatewayMetadata.withReadiness(endpoint, "my-model") { ticket ->
            saved = ticket
            val request = okhttp3.Request.Builder().url(selected).header(PrivateGatewayMetadata.HEADER, requireNotNull(ticket)).build()
            queued = PrivateGatewayMetadata.bindReadinessGrant(request)
            assertEquals(null, queued.header(PrivateGatewayMetadata.HEADER))
            assertTrue(PrivateGatewayMetadata.allows(queued))
            assertTrue(PrivateGatewayMetadata.allows("GET", selected, ticket))
            assertTrue(PrivateGatewayMetadata.allows("GET", "$selected&autoload=false".toHttpUrl(), ticket))
            assertFalse(PrivateGatewayMetadata.allows("GET", selected, null))
            assertFalse(PrivateGatewayMetadata.allows("POST", selected, ticket))
            for (url in listOf("$selected&extra=x", "$selected&model=my-model", "$selected&autoload=true", "$endpoint?model=other", selected.toString().replace(":8090", ":8091"))) {
                assertFalse(PrivateGatewayMetadata.allows("GET", url.toHttpUrl(), ticket))
            }
        }
        assertFalse(PrivateGatewayMetadata.allows("GET", selected, saved))
        assertFalse(PrivateGatewayMetadata.allows(queued))
    }

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
        assertTrue(NetworkClient.isSensitiveHeader("X-Subscription-Token"))
        assertTrue(NetworkClient.isSensitiveHeader("x-subscription-token"))
        assertTrue(NetworkClient.isSensitiveHeader("Mcp-Session-Id"))
        assertTrue(NetworkClient.isSensitiveHeader("mcp-session-id"))
        assertTrue(NetworkClient.isSensitiveHeader("Proxy-Authorization"))
        assertTrue(NetworkClient.isSensitiveHeader("Cookie"))
        assertTrue(NetworkClient.isSensitiveHeader("Set-Cookie"))
        assertTrue(NetworkClient.isSensitiveHeader("X-Gateway-Admission-Error"))
        assertTrue(NetworkClient.isSensitiveHeader("X-Custom-Provider-Account"))
        assertTrue(NetworkClient.isSensitiveHeader("X-Goog-Upload-URL"))
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
            apiUrl = "https://api.openai.com/v1/  ",
            token = null
        )
        assertEquals("https://api.openai.com/v1/chat/completions", configWithTrailingSlash.buildEndpoint("chat/completions"))
        assertEquals("https://api.openai.com/v1/chat/completions", configWithTrailingSlash.buildEndpoint("/chat/completions"))

        val configClean = ProviderRequestConfig(
            apiUrl = "https://api.groq.com/openai/v1",
            token = null
        )
        assertEquals("https://api.groq.com/openai/v1/models", configClean.buildEndpoint("models"))
    }
}
