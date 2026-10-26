package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val jsonNumber = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

/**
 * The tree parser accepts bare literals inside log-style lists, even in strict mode.
 * Quote those retained values before serialization so Android's strict reader can
 * consume both new diagnostics and historical trees containing [profile-a,profile-b].
 */
internal fun diagnosticJsonValue(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.mapValues { (_, child) -> diagnosticJsonValue(child) })
    is JsonArray -> JsonArray(value.map(::diagnosticJsonValue))
    is JsonPrimitive -> if (value.isString || value === JsonNull || value.content in setOf("true", "false") || jsonNumber.matches(value.content)) value else JsonPrimitive(value.content)
}
