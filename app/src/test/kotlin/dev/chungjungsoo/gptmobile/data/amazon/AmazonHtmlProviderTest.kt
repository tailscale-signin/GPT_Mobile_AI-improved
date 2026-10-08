package dev.chungjungsoo.gptmobile.data.amazon

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class AmazonHtmlProviderTest {
    @get:Rule val folder = TemporaryFolder()
    private val clock = AmazonMutableClock()
    private val budget by lazy { AmazonRequestBudget(File(folder.root, "budget.json"), clock) }
    private val market = AmazonFreeMarket.CANADA
    private val context = AmazonReadContext(45, 100) { true }
    private val request = AmazonSearchRequest("headphones & audio", market)
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/amazon/$name.html")).readText()
    private fun http(engine: MockEngine) = AmazonHtmlProvider.createClient(object : HttpClientEngineFactory<HttpClientEngineConfig> {
        override fun create(block: HttpClientEngineConfig.() -> Unit): HttpClientEngine {
            HttpClientEngineConfig().apply(block)
            return engine
        }
    })

    @Test
    fun searchUsesFixedHttpsHostAndEncodedQueryWithoutCredentials() = runBlocking {
        var calls = 0
        http(
            MockEngine { req ->
                calls++
                assertEquals("https", req.url.protocol.name)
                assertEquals("www.amazon.ca", req.url.host)
                assertEquals("headphones & audio", req.url.parameters["k"])
                assertEquals("/s", req.url.encodedPath)
                assertTrue(req.headers["Authorization"] == null && req.headers["Cookie"] == null)
                clock.advance(6_000)
                respond(fixture("search-ca"), HttpStatusCode.OK, headersOf("Content-Type", "text/html; charset=UTF-8"))
            }
        ).use { client ->
            val result = AmazonHtmlProvider(client, budget, clock).search(request, context)
            assertEquals(2, result.products.size)
            assertEquals(clock.instant(), result.products.first().acquiredAt)
        }
        assertEquals(1, calls)
    }

    @Test
    fun crossHostAndLoginRedirectsAreRejectedBeforeSecondRequest() = runBlocking {
        for (url in listOf("https://evil.example/dp/B000000001", "https://www.amazon.com/s?k=x", "/ap/signin")) {
            var calls = 0
            http(
                MockEngine {
                    calls++
                    clock.advance(6_000)
                    respond("private raw page", HttpStatusCode.Found, headersOf("Location", url))
                }
            ).use { client ->
                assertCode(AmazonReadError.PARSE_CHANGED) { AmazonHtmlProvider(client, budget, clock).search(request, context) }
            }
            assertEquals(1, calls)
        }
    }

    @Test
    fun redirectsConsumePhysicalRequestAllowanceAndDoNotBypassQuota() = runBlocking {
        var calls = 0
        http(
            MockEngine {
                calls++
                clock.advance(6_000)
                respond("", HttpStatusCode.Found, headersOf("Location", "/s?k=redirect"))
            }
        ).use { client ->
            assertCode(AmazonReadError.QUOTA_EXCEEDED) { AmazonHtmlProvider(client, budget, clock).search(request, context.copy(dailyLimit = 1)) }
        }
        assertEquals(1, calls)
    }

    @Test
    fun sameMarketRedirectLoopStopsAfterThreeRedirects() = runBlocking {
        var calls = 0
        http(
            MockEngine {
                calls++
                clock.advance(6_000)
                respond("", HttpStatusCode.Found, headersOf("Location", "/s?k=redirect"))
            }
        ).use { client ->
            assertCode(AmazonReadError.PARSE_CHANGED) { AmazonHtmlProvider(client, budget, clock).search(request, context) }
        }
        assertEquals(4, calls)
    }

    @Test
    fun httpRateLimitCreatesCooldownWithoutAutomaticRetry() = runBlocking {
        var calls = 0
        http(
            MockEngine {
                calls++
                clock.advance(6_000)
                respond("private raw rate-limit body", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "120"))
            }
        ).use { client ->
            val provider = AmazonHtmlProvider(client, budget, clock)
            assertCode(AmazonReadError.RATE_LIMITED) { provider.search(request, context) }
            assertCode(AmazonReadError.COOLDOWN_ACTIVE) { provider.search(request, context) }
        }
        assertEquals(1, calls)
    }

    @Test
    fun parentTimeoutRemainsCancellation() = runBlocking {
        http(MockEngine { awaitCancellation() }).use { client ->
            val failure = runCatching { withTimeout(100) { AmazonHtmlProvider(client, budget, clock).search(request, context) } }.exceptionOrNull()
            assertTrue(failure is TimeoutCancellationException)
        }
    }

    @Test
    fun decodedBodyLimitStopsCompressedOversizedHtml() = runBlocking {
        val bytes = ByteArrayOutputStream().apply { GZIPOutputStream(this).use { it.write("x".repeat(AmazonHtmlProvider.MAX_HTML_BYTES + 1).toByteArray()) } }.toByteArray()
        http(
            MockEngine {
                clock.advance(6_000)
                respond(ByteReadChannel(bytes), HttpStatusCode.OK, headersOf("Content-Type" to listOf("text/html"), "Content-Encoding" to listOf("gzip")))
            }
        ).use { client ->
            assertCode(AmazonReadError.RESPONSE_TOO_LARGE) { AmazonHtmlProvider(client, budget, clock).search(request, context) }
        }
    }

    @Test
    fun robotCheckAtHttp200StopsSubsequentFetches() = runBlocking {
        var calls = 0
        http(
            MockEngine {
                calls++
                clock.advance(6_000)
                respond("<title>Robot Check</title>", HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
            }
        ).use { client ->
            val provider = AmazonHtmlProvider(client, budget, clock)
            assertCode(AmazonReadError.CHALLENGE_REQUIRED) { provider.search(request, context) }
            assertCode(AmazonReadError.COOLDOWN_ACTIVE) { provider.search(request, context) }
        }
        assertEquals(1, calls)
    }

    @Test
    fun batchDetailsRetainSuccessfulFactsWhenLaterTransportFails() = runBlocking {
        http(
            MockEngine { req ->
                clock.advance(6_000)
                if (req.url.encodedPath.endsWith("B000000002")) throw IllegalStateException("private raw response https://secret.example")
                respond(fixture("product-ca"), HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
            }
        ).use { client ->
            val result = AmazonHtmlProvider(client, budget, clock).products(AmazonProductRequest(listOf("B000000001", "B000000002"), market), context)
            assertEquals(1, result.products.size)
            assertEquals("B000000002", result.errors.single().asin)
            assertEquals(AmazonReadError.NETWORK_ERROR, result.errors.single().code)
            assertFalse(result.toJson("partial", market).toString().contains("secret.example"))
        }
    }

    @Test
    fun nonHtmlResponseAndUnsupportedUrlNeverBecomeProductFacts() = runBlocking {
        http(
            MockEngine {
                clock.advance(6_000)
                respond("private", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        ).use { client ->
            assertCode(AmazonReadError.PARSE_CHANGED) { AmazonHtmlProvider(client, budget, clock).search(request, context) }
        }
        for (url in listOf("http://www.amazon.ca/s", "https://user@amazon.ca/s", "https://www.amazon.ca:444/s", "https://www.amazon.ca/s#fragment", "https://www.amazon.ca/ap/signin", "https://amazon.ca.evil.example/s")) {
            assertFalse(AmazonHtmlProvider.validPageUrl(url, market))
        }
    }

    private suspend fun assertCode(code: AmazonReadError, block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull() as? AmazonReadException
        assertEquals(code, failure?.code)
    }
}
