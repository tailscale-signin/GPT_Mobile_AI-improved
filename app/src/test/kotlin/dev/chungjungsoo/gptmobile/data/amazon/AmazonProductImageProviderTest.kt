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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazonProductImageProviderTest {
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
    private val url = "https://m.media-amazon.com/images/I/product.png"
    private fun client(engine: MockEngine) = HttpClient(engine) {
        followRedirects = false
        install(HttpTimeout)
    }

    @Test fun onlyAmazonCdnImagesAreFetchedAndCachedPermissionsAreRechecked() = runBlocking {
        var requests = 0
        client(
            MockEngine { request ->
                requests++
                assertEquals("m.media-amazon.com", request.url.host)
                assertNull(request.headers["Authorization"])
                assertNull(request.headers["Cookie"])
                respond(png, HttpStatusCode.OK, headersOf("Content-Type", "image/png"))
            }
        ).use { http ->
            val images = AmazonProductImageProvider(http)
            assertNull(images.fetch("https://example.com/product.png") { true })
            assertNotNull(images.fetch(url) { true })
            assertNotNull(images.fetch(url) { true })
            assertTrue(runCatching { images.fetch(url) { false } }.isFailure)
            assertEquals(1, requests)
        }
    }

    @Test fun redirectsChallengesAndInvalidImagesAreOmitted() = runBlocking {
        listOf(
            Triple(HttpStatusCode.Found, "image/png", png),
            Triple(HttpStatusCode.OK, "text/html", "<html>Challenge</html>".toByteArray()),
            Triple(HttpStatusCode.OK, "image/png", "invalid image".toByteArray())
        ).forEach { (status, type, bytes) ->
            client(MockEngine { respond(bytes, status, headersOf("Content-Type" to listOf(type), "Location" to listOf("https://example.com/redirect"))) }).use { http ->
                assertNull(AmazonProductImageProvider(http).fetch(url) { true })
            }
        }
    }

    @Test fun declaredAndStreamedOversizeImagesAreOmitted() = runBlocking {
        client(MockEngine { respond(png, HttpStatusCode.OK, headersOf("Content-Type" to listOf("image/png"), "Content-Length" to listOf("4000000"))) }).use { http ->
            assertNull(AmazonProductImageProvider(http).fetch(url) { true })
        }
        client(MockEngine { respond(png.copyOf(3 * 1_048_576 + 1), HttpStatusCode.OK, headersOf("Content-Type", "image/png")) }).use { http ->
            assertNull(AmazonProductImageProvider(http).fetch(url) { true })
        }
    }

    @Test fun revokedPermissionDoesNotCacheAnImageThatFinishedDownloading() = runBlocking {
        var allowed = true
        var requests = 0
        client(
            MockEngine {
                requests++
                allowed = false
                respond(png, HttpStatusCode.OK, headersOf("Content-Type", "image/png"))
            }
        ).use { http ->
            val images = AmazonProductImageProvider(http)
            assertTrue(runCatching { images.fetch(url) { allowed } }.isFailure)
            allowed = true
            assertTrue(runCatching { images.fetch(url) { allowed } }.isFailure)
            assertEquals(2, requests)
        }
    }
}
