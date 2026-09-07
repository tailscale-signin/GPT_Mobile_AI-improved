package dev.melo.gptmobile.improved.util

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.timeout
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line

/**
 * Configure timeout for platform streaming requests.
 */
fun HttpRequestBuilder.applyPlatformStreamingTimeout(timeoutSeconds: Int) {
    timeout {
        requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MILLIS
        socketTimeoutMillis = timeoutSeconds.toLong() * 1000L
    }
}

/**
 * Safe read line extension for ByteReadChannel across Ktor versions.
 */
suspend fun ByteReadChannel.readLine(): String? = this.readUTF8Line()
