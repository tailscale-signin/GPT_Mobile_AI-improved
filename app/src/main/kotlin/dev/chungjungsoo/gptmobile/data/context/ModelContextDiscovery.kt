package dev.chungjungsoo.gptmobile.data.context

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Only accept explicit context fields for the selected model; never infer limits from its name. */
internal fun discoverContextCeiling(body: String, model: String): Int? {
    if (body.length > 1_000_000) return null
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    if (model.isBlank()) return null
    val catalog = root["data"] ?: root["models"]
    val candidates = when (catalog) {
        null -> listOf(root)
        is JsonArray -> catalog.filterIsInstance<JsonObject>()
        else -> return null
    }
    val selected = candidates.filter { ((it["id"] ?: it["name"] ?: it["model"]) as? JsonPrimitive)?.contentOrNull == model }
    val fields = listOf("context_length", "max_context_length", "n_ctx", "context_window")
    return selected.flatMap { item ->
        listOfNotNull(item, item["meta"] as? JsonObject, item["default_generation_settings"] as? JsonObject).flatMap { settings ->
            fields.mapNotNull { (settings[it] as? JsonPrimitive)?.intOrNull }
        }
    }.filter { it in 256..1_048_576 }.minOrNull()
}
