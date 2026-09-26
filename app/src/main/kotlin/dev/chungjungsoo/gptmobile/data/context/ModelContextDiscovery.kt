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
    val candidates = ((root["data"] ?: root["models"]) as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
    val selected = candidates.firstOrNull { ((it["id"] ?: it["name"]) as? JsonPrimitive)?.contentOrNull == model } ?: root
    val values = listOf(selected, selected["meta"] as? JsonObject, selected["default_generation_settings"] as? JsonObject)
        .filterNotNull().flatMap { item -> listOf("context_length", "max_context_length", "n_ctx", "context_window").mapNotNull { (item[it] as? JsonPrimitive)?.intOrNull } }
    return values.filter { it in 256..1_048_576 }.minOrNull()
}
