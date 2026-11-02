package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Compact model view. Originals remain in the evidence ledger; full detail can be requested explicitly. */
internal object GitHubResultProjection {
    private val noise = setOf("node_id", "external_id", "avatar_url", "gravatar_id", "followers_url", "following_url", "gists_url", "starred_url", "subscriptions_url", "organizations_url", "repos_url", "events_url", "received_events_url", "site_admin", "app", "performed_via_github_app", "signature", "payload")
    private val actions = mapOf(
        "github_context" to setOf("commits", "checks", "repository", "status"),
        "github_ci" to setOf("runs", "workflows", "jobs", "status"),
        "github_pr" to setOf("list")
    )

    fun project(operation: String, request: JsonObject, source: JsonObject): JsonObject {
        if (operation == "github_read" && request["response_detail"] != JsonPrimitive("full")) {
            val graph = source["context_graph"] as? JsonObject ?: return source
            return JsonObject(
                source + (
                    "context_graph" to JsonObject(
                        graph.filterKeys { it !in setOf("edges", "suggested_ranges", "test_candidates", "guidance_paths") } + mapOf(
                            "detail" to JsonPrimitive("Import edges omitted from this compact read. Request response_detail=full with the same immutable selectors to inspect them.")
                        )
                    )
                    )
            )
        }
        if (request["response_detail"] == JsonPrimitive("full") || (request["action"] as? JsonPrimitive)?.content !in actions[operation].orEmpty()) return source
        var omitted = 0
        fun visit(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(
                value.mapNotNull { (key, child) ->
                    if (key in noise) {
                        omitted++
                        null
                    } else {
                        key to visit(child)
                    }
                }.toMap()
            )
            is JsonArray -> JsonArray(value.map(::visit))
            else -> value
        }
        val compact = visit(source) as JsonObject
        if (omitted == 0) return source
        return JsonObject(
            compact + (
                "projection" to buildJsonObject {
                    put("omitted_metadata_fields", omitted)
                    put("detail", "Operational fields preserved. Request response_detail=full with the same selectors for omitted metadata; original evidence is retained.")
                }
                )
        )
    }
}
