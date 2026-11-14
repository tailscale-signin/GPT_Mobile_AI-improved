package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Preparation observations are scoped evidence, never execution or permission grants. */
internal object GitHubSelfTestPrerequisites {
    data class Selected(val source: JsonObject, val head: JsonPrimitive, val receipts: List<JsonObject>)

    fun selected(receipts: List<JsonObject>): Selected? {
        val trusted = receipts.filter {
            it["trusted"] == JsonPrimitive(true) &&
                it["verification_eligible"] != JsonPrimitive(false) &&
                it["repository_role"] != JsonPrimitive("READ_SOURCE")
        }
        val source = trusted.lastOrNull {
            it["success"] == JsonPrimitive(true) &&
                it.text("tool").substringBefore("__") == "github_context" &&
                (it["arguments"] as? JsonObject)?.text("action") == "status"
        } ?: return null
        val head = (GitHubInventoryObservation.planningRoot(source)?.get("commit_sha") as? JsonPrimitive)
            ?.takeIf { it.isString && it.content.matches(Regex("[a-fA-F0-9]{40}")) } ?: return null
        return Selected(source, head, trusted.filter { it["binding"] == source["binding"] && it["repository"] == source["repository"] })
    }

    fun graphObservation(receipts: List<JsonObject>): String {
        val selected = selected(receipts) ?: return "UNOBSERVED"
        val graph = selected.receipts.lastOrNull {
            it.text("tool").substringBefore("__") == "repository_graph" &&
                (it["arguments"] as? JsonObject)?.text("action") in setOf("status", "index")
        } ?: return "UNOBSERVED"
        if (graph["success"] != JsonPrimitive(true)) return "UNAVAILABLE"
        val root = GitHubInventoryObservation.planningRoot(graph) ?: return "UNAVAILABLE"
        val metadata = (root["graph"] as? JsonObject) ?: root
        if (metadata["repository"] != null && !metadata.text("repository").equals(selected.source.text("repository"), ignoreCase = true)) return "UNOBSERVED"
        if (!metadata.text("commit_sha").equals(selected.head.content, ignoreCase = true)) return "REVISION_MISMATCH"
        val source = GitHubInventoryObservation.planningRoot(selected.source)
        if (metadata["branch"] != null && source?.get("branch") != null && metadata["branch"] != source["branch"]) return "REVISION_MISMATCH"
        return if (root.text("status") == "NOT_INDEXED") "NOT_INDEXED" else "OBSERVED"
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
