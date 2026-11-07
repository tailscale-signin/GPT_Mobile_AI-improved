package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Fixed field selections keep bodies, diffs, review threads and user records off the wire. */
internal object GitHubHeaderQuery {
    fun query(pullRequest: Boolean): String {
        val selection = if (pullRequest) "pullRequest" else "issue"
        val fields = if (pullRequest) "isDraft merged headRefOid baseRefOid headRefName baseRefName" else "closed"
        return "query(\$owner:String!,\$repo:String!,\$number:Int!){repository(owner:\$owner,name:\$repo){$selection(number:\$number){number title state url updatedAt $fields}}}"
    }

    private fun sanitizedCauses(errors: JsonArray): JsonArray = JsonArray(
        errors.take(32).map { error ->
            val fields = error as? JsonObject
            val errorType = (fields?.get("type") ?: (fields?.get("extensions") as? JsonObject)?.get("code")) as? JsonPrimitive
            val path = fields?.get("path") as? JsonArray
            val safePath = path?.take(64)?.map { segment ->
                val value = segment as? JsonPrimitive
                when {
                    value == null -> JsonPrimitive("[unavailable]")
                    !value.isString && value.content.toLongOrNull()?.let { it >= 0 } == true -> value
                    value.isString && value.content.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")) ->
                        dev.chungjungsoo.gptmobile.data.diagnostics.redactChatDebugData(value)
                    else -> JsonPrimitive("[redacted]")
                }
            }
            buildJsonObject {
                put("type", errorType?.content?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,63}")) } ?: "GRAPHQL_APPLICATION_ERROR")
                if (safePath != null) {
                    put("path", JsonArray(safePath))
                    put("path_complete", safePath == path)
                    put("path_segment_count", path?.size ?: 0)
                }
                // Server prose/extensions can contain credentials or user content;
                // the typed cause and exact safe path are sufficient for recovery.
                put("message_retained", false)
            }
        }
    )

    fun normalize(response: JsonObject, pullRequest: Boolean, arguments: JsonObject = JsonObject(emptyMap())): JsonObject {
        val identity = buildJsonObject {
            listOf("owner", "repo").forEach { key ->
                (arguments[key] as? JsonPrimitive)?.content?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,100}")) }?.let { put(key, it) }
            }
            val numberKey = if (pullRequest) "pull_number" else "issue_number"
            (arguments[numberKey] as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it > 0 }?.let { put(numberKey, it) }
        }
        val errors = response["errors"] as? JsonArray
        if (!errors.isNullOrEmpty()) {
            throw GitHubHeaderLookupFailure(
                "GITHUB_HEADER_QUERY_FAILED",
                buildJsonObject {
                    put("error_code", "GITHUB_HEADER_QUERY_FAILED")
                    put("failed_step", "graphql_header_lookup")
                    put("failed_stage", "graphql_application")
                    put("operation", if (pullRequest) "pr_headers" else "issue_headers")
                    put("resource_identity", identity)
                    put("dispatched", true)
                    put("retryable", false)
                    put("resource", if (pullRequest) "pull_request" else "issue")
                    put("causes", sanitizedCauses(errors))
                    put("causes_complete", errors.size <= 32)
                    put("error_count", errors.size)
                    put("next_action", if (pullRequest) "github_pr action=list; use an observed pull_number" else "github_issue action=list; use an observed issue_number")
                    put("snapshot_verified", false)
                }
            )
        }
        val item = (((response["data"] as? JsonObject)?.get("repository") as? JsonObject)?.get(if (pullRequest) "pullRequest" else "issue") as? JsonObject)
        if (item == null) {
            throw GitHubHeaderLookupFailure(
                "GITHUB_RESOURCE_UNAVAILABLE",
                buildJsonObject {
                    put("error_code", "GITHUB_RESOURCE_UNAVAILABLE")
                    put("failed_step", "resource_lookup")
                    put("failed_stage", "graphql_resource_lookup")
                    put("operation", if (pullRequest) "pr_headers" else "issue_headers")
                    put("resource_identity", identity)
                    put("resource", if (pullRequest) "pull_request" else "issue")
                    put("next_action", "Discover an existing resource in the selected repository and verify account access.")
                    put("snapshot_verified", false)
                }
            )
        }
        return buildJsonObject {
            mapOf("number" to "number", "title" to "title", "state" to "state", "url" to "html_url", "updatedAt" to "updated_at", "isDraft" to "draft", "merged" to "merged", "closed" to "closed", "headRefOid" to "head_sha", "baseRefOid" to "base_sha", "headRefName" to "head_ref", "baseRefName" to "base_ref").forEach { (source, target) ->
                item[source]?.let { value ->
                    put(target, if (source == "state" && value is JsonPrimitive) JsonPrimitive(value.content.lowercase()) else value)
                }
            }
            put("resource_type", if (pullRequest) "pull_request" else "issue")
            put("projection", "headers")
            put("bodies_included", false)
            put("merge_readiness_verified", false)
        }
    }
}

internal class GitHubHeaderLookupFailure(val code: String, val details: JsonObject) : IllegalStateException(details.toString())
