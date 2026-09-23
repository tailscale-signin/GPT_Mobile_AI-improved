package dev.chungjungsoo.gptmobile.util

import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder

internal fun platformTimeoutSecondsToSocketTimeoutMillis(timeoutSeconds: Int): Long? = when {
    timeoutSeconds <= 0 -> null
    // OkHttp stores finite read/write timeouts in signed Int milliseconds.
    // Older settings and imports may contain any positive Int number of seconds.
    else -> (timeoutSeconds * 1_000L).coerceAtMost(Int.MAX_VALUE.toLong())
}

/**
 * Configures timeouts for streaming SSE / chat completions.
 * Sets requestTimeoutMillis to INFINITE_TIMEOUT_MS so that long generations, reasoning chains,
 * and multi-step tool sessions are never prematurely aborted by a fixed request deadline.
 * Socket inactivity timeout is bound to [timeoutSeconds] (defaulting to 90s if disabled)
 * so idle or severed TCP connections are still promptly detected.
 */
internal fun HttpRequestBuilder.applyPlatformStreamingTimeout(timeoutSeconds: Int) {
    val socketTimeout = platformTimeoutSecondsToSocketTimeoutMillis(timeoutSeconds) ?: 90_000L
    timeout {
        this.requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        this.socketTimeoutMillis = socketTimeout
        this.connectTimeoutMillis = 30_000L
    }
}

internal fun formatPlatformTimeout(timeoutSeconds: Int, offLabel: String): String = when {
    timeoutSeconds <= 0 -> offLabel
    else -> "$timeoutSeconds sec"
}
