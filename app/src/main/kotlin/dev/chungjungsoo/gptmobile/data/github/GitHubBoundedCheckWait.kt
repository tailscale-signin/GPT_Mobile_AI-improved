package dev.chungjungsoo.gptmobile.data.github

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** One caller-requested wait has a hard deadline; an unfinished check is a pending result. */
internal object GitHubBoundedCheckWait {
    const val MAX_SECONDS = 30
    suspend fun await(
        seconds: Int,
        now: () -> Long = { System.nanoTime() / 1_000_000 },
        observe: suspend () -> JsonObject
    ): JsonObject {
        require(seconds in 1..MAX_SECONDS) { "wait_timeout_seconds must be 1..$MAX_SECONDS" }
        val started = now()
        val limit = seconds * 1000L
        var latest: JsonObject? = null
        var observations = 0
        var complete = false
        withTimeoutOrNull(limit) {
            while (!complete) {
                latest = observe()
                observations++
                val status = (latest["status"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                complete = status in setOf("PASSED", "FAILED", "SKIPPED", "BLOCKED", "STALE", "CANCELLED", "EXPIRED", "MERGED", "CLOSED")
                val remaining = limit - (now() - started).coerceAtLeast(0)
                if (complete || remaining <= 0) break
                delay(minOf(5000L, remaining))
            }
        }
        return buildJsonObject {
            latest?.forEach { (key, value) -> put(key, value) }
            latest?.get("status")?.let { put("observed_status", it) }
            if (!complete) put("status", "PENDING_CHECKS")
            put("complete", complete)
            put("wait_outcome", if (complete) "TERMINAL_OBSERVED" else "PENDING_TIMEOUT")
            put("wait_timeout_seconds", seconds)
            put("observations", observations)
            put("wait_elapsed_ms", (now() - started).coerceAtLeast(0))
            put("monitoring_transport", "BOUNDED_INLINE_POLLING")
            put("model_polling_required", false)
            put("device_test_execution", "NOT_TESTED")
        }
    }
}
