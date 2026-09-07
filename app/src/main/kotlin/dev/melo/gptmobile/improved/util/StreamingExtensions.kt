package dev.melo.gptmobile.improved.util

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.plugins.HttpTimeout
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line

/**
 * Configure streaming timeouts on HTTP requests.
 */
fun HttpRequestBuilder.applyPlatformStreamingTimeout(timeoutSeconds: Int = 120) {
    // HttpTimeout.HttpTimeoutCapabilityConfiguration can be set via attributes
    val millis = timeoutSeconds * 1000L
    val pluginConfig = HttpTimeout.HttpTimeoutCapabilityConfiguration(
        requestTimeoutMillis = Long.MAX_VALUE,
        connectTimeoutMillis = millis,
        socketTimeoutMillis = millis
    )
    attributes.put(HttpTimeout.Plugin.key, pluginConfig)
}

/**
 * Read a single UTF-8 line from ByteReadChannel for SSE streaming.
 */
suspend fun ByteReadChannel.readLine(): String? = this.readUTF8Line()
