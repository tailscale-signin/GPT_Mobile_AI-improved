package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** An empty, complete failure query is useful evidence, not proof of passing checks. */
internal object GitHubCiFailureObservation {
    private val allowed = setOf("action", "owner", "repo", "head_sha", "commit_sha", "status", "page", "per_page", "response_detail", "verify_live")

    fun emptyRevision(arguments: JsonObject, data: JsonObject): String? {
        if (arguments["action"] != JsonPrimitive("runs") || arguments["status"] != JsonPrimitive("failure") || arguments.keys.any { it !in allowed }) return null
        val head = GitHubInventoryQuery.commitSha(arguments["head_sha"] ?: arguments["commit_sha"]) ?: return null
        if (arguments["head_sha"] != null && arguments["commit_sha"] != null && arguments["head_sha"] != arguments["commit_sha"]) return null
        if (data["commit_sha"] != null && data["commit_sha"] != head || data["head_sha"] != null && data["head_sha"] != head) return null
        if (arguments["page"] !in setOf(null, JsonPrimitive(1)) || data["page"] != JsonPrimitive(1)) return null
        if ((data["workflow_runs"] as? JsonArray)?.isEmpty() != true || data["returned"] != JsonPrimitive(0) || data["total_count"] != JsonPrimitive(0) || data["has_more"] != JsonPrimitive(false)) return null
        if (data["history_truncated"] == JsonPrimitive(true) || data["pagination_inconsistent"] == JsonPrimitive(true)) return null
        val progress = data["pagination_progress"] as? JsonObject
        if (progress?.get("upstream_changed_during_traversal") == JsonPrimitive(true) ||
            progress?.get("complete") == JsonPrimitive(false) ||
            progress?.get("contiguous_from_first_page") == JsonPrimitive(false) ||
            listOf("complete", "traversal_complete", "history_complete").any { data[it] == JsonPrimitive(false) }
        ) {
            return null
        }
        val complete = data["traversal_complete"] == JsonPrimitive(true) ||
            data["complete"] == JsonPrimitive(true) ||
            progress?.get("complete") == JsonPrimitive(true) &&
            progress["contiguous_from_first_page"] == JsonPrimitive(true)
        return head.content.takeIf { complete }
    }
}
