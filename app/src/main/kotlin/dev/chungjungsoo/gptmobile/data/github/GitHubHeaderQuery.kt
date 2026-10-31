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
        require(response["errors"] == null) { "GitHub header query failed; no metadata snapshot was verified." }
        val item = (((response["data"] as? JsonObject)?.get("repository") as? JsonObject)?.get(if (pullRequest) "pullRequest" else "issue") as? JsonObject)
        requireNotNull(item) { "GitHub resource is unavailable in this repository/account." }
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
