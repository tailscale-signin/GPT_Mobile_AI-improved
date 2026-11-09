package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Count the canonical returned set before model/display projection can omit records. */
internal object GitHubInventoryObservation {
    private val fields = listOf("workflows", "items", "pull_requests", "workflow_runs", "tags", "recent_commits", "commits", "issues", "releases", "branches")

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
}
