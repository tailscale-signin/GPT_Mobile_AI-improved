package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Translate an unassigned legacy MCP search to the current authorized aggregate schema. */
internal fun legacySearchArguments(arguments: JsonObject): JsonObject {
    fun first(vararg names: String): JsonElement? = names.firstNotNullOfOrNull { arguments[it]?.takeUnless { value -> value == JsonNull } }
    fun integer(value: JsonElement?): JsonElement? = (value as? JsonPrimitive)?.intOrNull?.let(::JsonPrimitive) ?: value
    fun domains(value: JsonElement?): JsonElement? =
        if (value is JsonPrimitive && value.isString) {
            JsonArray(value.content.split(',').map(String::trim).filter(String::isNotEmpty).map(::JsonPrimitive))
        } else {
            value
        }
    val count = integer(first("maxResults", "max_results", "numResults", "num_results", "count", "limit", "num"))
    val requestedCount = (count as? JsonPrimitive)?.intOrNull
    val total = integer(first("totalResults", "total_results"))
    return JsonObject(
        buildMap {
            first("query", "search_query", "q")?.let { put("query", it) }
            if (requestedCount != null && requestedCount > 10) {
                put("maxResults", JsonPrimitive(10))
                if (total == null) put("totalResults", JsonPrimitive(requestedCount.coerceAtMost(50)))
            } else {
                count?.let { put("maxResults", it) }
            }
            total?.let { put("totalResults", it) }
            integer(first("recencyDays", "recency_days"))?.let { put("recencyDays", it) }
            domains(first("includeDomains", "include_domains"))?.let { put("includeDomains", it) }
            domains(first("excludeDomains", "exclude_domains"))?.let { put("excludeDomains", it) }
        }
    )
}
