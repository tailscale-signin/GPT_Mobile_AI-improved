package dev.chungjungsoo.gptmobile.util

import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.HttpRequestBuilder
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlatformTimeoutTest {

    @Test
    fun `platform timeout maps to socket timeout millis`() {
        assertEquals(30_000L, platformTimeoutSecondsToSocketTimeoutMillis(30))
    }

    @Test
    fun `streaming timeout raises short values to five minute stability floor`() {
        val request = HttpRequestBuilder().apply { applyPlatformStreamingTimeout(30) }
        val config = checkNotNull(request.getCapabilityOrNull(HttpTimeoutCapability))

        assertEquals(300_000L, config.socketTimeoutMillis)
        assertEquals(60_000L, config.connectTimeoutMillis)
        assertEquals(HttpTimeoutConfig.INFINITE_TIMEOUT_MS, config.requestTimeoutMillis)
    }

    @Test
    fun `zero platform timeout disables socket timeout`() {
        assertNull(platformTimeoutSecondsToSocketTimeoutMillis(0))
    }

    @Test
    fun `oversized saved timeout is safe for the OkHttp engine`() {
        val request = HttpRequestBuilder().apply { applyPlatformStreamingTimeout(Int.MAX_VALUE) }
        val config = checkNotNull(request.getCapabilityOrNull(HttpTimeoutCapability))
        val client = OkHttpClient.Builder()
            .connectTimeout(checkNotNull(config.connectTimeoutMillis), TimeUnit.MILLISECONDS)
            .readTimeout(checkNotNull(config.socketTimeoutMillis), TimeUnit.MILLISECONDS)
            .writeTimeout(checkNotNull(config.socketTimeoutMillis), TimeUnit.MILLISECONDS)
            .build()

        assertEquals(Int.MAX_VALUE, client.readTimeoutMillis)
        assertEquals(Int.MAX_VALUE, client.writeTimeoutMillis)
        assertEquals(60_000, client.connectTimeoutMillis)
        assertEquals(HttpTimeoutConfig.INFINITE_TIMEOUT_MS, config.requestTimeoutMillis)
    }

    @Test
    fun `conversion preserves valid timeout at OkHttp boundary and clamps the next second`() {
        assertEquals(2_147_483_000L, platformTimeoutSecondsToSocketTimeoutMillis(2_147_483))
        assertEquals(Int.MAX_VALUE.toLong(), platformTimeoutSecondsToSocketTimeoutMillis(2_147_484))
    }

    @Test
    fun `disabled or negative timeout uses five minute idle floor and unlimited generation time`() {
        for (seconds in listOf(0, -1, Int.MIN_VALUE)) {
            val request = HttpRequestBuilder().apply { applyPlatformStreamingTimeout(seconds) }
            val config = checkNotNull(request.getCapabilityOrNull(HttpTimeoutCapability))
            assertEquals(300_000L, config.socketTimeoutMillis)
            assertEquals(HttpTimeoutConfig.INFINITE_TIMEOUT_MS, config.requestTimeoutMillis)
        }
    }

    @Test
    fun `formatPlatformTimeout shows off when disabled`() {
        assertEquals("Off", formatPlatformTimeout(0, "Off"))
    }
}
