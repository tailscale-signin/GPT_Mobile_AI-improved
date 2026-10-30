package dev.chungjungsoo.gptmobile.data.github

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Bounded, explicit follow-ups. A failed policy read is unknown, never permission. */
internal class GitHubInspection(private val get: suspend (String) -> JsonElement) {
    companion object {
        val actions = setOf("branch_context", "branch_dependencies", "repository_details", "issue_context", "issue_comments", "issue_events", "search_commits", "tag_context", "pull_request_reviews")
    }

    private fun JsonObject.s(key: String) = (get(key) as? JsonPrimitive)?.content.orEmpty()
    private fun segment(value: String) = GitHubWorkspaceClient.segment(value)
    private suspend fun observed(path: String): JsonObject = try {
        buildJsonObject {
            put("available", true)
            put("data", get(path))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        buildJsonObject {
            put("available", false)
            put("state", "unknown")
            put("reason", "Endpoint unavailable or access denied; no permission or absence inferred.")
        }
    }

    suspend fun execute(action: String, root: String, args: JsonObject, page: Int): JsonObject {
        fun required(key: String) = args.s(key).also { require(it.isNotBlank()) { "$key is required." } }
        fun number(key: String) = required(key).toInt().also { require(it > 0) }
        return when (action) {
            "repository_details" -> buildJsonObject {
                put("repository", get(root))
                put("languages", observed("$root/languages"))
                put("observed_at", System.currentTimeMillis())
                put("languages_unit", "bytes")
            }
            "branch_context" -> {
                val branch = segment(required("branch"))
                buildJsonObject {
                    put("branch", get("$root/branches/$branch"))
                    put("protection", observed("$root/branches/$branch/protection"))
                    put("rules", observed("$root/rules/branches/$branch"))
                    put("observed_at", System.currentTimeMillis())
                }
            }
            "branch_dependencies" -> {
                val branch = required("branch")
                val owner = root.removePrefix("/repos/").substringBefore('/')
                val headPrs = get("$root/pulls?state=open&head=${segment("$owner:$branch")}&per_page=30&page=$page").jsonArray
                val basePrs = get("$root/pulls?state=open&base=${segment(branch)}&per_page=30&page=$page").jsonArray
                val runs = get("$root/actions/runs?branch=${segment(branch)}&per_page=30&page=$page").jsonObject
                val more = headPrs.size == 30 || basePrs.size == 30 || (runs.s("total_count").toIntOrNull() ?: Int.MAX_VALUE) > page * 30
                buildJsonObject {
                    put("branch", get("$root/branches/${segment(branch)}"))
                    put("head_pull_requests", headPrs)
                    put("base_pull_requests", basePrs)
                    put("workflow_runs", runs)
                    put("page", page)
                    put("has_more", more)
                    put("deletion_authorized", false)
                    put("note", "Advisory mutable observations. GitHub ref deletion has no atomic expected-SHA precondition. Inspect every page before deletion.")
                    if (more) put("continuation", JsonObject(args + mapOf("action" to JsonPrimitive(action), "page" to JsonPrimitive(page + 1))))
                }
            }
            "issue_context" -> buildJsonObject {
                val id = number("issue_number")
                put("issue", get("$root/issues/$id"))
                put("comments_continuation", JsonObject(args + ("action" to JsonPrimitive("issue_comments"))))
                put("history_continuation", JsonObject(args + ("action" to JsonPrimitive("issue_events"))))
                put("history_scope", "GitHub timeline events; not a complete revision history of edited issue bodies.")
            }
            "issue_comments", "issue_events" -> {
                val endpoint = if (action == "issue_comments") "comments" else "timeline"
                page(get("$root/issues/${number("issue_number")}/$endpoint?per_page=30&page=$page"), action, args, page)
            }
            "pull_request_reviews" -> page(get("$root/pulls/${number("pull_number")}/reviews?per_page=30&page=$page"), action, args, page)
            "search_commits" -> {
                val query = required("query")
                require(query.length <= 200)
                // Repo qualifiers supplied by the model cannot widen the selected repository.
                require(!Regex("(?i)(repo|org|user):").containsMatchIn(query)) { "Omit repository qualifiers; the selected repository is always enforced." }
                val result = get("/search/commits?q=${segment(query + " repo:" + root.removePrefix("/repos/"))}&per_page=30&page=$page").jsonObject
                val total = result["total_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val more = page * 30 < minOf(total, 1000)
                buildJsonObject {
                    result.forEach { (key, value) -> put(key, value) }
                    put("page", page)
                    put("has_more", more)
                    put("complete", !more && total <= 1000 && result.s("incomplete_results") == "false")
                    put("search_limit", 1000)
                    if (more) put("continuation", JsonObject(args + mapOf("action" to JsonPrimitive(action), "page" to JsonPrimitive(page + 1))))
                }
            }
            "tag_context" -> {
                val tag = segment(required("tag_name"))
                val ref = get("$root/git/ref/tags/$tag").jsonObject
                val target = ref["object"]!!.jsonObject
                var resolved = target
                var annotation: JsonElement = JsonNull
                val visited = mutableSetOf<String>()
                while (resolved.s("type") == "tag") {
                    require(visited.size < 16 && visited.add(resolved.s("sha"))) { "Tag chain is too deep or cyclic." }
                    val objectData = get("$root/git/tags/${segment(resolved.s("sha"))}").jsonObject
                    if (annotation == JsonNull) annotation = objectData
                    resolved = objectData["object"]!!.jsonObject
                }
                buildJsonObject {
                    put("ref", ref)
                    put("annotation", annotation)
                    put("resolved_target", resolved)
                    put("commit", if (resolved.s("type") == "commit") get("$root/commits/${segment(resolved.s("sha"))}") else JsonNull)
                    put("release", observed("$root/releases/tags/$tag"))
                }
            }
            else -> error("Unsupported inspection action")
        }
    }

    private fun page(value: JsonElement, action: String, args: JsonObject, page: Int): JsonObject = buildJsonObject {
        val items = value.jsonArray
        put("items", items)
        put("page", page)
        put("returned", items.size)
        put("has_more", items.size == 30)
        put("complete", items.size < 30)
        if (items.size == 30) put("continuation", JsonObject(args + mapOf("action" to JsonPrimitive(action), "page" to JsonPrimitive(page + 1))))
    }
}
