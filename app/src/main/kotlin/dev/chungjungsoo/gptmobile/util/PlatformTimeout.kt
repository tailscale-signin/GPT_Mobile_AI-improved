package dev.chungjungsoo.gptmobile.util

import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder

internal const val REMOTE_STREAM_MIN_SOCKET_TIMEOUT_MS = 300_000L
internal const val REMOTE_STREAM_CONNECT_TIMEOUT_MS = 60_000L

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
 * Socket inactivity uses a five-minute floor so slow reasoning, provider queueing,
 * long tool calls, and sparse SSE streams do not get mistaken for dead connections.
 * The total request deadline remains unlimited for streaming responses.
 */
internal fun HttpRequestBuilder.applyPlatformStreamingTimeout(timeoutSeconds: Int, worker: dev.chungjungsoo.gptmobile.data.network.WorkerRequestBudget? = null) {
    val requestedSocketTimeout =
        platformTimeoutSecondsToSocketTimeoutMillis(timeoutSeconds) ?: REMOTE_STREAM_MIN_SOCKET_TIMEOUT_MS
    val socketTimeout = requestedSocketTimeout.coerceAtLeast(REMOTE_STREAM_MIN_SOCKET_TIMEOUT_MS)
    worker?.acquireRequest()
    val local = dev.chungjungsoo.gptmobile.data.model.endpointLocality(url.toString()) != dev.chungjungsoo.gptmobile.data.model.EndpointLocality.EXTERNAL
    val remaining = worker?.remainingMillis()
    timeout {
        this.requestTimeoutMillis = remaining ?: HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        this.socketTimeoutMillis = remaining?.let { minOf(socketTimeout, it) } ?: socketTimeout
        this.connectTimeoutMillis = minOf(if (local) 5_000L else REMOTE_STREAM_CONNECT_TIMEOUT_MS, remaining ?: Long.MAX_VALUE)
    }
}

internal fun formatPlatformTimeout(timeoutSeconds: Int, offLabel: String): String = when {
    timeoutSeconds <= 0 -> offLabel
    else -> "$timeoutSeconds sec"
}
