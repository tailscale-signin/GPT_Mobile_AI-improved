package dev.chungjungsoo.gptmobile.data.github

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

    fun normalize(response: JsonObject, pullRequest: Boolean): JsonObject {
        val errors = response["errors"] as? kotlinx.serialization.json.JsonArray
        if (!errors.isNullOrEmpty()) {
            throw GitHubHeaderLookupFailure(
                "GITHUB_HEADER_QUERY_FAILED",
                buildJsonObject {
                    put("error_code", "GITHUB_HEADER_QUERY_FAILED")
                    put("failed_step", "graphql_header_lookup")
                    put("resource", if (pullRequest) "pull_request" else "issue")
                    put("causes", dev.chungjungsoo.gptmobile.data.diagnostics.redactChatDebugData(errors))
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
            put("projection", "headers")
            put("bodies_included", false)
            put("merge_readiness_verified", false)
        }
    }
}

internal class GitHubHeaderLookupFailure(val code: String, val details: JsonObject) : IllegalStateException(details.toString())
