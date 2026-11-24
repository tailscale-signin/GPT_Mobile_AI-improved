package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Count the canonical returned set before model/display projection can omit records. */
internal object GitHubInventoryObservation {
    private val fields = listOf("workflows", "items", "pull_requests", "workflow_runs", "jobs", "tags", "recent_commits", "commits", "issues", "releases", "branches")

    fun capture(source: JsonElement, visible: JsonElement? = source): JsonObject? {
        fun root(value: JsonElement?) = (value as? JsonObject)?.let { (it["data"] as? JsonObject) ?: it }
        val value = root(source) ?: return null
        val field = fields.firstOrNull { value[it] is JsonArray } ?: return null
        val records = value[field] as JsonArray
        val identities = records.mapNotNull { row ->
            val item = row as? JsonObject ?: return@mapNotNull null
            listOf("id", "number", "name", "sha").firstNotNullOfOrNull { key -> (item[key] as? JsonPrimitive)?.takeUnless { it == JsonNull } }
        }
        val display = root(visible)?.get(field) as? JsonArray
        return buildJsonObject {
            put("schema", "github.inventory-observation.v1")
            put("field", field)
            GitHubInventoryQuery.commitSha(value["commit_sha"])?.let { put("commit_sha", it) }
            put("fetched", records.size)
            put("record_keys", JsonArray(identities))
            put("record_keys_complete", identities.size == records.size && identities.distinct().size == records.size)
            if (field in setOf("workflow_runs", "jobs", "pull_requests", "issues", "releases", "tags", "items")) {
                put(
                    "planning_records",
                    JsonArray(
                        records.filterIsInstance<JsonObject>().take(1000).map { row ->
                            JsonObject(
                                row.filterKeys { it in setOf("id", "number", "name", "state", "tag_name", "draft", "run_id", "run_attempt", "head_sha", "status", "conclusion") } + buildJsonObject {
                                    val path = (row["path"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isWorkspacePath() }
                                    val blob = GitHubInventoryQuery.commitSha(row["sha"])
                                    if (path != null && blob != null) {
                                        put("path", path)
                                        put("sha", blob)
                                    }
                                    for (key in listOf("head", "base")) {
                                        (row[key] as? JsonObject)?.let { revision -> put(key, JsonObject(revision.filterKeys { it == "sha" })) }
                                    }
                                    if ("pull_request" in row) put("pull_request", true)
                                    (row["steps"] as? JsonArray)?.let { steps -> put("steps", JsonArray(steps.filterIsInstance<JsonObject>().take(100).map { JsonObject(it.filterKeys { key -> key in setOf("status", "conclusion") }) })) }
                                }
                            )
                        }
                    )
                )
            }
            put("displayed", display?.size?.let(::JsonPrimitive) ?: JsonNull)
            put("consumed", JsonNull)
            put("consumption_status", "NOT_RECORDED")
            for (key in listOf("page", "per_page", "total_count", "has_more", "complete")) value[key]?.let { put(key, it) }
            value["pagination_inconsistent"]?.let { put("pagination_inconsistent", it) }
            (value["pagination_progress"] as? JsonObject)?.get("upstream_changed_during_traversal")?.let { put("upstream_changed_during_traversal", it) }
            put("scope", "CANONICAL_RETURNED_PAGE_BEFORE_PROJECTION")
        }
    }

    fun of(receipt: JsonObject): JsonObject? =
        (receipt["inventory_observation"] as? JsonObject)?.takeIf { it["schema"] == JsonPrimitive("github.inventory-observation.v1") }
            ?: receipt["result"]?.takeUnless { receipt["review_projection"] != null }?.let(::capture)
            ?: fromDispatchMetadata(receipt)

    /** Legacy excerpts supply selectors for another checked read, never omitted facts or completeness. */
    fun planningRoot(receipt: JsonObject): JsonObject? {
        val envelope = receipt["result"] as? JsonObject ?: return null
        val value = if (envelope["observationTruncated"] == JsonPrimitive(true)) envelope["excerpt"] as? JsonObject ?: JsonObject(emptyMap()) else envelope
        val root = (value["data"] as? JsonObject) ?: value
        val inventory = (receipt["inventory_observation"] as? JsonObject)?.takeIf { it["schema"] == JsonPrimitive("github.inventory-observation.v1") }
        val field = (inventory?.get("field") as? JsonPrimitive)?.contentOrNull
        val records = inventory?.get("planning_records") as? JsonArray
        if (field !in fields || records == null) return root
        val revision = GitHubInventoryQuery.commitSha(inventory?.get("commit_sha"))
        if (revision != null && "commit_sha" in root && GitHubInventoryQuery.commitSha(root["commit_sha"]) != revision) return null
        return JsonObject(root + (requireNotNull(field) to records) + if (revision != null) mapOf("commit_sha" to revision) else emptyMap())
    }

    /** A search selector stays attached to the selected repository and exact returned revision. */
    fun searchPath(receipts: List<JsonObject>): JsonPrimitive? = searchReceipts(receipts).asReversed().firstNotNullOfOrNull { receipt ->
        // Older inventories retain only empty planning rows. A surviving
        // original/excerpt selector remains usable without restoring any
        // missing rows or claiming that the partial page was consumed.
        listOfNotNull(planningRoot(receipt), sourceRoot(receipt)).firstNotNullOfOrNull { root ->
            val rows = listOf("matches", "results", "files", "items").firstNotNullOfOrNull { root[it] as? JsonArray }.orEmpty()
            rows.filterIsInstance<JsonObject>().firstNotNullOfOrNull { row ->
                (row["path"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isWorkspacePath() && GitHubInventoryQuery.commitSha(row["sha"]) != null }
            }
        }
    }

    internal fun searchReceipts(receipts: List<JsonObject>): List<JsonObject> {
        val selected = GitHubSelfTestPrerequisites.selected(receipts) ?: return emptyList()
        val revision = GitHubInventoryQuery.commitSha(selected.head)
        return selected.receipts.filter { receipt ->
            val args = receipt["arguments"] as? JsonObject ?: return@filter false
            if (receipt["success"] != JsonPrimitive(true) ||
                (receipt["tool"] as? JsonPrimitive)?.contentOrNull?.githubOperationFamily() != "github_search" ||
                args["action"] != JsonPrimitive("search") ||
                "view_cursor" in args
            ) {
                return@filter false
            }
            if ("owner" in args || "repo" in args) {
                val repository = "${(args["owner"] as? JsonPrimitive)?.contentOrNull}/${(args["repo"] as? JsonPrimitive)?.contentOrNull}"
                if (!repository.equals((receipt["repository"] as? JsonPrimitive)?.contentOrNull, true)) return@filter false
            }
            val root = sourceRoot(receipt) ?: return@filter false
            val inventory = (receipt["inventory_observation"] as? JsonObject)?.takeIf { it["schema"] == JsonPrimitive("github.inventory-observation.v1") }
            val revisions = listOfNotNull(args["commit_sha"], root["commit_sha"], inventory?.get("commit_sha"), receipt["source_head_sha"]).filter { it != JsonNull }
            revisions.isNotEmpty() && revisions.all { GitHubInventoryQuery.commitSha(it) == revision }
        }
    }

    private fun sourceRoot(receipt: JsonObject): JsonObject? {
        val envelope = receipt["result"] as? JsonObject ?: return null
        val value = if (envelope["observationTruncated"] == JsonPrimitive(true)) envelope["excerpt"] as? JsonObject ?: JsonObject(emptyMap()) else envelope
        return (value["data"] as? JsonObject) ?: value
    }

    private fun fromDispatchMetadata(receipt: JsonObject): JsonObject? {
        if (receipt["trusted"] != JsonPrimitive(true) || receipt["success"] != JsonPrimitive(true) || receipt["verification_eligible"] == JsonPrimitive(false)) return null
        val args = receipt["arguments"] as? JsonObject ?: return null
        if ("view_cursor" in args || (args["action"] as? JsonPrimitive)?.contentOrNull !in setOf("workflows", "branches", "list", "commits", "runs", "tags")) return null
        val execution = receipt["execution"] as? JsonObject ?: return null
        if (execution["schema"] != JsonPrimitive("github.dispatch.v1") || execution["parent_result_successful"] == JsonPrimitive(false)) return null
        val progress = execution["pagination_progress"] as? JsonObject ?: return null
        if (progress["schema"] != JsonPrimitive("github.pagination-progress.v1")) return null
        val fetched = (progress["upstream_rows_returned"] as? JsonPrimitive)?.longOrNull?.takeIf { it in 0..1000 } ?: return null
        val root = planningRoot(receipt)
        val page = (progress["remote_page"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return null
        val expectedPage = ((args["page"] ?: root?.get("page")) as? JsonPrimitive)?.longOrNull ?: 1L
        if (page != expectedPage) return null
        val field = fields.firstOrNull { root?.get(it) is JsonArray }
        val known = field?.let { root?.get(it) as? JsonArray }.orEmpty().filterIsInstance<JsonObject>()
        return buildJsonObject {
            put("schema", "github.inventory-observation.v1")
            field?.let { put("field", it) }
            put("fetched", fetched)
            put("record_keys", JsonArray(known.mapNotNull { item -> listOf("id", "number", "name", "sha").firstNotNullOfOrNull { item[it] as? JsonPrimitive } }.distinct()))
            put("record_keys_complete", false)
            put("displayed", JsonNull)
            put("consumed", JsonNull)
            put("consumption_status", "NOT_RECORDED")
            put("page", page)
            args["per_page"]?.let { put("per_page", it) }
            (progress["reported_total"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= fetched }?.let { put("total_count", it) }
            put("complete", false)
            if ((receipt["retained_remote_continuations"] as? JsonArray)?.isNotEmpty() == true) put("has_more", true)
            put("scope", "QUALIFIED_DISPATCH_PAGE_METADATA")
            put("source", "execution.pagination_progress")
            put("limitation", "Fetched counts are retained dispatch metadata. Missing record identities and source fields still require the original or an authorized source read.")
        }
    }
}
