package dev.melo.gptmobile.improved.network.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Enhanced streaming client with automatic retry, exponential backoff, and connection recovery.
 * Improved reliability for long-running streaming sessions with graceful degradation.
 */
class ResilientStreamingClient @Inject constructor() {
    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Make a streaming request with retry logic.
     */
    suspend fun streamRequest(
        url: String,
        headers: Map<String, String>,
        maxRetries: Int = 3,
        initialBackoffMs: Long = 1000L
    ): Result<StreamingResponse> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        for (attempt in 1..maxRetries) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .headers(headers.toHeaders())
                    .build()

                val response = baseClient.newCall(request).execute()

                if (response.isSuccessful) {
                    return@withContext Result.success(
                        StreamingResponse(
                            body = response.body?.string() ?: "",
                            headers = response.headers.toMap(),
                            statusCode = response.code
                        )
                    )
                } else {
                    // Check if this is a retryable error
                    val isRetryable = response.code in listOf(429, 500, 502, 503, 504)
                    if (isRetryable && attempt < maxRetries) {
                        lastException = Exception("HTTP $response.code")
                        continue
                    }
                }

                return@withContext Result.failure(
                    Exception("HTTP ${response.code}: ${response.message}")
                )
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries) {
                    // Exponential backoff
                    val backoff = initialBackoffMs * (2L.pow(attempt - 1))
                    kotlinx.coroutines.delay(backoff)
                }
            }
        }

        Result.failure(lastException ?: Exception("Max retries exceeded"))
    }

    /**
     * Parse SSE stream from response.
     */
    suspend fun parseSseStream(response: okhttp3.Response): Result<List<SseEvent>> = withContext(Dispatchers.IO) {
        try {
            val body = response.body?.string() ?: return@withContext Result.failure(Exception("No response body"))
            val events = parseSseEvents(body)
            Result.success(events)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseSseEvents(content: String): List<SseEvent> {
        val events = mutableListOf<SseEvent>()
        val lines = content.split("\n").filter { it.isNotBlank() }

        for (line in lines) {
            if (line.startsWith("data:")) {
                val data = line.substring(5).trim()
                if (data.isNotEmpty()) {
                    events.add(SseEvent(data))
                }
            } else if (line == "event:") {
                // Handle event type if needed
            }
        }

        return events
    }

    override fun close() {
        baseClient.dispatcher.executorService.shutdown()
    }
}

/**
 * Streaming response wrapper.
 */
data class StreamingResponse(
    val body: String,
    val headers: Map<String, List<String>>,
    val statusCode: Int
)

/**
 * Server-Sent Event.
 */
data class SseEvent(val data: String)