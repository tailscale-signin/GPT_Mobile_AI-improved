package dev.chungjungsoo.gptmobile.data.network

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Utility functions for Server-Sent Events (SSE) stream parsing and resilient JSON chunk extraction.
 */
object SseUtils {
    private const val DATA_PREFIX = "data:"
    const val DONE_SENTINEL = "[DONE]"

    /**
     * Extracts and trims the payload from an SSE data line.
     *
     * Per W3C/WHATWG SSE specification:
     * - A line starting with "data:" contains a payload.
     * - If the character immediately following the colon is a space (U+0020), it is ignored.
     * - Lines not starting with "data:" (e.g. comments starting with ":", "event:", or empty lines)
     *   are ignored and return null.
     *
     * This implementation avoids intermediate string allocations from [removePrefix] and trims
     * leading/trailing whitespace efficiently.
     *
     * @param line The raw line from the SSE byte read channel.
     * @return The trimmed data payload, or null if the line is not a data field or empty.
     */
    fun extractSseData(line: String): String? {
        if (!line.startsWith(DATA_PREFIX)) {
            return null
        }

        var start = DATA_PREFIX.length
        val end = line.length

        // Skip leading whitespace after "data:"
        while (start < end && line[start] == ' ') {
            start++
        }

        // Skip trailing whitespace
        var trailing = end
        while (trailing > start && line[trailing - 1] == ' ') {
            trailing--
        }

        if (start >= trailing) {
            return null
        }

        return line.substring(start, trailing)
    }

    /**
     * Determines whether an SSE payload string indicates stream completion ([DONE]).
     */
    fun isDone(payload: String): Boolean {
        return payload.trim() == DONE_SENTINEL
    }

    /**
     * Safely parses a JSON payload chunk into type [T], gracefully recovering from partial,
     * truncated, or malformed chunks that can occur over flaky connections or network gateways.
     *
     * @param json The Json serializer instance to use.
     * @param payload The raw string payload extracted from an SSE line.
     * @param onParseError Optional callback invoked when parsing fails for diagnostic logging.
     * @return Deserialized [T] or null if parsing fails or payload is the [DONE] sentinel.
     */
    inline fun <reified T> safeParseChunk(
        json: Json,
        payload: String,
        onParseError: ((Throwable, String) -> Unit)? = null
    ): T? {
        if (isDone(payload)) return null
        return try {
            json.decodeFromString<T>(payload)
        } catch (e: SerializationException) {
            onParseError?.invoke(e, payload)
            null
        } catch (e: IllegalArgumentException) {
            onParseError?.invoke(e, payload)
            null
        }
    }
}
