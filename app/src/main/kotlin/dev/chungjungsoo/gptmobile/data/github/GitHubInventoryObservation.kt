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
            put("fetched", records.size)
            put("record_keys", JsonArray(identities))
            put("record_keys_complete", identities.size == records.size && identities.distinct().size == records.size)
            if (field in setOf("workflow_runs", "jobs")) {
                put(
                    "planning_records",
                    JsonArray(
                        records.filterIsInstance<JsonObject>().take(1000).map { row ->
                            JsonObject(
                                row.filterKeys { it in setOf("id", "run_id", "run_attempt", "head_sha", "status", "conclusion") } + buildJsonObject {
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
        val value = if (envelope["observationTruncated"] == JsonPrimitive(true)) envelope["excerpt"] as? JsonObject ?: return null else envelope
        val root = (value["data"] as? JsonObject) ?: value
        val inventory = receipt["inventory_observation"] as? JsonObject
        val field = (inventory?.get("field") as? JsonPrimitive)?.contentOrNull
        val records = inventory?.get("planning_records") as? JsonArray
        return if (field in setOf("workflow_runs", "jobs") && records != null) JsonObject(root + (requireNotNull(field) to records)) else root
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
