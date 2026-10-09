package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Normalize common model wrappers; selected action tools still validate and authorize execution. */
internal fun memoryActionInput(arguments: JsonObject, schema: JsonObject): JsonObject? {
    val nested = arguments["input"]
    if (nested is JsonObject) return nested
    if (nested is JsonPrimitive && nested.isString && nested.content.length <= 16000) {
        return runCatching { Json.parseToJsonElement(nested.content) as? JsonObject }.getOrNull()
    }
    if (nested != null) return null
    val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
    val flat = arguments.filterKeys { it != "action" }
    if (flat.keys.any { it !in properties }) return null
    val required = (schema["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
    if (required.any { it !in flat }) return null
    return JsonObject(flat)
}
