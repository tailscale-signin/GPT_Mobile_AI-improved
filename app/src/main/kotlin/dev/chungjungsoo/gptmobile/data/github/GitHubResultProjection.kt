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
        if (operation == "github_release" && request["response_detail"] != JsonPrimitive("full") && request["action"] in setOf(JsonPrimitive("list"), JsonPrimitive("status"))) {
            var notes = 0
            fun releaseView(value: JsonElement): JsonElement = when (value) {
                is JsonObject -> buildJsonObject {
                    value.forEach { (key, child) ->
                        val body = (child as? JsonPrimitive)?.takeIf { it.isString }?.content
                        if (key == "body" && body != null && body.length > 512) {
                            notes++
                            put("body_preview", body.take(if (body[512].isLowSurrogate()) 511 else 512))
                            put("body_characters", body.length)
                            put("body_sha256", GitHubRepositoryStateStore.digest(body))
                        } else {
                            put(key, releaseView(child))
                        }
                    }
                }
                is JsonArray -> JsonArray(value.map(::releaseView))
                else -> value
            }
            val projected = releaseView(source) as JsonObject
            if (notes > 0) {
                return JsonObject(
                    projected + (
                        "projection" to buildJsonObject {
                            put("release_note_previews", notes)
                            put("detail", "Release notes are previews with original hashes. Full captured notes remain in the durable evidence receipt; detail retrieves the selected release. IDs, SHAs, assets, errors and remote pagination are preserved.")
                        }
                        )
                )
            }
            return source
        }
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
        fun check(value: JsonObject): JsonObject = buildJsonObject {
            // Check-run REST objects repeat the producer, repository and owner for
            // every run. These are display metadata, not eighteen independent
            // pieces of evidence. Keep the immutable producer identity for
            // distinguishing checks with the same name.
            setOf("id", "name", "status", "conclusion", "started_at", "completed_at").forEach { key -> value[key]?.let { put(key, it) } }
            (value["html_url"] ?: value["details_url"])?.let { put("html_url", it) }
            (value["app"] as? JsonObject)?.get("id")?.let { put("app", buildJsonObject { put("id", it) }) }
            if (value["required_verified"] == JsonPrimitive(true)) {
                value["required"]?.let { put("required", it) }
                put("required_verified", true)
            }
            val conclusion = (value["conclusion"] as? JsonPrimitive)?.content
            if (conclusion in setOf("failure", "timed_out", "cancelled", "action_required", "startup_failure", "stale")) {
                val output = value["output"] as? JsonObject
                val failure = listOfNotNull(output?.get("title"), output?.get("summary"))
                    .mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
                    .joinToString(" — ").trim()
                if (failure.isNotEmpty()) put("failure_summary", failure.take(256))
            }
        }
        fun status(value: JsonObject): JsonObject = JsonObject(value.filterKeys { it in setOf("id", "context", "state", "target_url", "description", "created_at", "updated_at") })
        fun visit(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(
                value.mapNotNull { (key, child) ->
                    if (key in noise) {
                        omitted++
                        null
                    } else if (key == "check_runs" && child is JsonArray) {
                        omitted += child.size
                        key to JsonArray(child.map { if (it is JsonObject) check(it) else it })
                    } else if (key == "statuses" && child is JsonArray) {
                        omitted += child.size
                        key to JsonArray(child.map { if (it is JsonObject) status(it) else it })
                    } else {
                        key to visit(child)
                    }
                }.toMap()
            )
            is JsonArray -> JsonArray(value.map(::visit))
            else -> value
        }
        var compact = visit(source) as JsonObject
        if (operation == "github_context" && request["action"] == JsonPrimitive("commits")) {
            val items = compact["items"] as? JsonArray
            if (items != null) {
                compact = JsonObject(
                    compact - "history_graph" - "recent_commits" + (
                        "items" to JsonArray(
                            items.map { raw ->
                                val item = raw as? JsonObject ?: return@map raw
                                val commit = item["commit"] as? JsonObject
                                buildJsonObject {
                                    item["sha"]?.let { put("sha", it) }
                                    item["html_url"]?.let { put("html_url", it) }
                                    put(
                                        "commit",
                                        buildJsonObject {
                                            put("subject", ((commit?.get("message") ?: commit?.get("subject")) as? JsonPrimitive)?.content.orEmpty().lineSequence().firstOrNull().orEmpty().take(240))
                                            commit?.get("author")?.let { put("author", it) }
                                        }
                                    )
                                }
                            }
                        )
                        )
                )
                omitted++
            }
        }
        if (omitted == 0) return source
        return JsonObject(
            compact + (
                "projection" to buildJsonObject {
                    put("omitted_metadata_fields", omitted)
                    put("detail", "Check identities, states, URLs, timestamps and brief failure summaries are preserved. Requiredness is shown only when verified. Complete original evidence is retained; display metadata does not require pagination. Request response_detail=full for original detail.")
                }
                )
        )
    }
}
