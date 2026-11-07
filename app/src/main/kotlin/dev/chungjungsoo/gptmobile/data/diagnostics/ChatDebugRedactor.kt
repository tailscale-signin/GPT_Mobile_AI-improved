package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Preserve large results and token-count fields; redact secrets, including embedded JSON payloads. */
internal fun redactChatDebugData(element: JsonElement, credentials: List<String> = emptyList()): JsonElement {
    val knownCredentials = credentials.filter { it.length >= 4 }.distinct()
    fun clean(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> {
            var changed: MutableMap<String, JsonElement>? = null
            value.forEach { (key, child) ->
                val safe = if (sensitive.matches(key)) JsonPrimitive("[redacted]") else clean(child)
                if (safe !== child) {
                    if (changed == null) changed = LinkedHashMap(value)
                    changed[key] = safe
                }
            }
            changed?.let(::JsonObject) ?: value
        }
        is JsonArray -> {
            var changed: MutableList<JsonElement>? = null
            value.forEachIndexed { index, child ->
                val safe = clean(child)
                if (safe !== child) {
                    if (changed == null) changed = value.toMutableList()
                    changed[index] = safe
                }
            }
            changed?.let(::JsonArray) ?: value
        }
        is JsonPrimitive -> if (!value.isString) {
            val normalized = diagnosticJsonValue(value)
            if (normalized is JsonPrimitive && normalized.isString) clean(normalized) else normalized
        } else {
            val text = value.content
            val embedded = if (text.trimStart().startsWith("{") || text.trimStart().startsWith("[")) runCatching { Json.parseToJsonElement(text) }.getOrNull() else null
            if (embedded is JsonObject || embedded is JsonArray) {
                JsonPrimitive(clean(embedded).toString())
            } else {
                var safe = text
                knownCredentials.forEach { safe = safe.replace(it, "[redacted]") }
                safe = safe.replace(urlCredentials, "$1[redacted]@")
                safe = urlSecret.replace(safe, "$1[redacted]")
                safe = bearer.replace(safe, "$1 [redacted]")
                safe = assignment.replace(safe, "$1[redacted]")
                safe = knownKey.replace(safe, "[redacted]")
                if (safe == text) value else JsonPrimitive(safe)
            }
        }
    }
    return clean(element)
}

private val sensitive = Regex("(?i)^(?:token|secretRef|protectedReference|password|secret|authorization|proxy.authorization|cookie|set.cookie|(?:x[-_])?(?:goog[-_])?api.?key|access.?token|refresh.?token|client.?secret|credential|credentials|lastShareToken)$")
private val bearer = Regex("(?i)\\b(bearer|basic)\\s+[a-z0-9._~+/=-]+")
private val knownKey = Regex("\\b(?:sk-|hf_|ghp_|github_pat_)[A-Za-z0-9_-]{8,}")
private val assignment = Regex("(?i)((?:api.?key|access.?token|refresh.?token|password|client.?secret|authorization)\\s*[:=]\\s*[\"']?)[^\"'\\s,};&]+")
private val urlSecret = Regex("(?i)([?&](?:api.?key|token|access.?token|auth|secret|signature|sig|key)=)[^&#\\s]+")
private val urlCredentials = Regex("(?i)(https?://)[^/\\s@]+@")
