package dev.chungjungsoo.gptmobile.data.github

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
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
        val actions = setOf("branch_context", "branch_dependencies", "repository_details", "list_issues", "issue_context", "issue_comments", "issue_events", "search_commits", "tag_context", "pull_request_reviews")
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
            "list_issues" -> {
                val state = args.s("state").ifBlank { "open" }
                require(state in setOf("open", "closed", "all")) { "Issue state must be open, closed or all." }
                val perPage = args.s("per_page").ifBlank { "30" }.toInt().also { require(it in 1..100) }
                val sort = args.s("sort").ifBlank { "created" }.also { require(it in setOf("created", "updated", "comments")) }
                val direction = args.s("direction").ifBlank { "asc" }.also { require(it in setOf("asc", "desc")) }
                val raw = get("$root/issues?state=$state&sort=$sort&direction=$direction&per_page=$perPage&page=$page").jsonArray
                val issues = raw.filter { "pull_request" !in it.jsonObject }
                buildJsonObject {
                    put("items", JsonArray(issues))
                    put("page", page)
                    put("per_page", perPage)
                    put("returned", issues.size)
                    put("upstream_returned", raw.size)
                    put("upstream_record_ids", JsonArray(raw.mapNotNull { (it as? JsonObject)?.get("id") }))
                    put("resource_type", "issue")
                    put("pull_requests_excluded", raw.size - issues.size)
                    put("has_more", raw.size == perPage)
                    put("traversal_state", if (raw.size == perPage) "MORE" else "COMPLETE")
                    put("empty", issues.isEmpty())
                    if (raw.size == perPage) put("continuation", JsonObject(args + mapOf("action" to JsonPrimitive(action), "page" to JsonPrimitive(page + 1), "per_page" to JsonPrimitive(perPage), "sort" to JsonPrimitive(sort), "direction" to JsonPrimitive(direction))))
                }
            }
            "repository_details" -> buildJsonObject {
                put("repository", get(root))
                put("languages", observed("$root/languages"))
                put("observed_at", System.currentTimeMillis())
                put("languages_unit", "bytes")
            }
            "branch_context" -> {
                val branch = segment(required("branch"))
                val details = get("$root/branches/$branch")
                val protection = observed("$root/branches/$branch/protection")
                val rules = observed("$root/rules/branches/$branch")
                buildJsonObject {
                    put("branch", details)
                    put("protection", protection)
                    put("rules", rules)
                    put("policy", branchPolicy(protection, rules))
                    put("observed_at", System.currentTimeMillis())
                }
            }
            "branch_dependencies" -> {
                val branch = required("branch")
                // Resolve first: a missing branch must not fan out into unrelated PR/CI reads.
                val branchDetails = get("$root/branches/${segment(branch)}")
                val owner = root.removePrefix("/repos/").substringBefore('/')
                val headPrs = get("$root/pulls?state=open&head=${segment("$owner:$branch")}&per_page=30&page=$page").jsonArray
                val basePrs = get("$root/pulls?state=open&base=${segment(branch)}&per_page=30&page=$page").jsonArray
                val runs = get("$root/actions/runs?branch=${segment(branch)}&per_page=30&page=$page").jsonObject
                val more = headPrs.size == 30 || basePrs.size == 30 || (runs.s("total_count").toIntOrNull() ?: Int.MAX_VALUE) > page * 30
                buildJsonObject {
                    put("branch", branchDetails)
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
                    put(
                        "commit",
                        if (resolved.s("type") == "commit") {
                            GitHubCommitDetail.present(
                                get("$root/commits/${segment(resolved.s("sha"))}?per_page=30&page=1").jsonObject,
                                page = 1,
                                perPage = 30,
                                includePatches = false
                            )
                        } else {
                            JsonNull
                        }
                    )
                    put("release", observed("$root/releases/tags/$tag"))
                }
            }
            else -> error("Unsupported inspection action")
        }
    }

    private fun branchPolicy(protection: JsonObject, rules: JsonObject): JsonObject {
        val protectionAvailable = protection["available"] == JsonPrimitive(true)
        val rulesAvailable = rules["available"] == JsonPrimitive(true)
        val required = mutableListOf<JsonElement>()
        val classic = ((protection["data"] as? JsonObject)?.get("required_status_checks") as? JsonObject)
        (classic?.get("contexts") as? JsonArray)?.let { required.addAll(it) }
        (classic?.get("checks") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("context") }?.let { required.addAll(it) }
        (rules["data"] as? JsonArray)?.filterIsInstance<JsonObject>()?.filter { it.s("type") == "required_status_checks" }?.forEach { rule ->
            (((rule["parameters"] as? JsonObject)?.get("required_status_checks")) as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("context") }?.let { required.addAll(it) }
        }
        val complete = protectionAvailable && rulesAvailable
        return buildJsonObject {
            put("protection_available", protectionAvailable)
            put("rules_available", rulesAvailable)
            put(
                "required_checks_state",
                if (!complete) {
                    "UNKNOWN"
                } else if (required.isEmpty()) {
                    "VERIFIED_NONE"
                } else {
                    "VERIFIED_PRESENT"
                }
            )
            put("observed_required_checks", JsonArray(required.distinct()))
            put("required_checks_complete", complete)
            put("interpretation", if (complete) "Both policy sources were retrieved. Observed check results are independent of this requirement policy." else "One or more policy endpoints were unavailable. Branch metadata protected=false or an empty available rules list cannot establish that no checks are required.")
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
