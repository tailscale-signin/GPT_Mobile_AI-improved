package dev.chungjungsoo.gptmobile.data.amazon

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazonPublicHistoryProviderTest {
    @Test fun preloadsKeepEveryCarouselChartCachedInsteadOfEvictingAfterEightProducts() = runBlocking {
        val requests = AtomicInteger()
        client(
            MockEngine {
                requests.incrementAndGet()
                respond(png, HttpStatusCode.OK, headersOf("Content-Type", "image/png"))
            }
        ).use { http ->
            val provider = AmazonPublicHistoryProvider(http)
            val asins = (1..30).map { "B" + it.toString().padStart(9, '0') }
            asins.map { asin -> async { provider.fetch("amazon.ca", asin) { true } } }.awaitAll()
            asins.forEach { assertNotNull(provider.fetch("amazon.ca", it) { true }.png) }
            assertEquals(30, requests.get())
        }
    }

    @Test fun unrelatedChartsLoadConcurrentlyButAtMostThreeNetworkReadsRun() = runBlocking {
        val started = Channel<String>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        val requests = AtomicInteger()
        client(
            MockEngine { request ->
                requests.incrementAndGet()
                started.send(requireNotNull(request.url.parameters["asin"]))
                release.await()
                respond(png, HttpStatusCode.OK, headersOf("Content-Type", "image/png"))
            }
        ).use { http ->
            val provider = AmazonPublicHistoryProvider(http)
            val pending = (1..5).map { index -> async { provider.fetch("amazon.ca", "B" + index.toString().padStart(9, '0')) { true } } }
            withTimeout(5000) { repeat(3) { started.receive() } }
            assertEquals(3, requests.get())
            release.complete(Unit)
            withTimeout(5000) { pending.awaitAll() }
            assertEquals(5, requests.get())
        }
    }

    @Test fun openingAProductDuringItsPreloadSharesTheSameNetworkRead() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val requests = AtomicInteger()
        client(
            MockEngine {
                requests.incrementAndGet()
                started.complete(Unit)
                release.await()
                respond(png, HttpStatusCode.OK, headersOf("Content-Type", "image/png"))
            }
        ).use { http ->
            val provider = AmazonPublicHistoryProvider(http)
            val preload = async { provider.fetch("amazon.ca", "B000000001") { true } }
            withTimeout(5000) { started.await() }
            val open = async { provider.fetch("amazon.ca", "B000000001") { true } }
            release.complete(Unit)
            assertNotNull(withTimeout(5000) { preload.await() }.png)
            assertNotNull(withTimeout(5000) { open.await() }.png)
            assertEquals(1, requests.get())
        }
    }

    @Test fun emptyHistoryBannerWithSuccessfulHttpStatusStillFallsBack() = runBlocking {
        val banner = png.copyOf().apply { java.nio.ByteBuffer.wrap(this, 16, 8).putInt(500).putInt(200) }
        client(
            MockEngine { request ->
                respond(if (request.url.host == "graph.keepa.com") banner else png, HttpStatusCode.OK, headersOf("Content-Type", "image/png"))
            }
        ).use { http ->
            assertEquals("camelcamelcamel", AmazonPublicHistoryProvider(http).fetch("amazon.com", "B000000001") { true }.provider)
        }
    }
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
