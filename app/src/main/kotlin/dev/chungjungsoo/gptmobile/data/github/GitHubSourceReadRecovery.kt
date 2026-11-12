package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Repair only rejected reads whose exact replacement is proven by the pinned executor. */
internal object GitHubSourceReadRecovery {
    private val sha = Regex("[a-fA-F0-9]{40}")

    fun correctedArguments(operation: String, arguments: JsonObject, validation: JsonObject): JsonObject? {
        if (validation["schema"] != JsonPrimitive("github.read_validation.v1") ||
            validation["status"] != JsonPrimitive("PRECHECK_REJECTED") ||
            validation["read_dispatched"] != JsonPrimitive(false) ||
            arguments.keys.any { it in setOf("view_cursor", "evidence_id", "execution_id") }
        ) {
            return null
        }
        val commit = validation.text("commit_sha").takeIf(sha::matches) ?: return null
        val owner = validation.text("owner").takeIf(String::isNotBlank) ?: return null
        val repo = validation.text("repo").takeIf(String::isNotBlank) ?: return null
        val ref = validation.text("ref").takeIf(String::isNotBlank) ?: return null
        if (arguments.text("commit_sha").let { it.isNotBlank() && !it.equals(commit, true) } ||
            arguments.text("owner").let { it.isNotBlank() && !it.equals(owner, true) } ||
            arguments.text("repo").let { it.isNotBlank() && !it.equals(repo, true) } ||
            listOf("ref", "branch").any { key -> arguments.text(key).let { it.isNotBlank() && it != ref && !it.equals(commit, true) } } ||
            (arguments.text("owner").isBlank() != arguments.text("repo").isBlank())
        ) {
            return null
        }
        val pinned = JsonObject(arguments + mapOf("owner" to JsonPrimitive(owner), "repo" to JsonPrimitive(repo), "commit_sha" to JsonPrimitive(commit)))
        return when {
            operation == "github_read" && validation.text("error_code") == "GITHUB_PATH_NOT_FOUND" -> pathCorrection(pinned, validation)
            operation == "github_search" && validation.text("error_code") == "GITHUB_SEARCH_CURSOR_UNBOUND" -> searchCorrection(pinned, validation)
            else -> null
        }
    }

    private fun pathCorrection(arguments: JsonObject, validation: JsonObject): JsonObject? {
        if (arguments.text("action") !in setOf("", "ranges", "read_ranges") || validation["tree_complete"] != JsonPrimitive(true)) return null
        val missing = validation.text("path").takeIf(String::isNotBlank) ?: return null
        val candidate = (validation["candidates"] as? JsonArray)?.singleOrNull() as? JsonObject ?: return null
        val path = candidate.text("path")
        if (path == missing ||
            path.substringAfterLast('/') != missing.substringAfterLast('/') ||
            !sha.matches(candidate.text("sha")) ||
            runCatching { GitHubWorkspaceClient.filePath(path) }.isFailure
        ) {
            return null
        }
        val ranges = arguments["ranges"] as? JsonArray
        if (ranges == null) {
            if (arguments.text("path") != missing || arguments.text("expected_blob_sha").let { it.isNotBlank() && !it.equals(candidate.text("sha"), true) }) return null
            return JsonObject(arguments + ("path" to JsonPrimitive(path)))
        }
        if (ranges.isEmpty() || ranges.size > 8 || ranges.any { it !is JsonObject || it.text("path") != missing }) return null
        if (ranges.any { (it as JsonObject).text("expected_blob_sha").let { expected -> expected.isNotBlank() && !expected.equals(candidate.text("sha"), true) } }) return null
        return JsonObject(arguments + ("ranges" to JsonArray(ranges.map { JsonObject((it as JsonObject) + ("path" to JsonPrimitive(path))) })))
    }

    private fun searchCorrection(arguments: JsonObject, validation: JsonObject): JsonObject? {
        if (arguments.text("action") !in setOf("", "search", "search_repository") || arguments.text("search_id").isNotBlank()) return null
        val cursor = (arguments["index_cursor"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: return null
        val recipe = validation["correction_call"] as? JsonObject ?: return null
        if (recipe.text("tool").substringBefore("__") != "github_search") return null
        val retained = recipe["arguments"] as? JsonObject ?: return null
        // The native executor supplies the selected ref when the facade caller
        // only supplied commit_sha. Remove that default only when its identity
        // agrees with the pinned validation context; never rewrite a caller ref.
        val corrected = if ("ref" !in arguments && "ref" in retained) {
            if (retained.text("ref") != validation.text("ref")) return null
            JsonObject(retained - "ref")
        } else {
            retained
        }
        if (!corrected.text("search_id").matches(Regex("[a-fA-F0-9]{64}")) || corrected["index_cursor"] != JsonPrimitive(cursor)) return null
        // The executor restored this one omitted field from an exact persisted
        // definition and issued boundary. It may not change query, filters or ref.
        if (JsonObject(corrected - "search_id") != JsonObject(arguments - "search_id")) return null
        return corrected
    }

    private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
