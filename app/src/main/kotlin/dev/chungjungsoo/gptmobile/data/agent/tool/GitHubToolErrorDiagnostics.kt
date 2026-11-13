package dev.chungjungsoo.gptmobile.data.agent.tool

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Public server categories only: never copy validation messages, rejected values or request IDs. */
internal object GitHubToolErrorDiagnostics {
    private val resources = setOf("PullRequest", "Issue", "Repository", "Commit", "Reference", "Release", "Workflow", "WorkflowRun", "CheckRun", "CheckSuite", "ReviewComment", "Comment", "Label", "Milestone", "Project", "User", "Team")
    private val fields = setOf("head", "base", "title", "body", "ref", "sha", "name", "state", "number", "issue_number", "pull_number", "owner", "repo", "repository", "labels", "assignees", "milestone", "draft", "maintainer_can_modify", "tag_name", "target_commitish", "path", "position", "line", "side", "event", "commit_id", "workflow_id", "run_id", "inputs")
    private val codes = setOf("missing", "missing_field", "invalid", "already_exists", "unprocessable", "custom", "not_found", "forbidden", "unauthorized")

    fun response(body: String, requestId: String?): JsonObject {
        val errors = body.takeIf { it.length <= 256 * 1024 }?.let {
            runCatching { (Json.parseToJsonElement(it) as? JsonObject)?.get("errors") as? JsonArray }.getOrNull()
        }.orEmpty().take(32).filterIsInstance<JsonObject>()
        fun categories(field: String, allowed: Set<String>) = JsonArray(
            errors.mapNotNull { (it[field] as? JsonPrimitive)?.takeIf { value -> value.isString && value.content in allowed }?.content }
                .distinct().sorted().map(::JsonPrimitive)
        )
        return buildJsonObject {
            put("github_error_resources", categories("resource", resources))
            put("github_error_fields", categories("field", fields))
            put("github_error_codes", categories("code", codes))
            requestId?.trim()?.takeIf { it.isNotEmpty() && it.length <= 256 }?.let {
                put("server_request_id_hash", MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8)).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) })
            }
        }
    }
}
