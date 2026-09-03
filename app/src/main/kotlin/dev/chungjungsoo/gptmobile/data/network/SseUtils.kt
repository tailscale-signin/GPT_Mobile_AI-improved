package dev.chungjungsoo.gptmobile.data.network

/**
 * Utility functions for Server-Sent Events (SSE) stream parsing.
 */
object SseUtils {
    private const val DATA_PREFIX = "data:"

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
}
