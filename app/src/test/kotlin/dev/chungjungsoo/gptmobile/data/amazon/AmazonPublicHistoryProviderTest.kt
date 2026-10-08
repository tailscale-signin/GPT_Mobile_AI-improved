package dev.chungjungsoo.gptmobile.data.amazon

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazonPublicHistoryProviderTest {
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
    private fun client(engine: MockEngine) = HttpClient(engine) {
        followRedirects = false
        install(HttpTimeout)
    }

    @Test fun publicChartUsesFixedHostsWithoutCredentialsAndRechecksCachedPermissions() = runBlocking {
        var requests = 0
        client(
            MockEngine { request ->
                requests++
                assertEquals("https", request.url.protocol.name)
                assertEquals("graph.keepa.com", request.url.host)
                assertEquals("B000000001", request.url.parameters["asin"])
                assertEquals("ca", request.url.parameters["domain"])
                assertTrue(request.headers["Authorization"] == null && request.headers["Cookie"] == null)
                respond(png, HttpStatusCode.OK, headersOf("Content-Type", "image/png"))
            }
        ).use { http ->
            val provider = AmazonPublicHistoryProvider(http)
            assertNotNull(provider.fetch("amazon.ca", "B000000001") { true }.png)
            assertEquals("Keepa", provider.fetch("amazon.ca", "B000000001") { true }.provider)
            val denied = runCatching { provider.fetch("amazon.ca", "B000000001") { false } }
            assertTrue(denied.isFailure)
            assertEquals(1, requests)
        }
    }

    @Test fun blockedPrimaryUsesCamelChartWithoutFollowingRedirectsOrInventingSeries() = runBlocking {
        val hosts = mutableListOf<String>()
        client(
            MockEngine { request ->
                hosts += request.url.host
                if (request.url.host == "graph.keepa.com") {
                    respond("", HttpStatusCode.Found, headersOf("Location", "https://untrusted.example/chart"))
                } else {
                    respond(png, HttpStatusCode.OK, headersOf("Content-Type", "image/png"))
                }
            }
        ).use { http ->
            val history = AmazonPublicHistoryProvider(http).fetch("amazon.com", "B000000001") { true }
            assertEquals(listOf("graph.keepa.com", "charts.camelcamelcamel.com"), hosts)
            assertEquals("camelcamelcamel", history.provider)
            assertEquals("false", history.toJson()["numericSeriesAvailable"].toString())
        }
    }

    @Test fun challengeHtmlAndInvalidImagesNeverBecomeHistoricalPrices() = runBlocking {
        client(MockEngine { respond("<html>Challenge</html>", HttpStatusCode.OK, headersOf("Content-Type", "text/html")) }).use { http ->
            val history = AmazonPublicHistoryProvider(http).fetch("amazon.com", "B000000001") { true }
            assertTrue(history.png == null)
            assertNotNull(history.notice)
        }
        assertTrue(AmazonPublicHistoryProvider.validPng(png))
        assertFalse(AmazonPublicHistoryProvider.validPng(png.copyOf(10)))
        assertFalse(AmazonPublicHistoryProvider.validPng(png.copyOf().apply { this[12] = 0 }))
        assertFalse(AmazonPublicHistoryProvider.validPng(png.copyOf().apply { this[16] = 127 }))
    }
}
