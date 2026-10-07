package dev.chungjungsoo.gptmobile.data.amazon

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SerpApiAmazonClientTest {
    @Test
    fun `native requests use only the fixed HTTPS endpoint and correct key parameter`() = runBlocking {
        var requests = 0
        val engine = MockEngine { request ->
            requests++
            assertEquals("serpapi.com", request.url.host)
            assertEquals("https", request.url.protocol.name)
            assertEquals("/search", request.url.encodedPath)
            assertEquals("test-key", request.url.parameters["api_key"])
            assertEquals("amazon", request.url.parameters["engine"])
            assertEquals("amazon.ca", request.url.parameters["amazon_domain"])
            assertEquals("headphones", request.url.parameters["k"])
            respond("{\"organic_results\":[]}", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        HttpClient(engine).use { http ->
            val client = SerpApiAmazonClient(http, "test-key")
            repeat(2) { assertTrue(client.fetch(mapOf("engine" to "amazon", "amazon_domain" to "amazon.ca", "k" to "headphones")).containsKey("organic_results")) }
        }
        assertEquals(2, requests)
    }

    @Test
    fun `redirects cannot send the key to another host and error bodies are never returned`() = runBlocking {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond("api_key=test-key", HttpStatusCode.Found, headersOf("Location", "https://evil.example/search"))
        }
        HttpClient(engine).use { http ->
            val failure = runCatching { SerpApiAmazonClient(http, "test-key").fetch(mapOf("engine" to "amazon")) }.exceptionOrNull()
            assertTrue(failure is AmazonProviderException)
            assertFalse(failure?.message.orEmpty().contains("test-key"))
        }
        assertEquals(1, requests)
    }

    @Test
    fun `missing key performs no request and oversized responses fail safely`() = runBlocking {
        var requests = 0
        HttpClient(
            MockEngine {
                requests++
                respond("x".repeat(1_500_001))
            }
        ).use { http ->
            assertTrue(runCatching { SerpApiAmazonClient(http, "").fetch(emptyMap()) }.exceptionOrNull() is AmazonProviderException)
            assertEquals(0, requests)
            assertTrue(runCatching { SerpApiAmazonClient(http, "test-key").fetch(emptyMap()) }.exceptionOrNull()?.message.orEmpty().contains("size limit"))
            assertEquals(1, requests)
        }
    }
}
