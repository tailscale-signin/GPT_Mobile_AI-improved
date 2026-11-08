package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

private const val DIAGNOSTIC_EVENT_SCHEMA = "gptmobile.diagnostic-event.v1"
private const val MAXIMUM_EVENT_CHARACTERS = 6000
private const val MAXIMUM_EVENT_BYTES = 6000
private val diagnosticFieldName = Regex("[A-Za-z_][A-Za-z0-9_]{0,79}")
private val privateDiagnosticField = Regex("(?i)^(?:query|prompt|arguments|result|document|content|text|error_body|authorization|proxy_authorization|cookie|set_cookie|api_?key|access_?token|refresh_?token|password|client_?secret|credential|credentials)$")

/** Values are serialized before they enter the recorder; Boolean and numeric evidence stays typed. */
internal data class StructuredDiagnosticEvent(val name: String, val fields: JsonObject) {
    fun summary(): String = buildString {
        append(name)
        fields.forEach { (key, value) ->
            append(" · ")
            append(key)
            append('=')
            append((value as? JsonPrimitive)?.content ?: value.toString())
        }
    }
}

internal fun parseStructuredDiagnosticEvent(payload: String): StructuredDiagnosticEvent? {
    if (!payload.startsWith('{') || payload.length > MAXIMUM_EVENT_CHARACTERS) return null
    val value = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null
    if (value["schema"] != JsonPrimitive(DIAGNOSTIC_EVENT_SCHEMA)) return null
    val name = (value["event"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_.:-]{0,127}")) } ?: return null
    val fields = value["fields"] as? JsonObject ?: return null
    return StructuredDiagnosticEvent(name, fields)
}

/**
 * This API accepts diagnostic metadata only. Opaque objects are never stringified,
 * private payload keys are omitted, and the bounded result is always complete JSON.
 */
internal fun structuredDiagnosticEventMessage(eventName: String, fields: Map<String, Any?>): String {
    var remainingNodes = 256
    var omittedFields = 0
    fun safeValue(value: Any?, depth: Int): JsonElement {
        if (depth > 8 || --remainingNodes < 0) {
            omittedFields++
            return JsonPrimitive("[diagnostic value omitted]")
        }
        fun safeObject(values: Map<*, *>): JsonObject = buildJsonObject {
            values.entries.take(64).forEach { (key, child) ->
                val name = key as? String
                if (name == null || !diagnosticFieldName.matches(name) || privateDiagnosticField.matches(name)) {
                    omittedFields++
                } else {
                    put(name, safeValue(child, depth + 1))
                }
            }
            omittedFields += (values.size - 64).coerceAtLeast(0)
        }
        return when (value) {
            null, JsonNull -> JsonNull
            is JsonObject -> safeObject(value)
            is Map<*, *> -> safeObject(value)
            is JsonArray -> JsonArray(value.take(64).map { safeValue(it, depth + 1) }).also { omittedFields += (value.size - 64).coerceAtLeast(0) }
            is Collection<*> -> JsonArray(value.take(64).map { safeValue(it, depth + 1) }).also { omittedFields += (value.size - 64).coerceAtLeast(0) }
            is JsonPrimitive -> if (value.isString) safeValue(value.content, depth + 1) else diagnosticJsonValue(value)
            is Boolean -> JsonPrimitive(value)
            is Byte, is Short, is Int, is Long -> JsonPrimitive((value as Number).toLong())
            is Float -> value.takeIf(Float::isFinite)?.let(::JsonPrimitive) ?: JsonNull
            is Double -> value.takeIf(Double::isFinite)?.let(::JsonPrimitive) ?: JsonNull
            is Enum<*> -> JsonPrimitive(value.name)
            is String -> JsonPrimitive(diagnosticMessageForStorage(value).let { if (it.length <= 512) it else it.take(480) + " [diagnostic value truncated]" })
            else -> {
                omittedFields++
                JsonPrimitive("[unsupported diagnostic type]")
            }
        }
    }
    val name = eventName.replace(Regex("[^A-Za-z0-9_.:-]"), "_").take(128).let { if (it.firstOrNull()?.isLetter() == true) it else "UNNAMED_EVENT" }
    val safeFields = (safeValue(fields, 0) as JsonObject).toMutableMap()
    fun encode(): String = buildJsonObject {
        put("schema", DIAGNOSTIC_EVENT_SCHEMA)
        put("event", name)
        put("fields", JsonObject(safeFields + if (omittedFields > 0) mapOf("_fieldCountOmitted" to JsonPrimitive(omittedFields)) else emptyMap()))
    }.toString()
    var encoded = encode()
    // The durable ring is byte bounded. Character-only bounds could split JSON
    // when multibyte model names or diagnostics expand during UTF-8 encoding.
    while (encoded.toByteArray(Charsets.UTF_8).size > MAXIMUM_EVENT_BYTES && safeFields.isNotEmpty()) {
        safeFields.remove(safeFields.keys.last())
        omittedFields++
        encoded = encode()
    }
    return encoded
}
