package dev.chungjungsoo.gptmobile.data.github

import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class GitHubLiveProcess(val id: String, val title: String, val status: String, val started: Long?, val completed: Long?, val steps: String = "")
internal data class GitHubLiveStatus(val rows: List<GitHubLiveProcess> = emptyList(), val error: String? = null, val checkedAt: Long? = null)

internal fun liveProcess(value: JsonObject, prefix: String = ""): GitHubLiveProcess {
    fun text(key: String) = (value[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    fun time(key: String) = runCatching { Instant.parse(text(key)).toEpochMilli() }.getOrNull()
    val steps = (value["steps"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val done = steps.count { (it["status"] as? JsonPrimitive)?.content == "completed" }
    val current = steps.firstOrNull { (it["status"] as? JsonPrimitive)?.content == "in_progress" }
    val status = if (text("status") == "completed") text("conclusion").ifBlank { "unknown" } else text("status").ifBlank { "unknown" }
    return GitHubLiveProcess(
        prefix + text("id"),
        text("display_title").ifBlank { text("name") },
        status,
        time("started_at") ?: time("run_started_at") ?: time("created_at"),
        time("completed_at"),
        if (steps.isEmpty()) "" else "$done/${steps.size} steps" + ((current?.get("name") as? JsonPrimitive)?.contentOrNull?.let { " · $it" } ?: "")
    )
}
