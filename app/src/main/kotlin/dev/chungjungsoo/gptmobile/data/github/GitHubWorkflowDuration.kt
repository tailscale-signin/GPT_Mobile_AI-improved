package dev.chungjungsoo.gptmobile.data.github

import java.time.Instant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Upstream timestamps remain unchanged; inconsistent pairs cannot become a zero duration. */
internal fun githubWorkflowDuration(started: Long?, completed: Long?, status: String, now: Long): String = when {
    started == null -> "Duration unavailable"
    completed != null && completed < started -> "Duration unavailable · inconsistent upstream timestamps"
    completed != null -> "Total ${githubElapsedTime(started, completed)}"
    status in setOf("in_progress", "queued", "waiting", "pending", "requested") && now >= started -> githubElapsedTime(started, now)
    else -> "Duration unavailable"
}

/** Keep the exact upstream values beside a bounded interpretation for model/tool consumers. */
internal fun githubWorkflowTiming(value: JsonObject): JsonObject {
    fun instant(key: String) = runCatching { Instant.parse((value[key] as? JsonPrimitive)?.content) }.getOrNull()
    val started = instant("started_at")
    val completed = instant("completed_at")
    val inconsistent = started != null && completed != null && completed < started
    val duration = if (started == null || completed == null || inconsistent) null else runCatching { java.time.Duration.between(started, completed).toMillis() }.getOrNull()
    return JsonObject(
        value + buildJsonObject {
            put("duration_ms", duration?.let(::JsonPrimitive) ?: JsonNull)
            put(
                "duration_status",
                if (inconsistent) {
                    "INCONSISTENT_UPSTREAM_TIMESTAMPS"
                } else if (duration == null) {
                    "UNAVAILABLE"
                } else {
                    "OBSERVED_TIMESTAMP_INTERVAL"
                }
            )
        }
    )
}
