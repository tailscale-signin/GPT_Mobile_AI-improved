package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Host validation shared by native callbacks and ordinary tools. Never guesses identifiers. */
internal object ToolArgumentValidator {
    fun errors(arguments: JsonObject, schema: JsonObject): List<String> = validate(arguments, schema, "$", 0).take(12)

    private fun validate(value: JsonElement, schema: JsonObject, path: String, depth: Int): List<String> {
        if (depth > 24) return listOf("$path: schema nesting exceeds validation limit")
        val errors = mutableListOf<String>()
        fun matches(type: String): Boolean = when (type) {
            "object" -> value is JsonObject
            "array" -> value is JsonArray
            "string" -> value is JsonPrimitive && value.isString
            "integer" -> value is JsonPrimitive && !value.isString && value.doubleOrNull?.let { it.isFinite() && it % 1.0 == 0.0 } == true
            "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull?.isFinite() == true
            "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
            "null" -> value == JsonNull
            else -> false
        }
        val types = when (val type = schema["type"]) {
            is JsonPrimitive -> listOf(type.content)
            is JsonArray -> type.filterIsInstance<JsonPrimitive>().map { it.content }
            else -> emptyList()
        }
        if (types.isNotEmpty() && types.none(::matches)) return listOf("$path: expected ${types.joinToString(" or ")}")
        (schema["enum"] as? JsonArray)?.let { allowed ->
            if (value !in allowed) {
                errors += "$path: unsupported value; allowed: ${allowed.take(20).joinToString().take(500)}"
            }
        }
        schema["const"]?.let { if (value != it) errors += "$path: must equal the declared constant" }
        for (kind in listOf("allOf", "anyOf", "oneOf")) {
            (schema[kind] as? JsonArray)?.filterIsInstance<JsonObject>()?.let { options ->
                val matching = options.count { validate(value, it, path, depth + 1).isEmpty() }
                if ((kind == "allOf" && matching != options.size) || (kind == "anyOf" && matching == 0) || (kind == "oneOf" && matching != 1)) errors += "$path: $kind constraint failed"
            }
        }
        if (value is JsonObject) {
            val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
            (schema["required"] as? JsonArray)?.filterIsInstance<JsonPrimitive>()?.forEach { if (it.content !in value) errors += "$path.${it.content}: required field missing" }
            value.forEach { (key, child) ->
                val childSchema = properties[key] as? JsonObject ?: schema["additionalProperties"] as? JsonObject
                if (childSchema != null) {
                    errors += validate(child, childSchema, "$path.$key", depth + 1)
                } else if (schema["additionalProperties"] == JsonPrimitive(false) && key !in properties) {
                    errors += "$path.$key: undeclared field"
                }
            }
        }
        if (value is JsonArray) {
            (schema["minItems"] as? JsonPrimitive)?.intOrNull?.let { if (value.size < it) errors += "$path: too few items" }
            (schema["maxItems"] as? JsonPrimitive)?.intOrNull?.let { if (value.size > it) errors += "$path: too many items" }
            (schema["items"] as? JsonObject)?.let { item -> value.forEachIndexed { index, child -> errors += validate(child, item, "$path[$index]", depth + 1) } }
        }
        if (value is JsonPrimitive && value.isString) {
            (schema["minLength"] as? JsonPrimitive)?.intOrNull?.let { if (value.content.length < it) errors += "$path: too short" }
            (schema["maxLength"] as? JsonPrimitive)?.intOrNull?.let { if (value.content.length > it) errors += "$path: too long" }
            (schema["pattern"] as? JsonPrimitive)?.contentOrNull?.let { pattern ->
                if (runCatching { Regex(pattern).containsMatchIn(value.content) }.getOrDefault(false).not()) errors += "$path: pattern mismatch"
            }
        }
        if (value is JsonPrimitive && !value.isString) {
            value.doubleOrNull?.let { number ->
                (schema["minimum"] as? JsonPrimitive)?.doubleOrNull?.let { if (number < it) errors += "$path: below minimum" }
                (schema["maximum"] as? JsonPrimitive)?.doubleOrNull?.let { if (number > it) errors += "$path: above maximum" }
            }
        }
        return errors
    }
}
