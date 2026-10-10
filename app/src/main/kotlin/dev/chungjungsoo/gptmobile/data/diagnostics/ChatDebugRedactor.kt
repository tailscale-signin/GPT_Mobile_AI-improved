package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Preserve large results and token-count fields; redact secrets, including embedded JSON payloads. */
internal fun redactChatDebugData(element: JsonElement, credentials: List<String> = emptyList()): JsonElement {
    val sensitive = Regex("(?i)^(?:token|secretRef|protectedReference|password|secret|authorization|proxy.authorization|cookie|set.cookie|(?:x[-_])?(?:goog[-_])?api.?key|access.?token|refresh.?token|client.?secret|credential|credentials|lastShareToken)$")
    val bearer = Regex("(?i)\\b(bearer|basic)\\s+[a-z0-9._~+/=-]+")
    val knownKey = Regex("\\b(?:sk-|hf_|ghp_|github_pat_)[A-Za-z0-9_-]{8,}")
    val assignment = Regex("(?i)((?:api.?key|access.?token|refresh.?token|password|client.?secret|authorization)\\s*[:=]\\s*[\"']?)[^\"'\\s,};&]+")
    val urlSecret = Regex("(?i)([?&](?:api.?key|token|access.?token|auth|secret|signature|sig|key)=)[^&#\\s]+")
    fun clean(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, child) -> if (sensitive.matches(key)) JsonPrimitive("[redacted]") else clean(child) })
        is JsonArray -> JsonArray(value.map(::clean))
        is JsonPrimitive -> if (!value.isString) {
            value
        } else {
            val text = value.content
            val embedded = if (text.trimStart().startsWith("{") || text.trimStart().startsWith("[")) runCatching { Json.parseToJsonElement(text) }.getOrNull() else null
            if (embedded is JsonObject || embedded is JsonArray) {
                JsonPrimitive(clean(embedded).toString())
            } else {
                var safe = text
                credentials.filter { it.length >= 4 }.distinct().forEach { safe = safe.replace(it, "[redacted]") }
                safe = safe.replace(Regex("(?i)(https?://)[^/\\s@]+@"), "$1[redacted]@")
                safe = urlSecret.replace(safe, "$1[redacted]")
                safe = bearer.replace(safe, "$1 [redacted]")
                safe = assignment.replace(safe, "$1[redacted]")
                JsonPrimitive(knownKey.replace(safe, "[redacted]"))
            }
        }
    }
    return clean(element)
}
