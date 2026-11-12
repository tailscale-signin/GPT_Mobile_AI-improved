package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** The task binding includes connection/account authority; resource and revision fields remain exact. */
internal object GitHubActionIdentity {
    /** Match the native facade default before tracing, deduplication and receipt binding. */
    fun canonicalArguments(tool: String, arguments: JsonObject): JsonObject {
        if (tool.substringBefore("__") != "github_ci" || "view_cursor" in arguments) return arguments
        val action = (arguments["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        // Invalid non-string values remain invalid; normalization cannot bypass schema validation.
        if (arguments.containsKey("action") && action == null) return arguments
        val canonical = when (action) {
            null, "" -> "workflows"
            "rerun" -> "rerun_failed_jobs"
            else -> action
        }
        return JsonObject(arguments + ("action" to JsonPrimitive(canonical)))
    }

    fun bind(arguments: JsonObject, repository: GitHubRepositoryContext): JsonObject {
        val requested = normalizeGitHubRepository(string(arguments, "owner"), string(arguments, "repo"))
        require(requested.first?.equals(repository.owner, true) != false && requested.second?.equals(repository.repo, true) != false) { "The operation belongs to another repository." }
        return JsonObject(arguments + mapOf("owner" to JsonPrimitive(repository.owner), "repo" to JsonPrimitive(repository.repo)))
    }

    fun digest(binding: String, repository: String, tool: String, arguments: JsonObject): String? = runCatching {
        val (owner, repo) = normalizeGitHubRepository(null, repository)
        require(!binding.isBlank() && owner != null && repo != null)
        val bounded = bind(canonicalArguments(tool, arguments), GitHubRepositoryContext(owner.lowercase(), repo.lowercase(), ""))
        GitHubEvidenceBook.digest(
            buildJsonObject {
                put("schema", "github.action-identity.v1")
                put("authorization_scope", binding)
                // The suffix identifies the selected connection's tool surface. Do not strip it.
                put("tool", tool)
                put("repository", "${owner.lowercase()}/${repo.lowercase()}")
                // Keep every meaningful field, including exact leases, content, cursors and live-read intent.
                put("arguments", ordered(bounded))
            }.toString()
        )
    }.getOrNull()

    fun digest(receipt: JsonObject): String? {
        val arguments = receipt["arguments"] as? JsonObject ?: return null
        return digest(receipt.text("binding"), receipt.text("repository"), receipt.text("tool"), arguments)
    }

    /** A bare recipe is resolved only inside the receipt's already checked task/connection scope. */
    fun matches(tool: String, arguments: JsonObject, receipt: JsonObject): Boolean {
        val observedTool = receipt.text("tool")
        if (tool != observedTool && ("__" in tool || tool != observedTool.substringBefore("__"))) return false
        val actual = digest(receipt) ?: return false
        return actual == digest(receipt.text("binding"), receipt.text("repository"), observedTool, arguments)
    }

    private fun string(arguments: JsonObject, key: String): String? {
        val value = arguments[key] ?: return null
        if (value == JsonNull) return null
        require(value is JsonPrimitive && value.isString) { "$key must be a repository name string." }
        return value.content
    }

    private fun ordered(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { ordered(it.value) })
        is JsonArray -> JsonArray(value.map(::ordered))
        else -> value
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
