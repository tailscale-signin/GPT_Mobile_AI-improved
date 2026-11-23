package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** An exact issued read recipe and its coverage survive display compaction together. */
internal object GitHubSearchFrontier {
    private val sha = Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")
    private val searchId = Regex("[a-f0-9]{64}")
    private val coverageKeys = setOf("search_id", "commit_sha", "interval_start", "interval_end", "total_candidate_files", "complete", "matches_complete", "coverage_from_start", "tree_complete", "total_skipped_files", "total_deferred_files", "has_more", "stop_reason", "returned")

    fun capture(name: String, arguments: JsonObject, data: JsonObject, recipes: List<JsonObject>): JsonObject? {
        if (name.githubOperationFamily() != "github_search" || "view_cursor" in arguments) return null
        val id = data.text("search_id")?.takeIf(searchId::matches) ?: return null
        val commit = data.text("commit_sha")?.takeIf(sha::matches) ?: return null
        val start = data.integer("interval_start") ?: return null
        val end = data.integer("interval_end") ?: return null
        val total = data.integer("total_candidate_files") ?: return null
        if (start < 0 || end < start || total < end) return null
        val identity = identity(arguments) ?: return null
        val continuation = recipes.distinct().singleOrNull { recipe ->
            val next = recipe["arguments"] as? JsonObject ?: return@singleOrNull false
            recipe["tool"] == JsonPrimitive(name) &&
                next["search_id"] == JsonPrimitive(id) &&
                next["commit_sha"] == JsonPrimitive(commit) &&
                next.integer("index_cursor") == end &&
                identity(next) == identity &&
                next["action"] in setOf(null, JsonPrimitive("search"), JsonPrimitive("search_repository"), JsonPrimitive("search_code")) &&
                "view_cursor" !in next
        }?.takeIf { data["has_more"] == JsonPrimitive(true) && end > start && end < total && it.toString().length <= 4096 }
        return buildJsonObject {
            put("schema", "github.search-frontier.v1")
            data.filterKeys { it in coverageKeys }.forEach { (key, value) -> put(key, value) }
            put("query_identity", identity)
            put("query_identity_sha256", GitHubRepositoryStateStore.digest(identity.toString()))
            continuation?.let { put("continuation_call", it) }
            put("continuation_available", continuation != null)
            put("coverage_policy", "Zero hits are not absence proof while coverage is incomplete. Deferred and skipped files remain unsearched.")
        }.takeIf { it.toString().length <= 8192 }
    }

    /** The native dispatch receipt supplies omitted repository selectors, never model text. */
    fun continuation(name: String, arguments: JsonObject, receipt: JsonObject?): JsonObject? {
        if (receipt?.get("schema") != JsonPrimitive("github.dispatch.v1")) return null
        val resolved = receipt["resolved_arguments"] as? JsonObject ?: return null
        val resource = receipt["resource_context"] as? JsonObject ?: return null
        if (resolved["action"] != JsonPrimitive("search_repository")) return null
        val frontier = receipt["search_frontier"] as? JsonObject ?: return null
        if (frontier["schema"] != JsonPrimitive("github.search-frontier.v1") || frontier["has_more"] != JsonPrimitive(true) || frontier["complete"] == JsonPrimitive(true)) return null
        val recipe = frontier["continuation_call"] as? JsonObject ?: return null
        val next = recipe["arguments"] as? JsonObject ?: return null
        val end = frontier.integer("interval_end") ?: return null
        val start = frontier.integer("interval_start") ?: return null
        val total = frontier.integer("total_candidate_files") ?: return null
        if (recipe["tool"] != JsonPrimitive(name) || "view_cursor" in next || start < 0 || end <= start || end >= total || next.integer("index_cursor") != end) return null
        if ((arguments.integer("index_cursor") ?: 0) != start || next["search_id"] != frontier["search_id"] || next["commit_sha"] != frontier["commit_sha"]) return null
        if (arguments["search_id"] != null && arguments["search_id"] != frontier["search_id"] || arguments["commit_sha"] != null && arguments["commit_sha"] != frontier["commit_sha"]) return null
        val identity = identity(arguments) ?: return null
        if (identity != frontier["query_identity"] || identity(next) != identity || GitHubRepositoryStateStore.digest(identity.toString()) != frontier.text("query_identity_sha256")) return null
        for (key in listOf("owner", "repo")) {
            val selected = resource.text(key) ?: return null
            if (!selected.equals(resolved.text(key), true) || !selected.equals(next.text(key), true)) return null
            if (arguments[key] != null && !selected.equals(arguments.text(key), true)) return null
        }
        return recipe
    }

    fun recipes(source: JsonObject?, receipt: JsonObject?): List<JsonObject> =
        listOfNotNull(source?.get("continuation_call") as? JsonObject) +
            listOf(source?.get("remote_continuations"), receipt?.get("retained_remote_continuations"))
                .flatMap { (it as? JsonArray).orEmpty() }.filterIsInstance<JsonObject>().mapNotNull { it["call"] as? JsonObject }

    private fun identity(arguments: JsonObject): JsonObject? {
        val options = try {
            GitHubSearchArguments.validate(arguments)
        } catch (_: GitHubSearchArgumentFailure) {
            return null
        }
        return buildJsonObject {
            put("query", arguments.getValue("query"))
            put("mode", arguments["mode"] ?: JsonPrimitive("literal"))
            put("path_prefix", arguments["path_prefix"] ?: JsonPrimitive(""))
            put("file_extension", arguments.text("file_extension").orEmpty().removePrefix(".").lowercase(java.util.Locale.ROOT))
            put("batch_size", arguments["batch_size"] ?: JsonPrimitive(1000))
            put("max_new_blobs", arguments["max_new_blobs"] ?: JsonPrimitive(4))
            options.json().forEach { (key, value) -> put(key, value) }
        }.takeIf { it.toString().length <= 2048 }
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.integer(key: String) = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
}
