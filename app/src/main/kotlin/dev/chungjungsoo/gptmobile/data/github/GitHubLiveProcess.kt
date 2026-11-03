package dev.chungjungsoo.gptmobile.data.github

import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class GitHubLiveProcess(val id: String, val title: String, val status: String, val started: Long?, val completed: Long?, val steps: String = "", val runId: Long? = null, val attempt: Int = 1, val headSha: String = "", val url: String = "", val parentId: String? = null, val stepDetails: List<String> = emptyList(), val comparison: String = "")
internal data class GitHubLiveStatus(
    val rows: List<GitHubLiveProcess> = emptyList(),
    val error: String? = null,
    val checkedAt: Long? = null,
    val phase: String = "LOADING",
    val filter: String = "CURRENT",
    val total: Int? = null,
    val hasMore: Boolean = false,
    val expanded: Set<String> = emptySet(),
    val moreJobs: Set<String> = emptySet(),
    val nextCheckAt: Long = 0,
    val metrics: JsonObject? = null,
    val health: List<String> = emptyList(),
    val notice: String? = null
)

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
        if (steps.isEmpty()) "" else "$done/${steps.size} steps" + ((current?.get("name") as? JsonPrimitive)?.contentOrNull?.let { " · $it" } ?: ""),
        runId = text("run_id").toLongOrNull() ?: text("id").toLongOrNull(),
        attempt = text("run_attempt").toIntOrNull() ?: 1,
        headSha = text("head_sha"),
        url = text("html_url"),
        stepDetails = steps.map { step -> listOf("name", "status", "conclusion").mapNotNull { (step[it] as? JsonPrimitive)?.contentOrNull }.joinToString(" · ") }

    )
}

/** Job names are comparable only when unique within each attempt. IDs change on rerun. */
internal fun compareGitHubAttempts(current: List<GitHubLiveProcess>, previous: List<GitHubLiveProcess>, complete: Boolean): String {
    val now = current.groupBy { it.title }
    val before = previous.groupBy { it.title }
    val changes = (now.keys + before.keys).sorted().mapNotNull { name ->
        val a = before[name].orEmpty()
        val b = now[name].orEmpty()
        when {
            a.size > 1 || b.size > 1 -> "$name: ambiguous repeated name; inspect jobs"
            a.isEmpty() -> "$name: only in loaded current jobs"
            b.isEmpty() -> "$name: only in loaded previous jobs"
            a.single().status != b.single().status -> "$name: ${a.single().status} → ${b.single().status}"
            else -> null
        }
    }
    return "Previous-attempt comparison · ${if (complete) "both job inventories complete" else "loaded jobs only; incomplete inventory"}: " + changes.take(30).joinToString("; ").ifBlank { "No status changes in matched loaded jobs" } + if (changes.size > 30) "; ${changes.size - 30} more differences" else ""
}
