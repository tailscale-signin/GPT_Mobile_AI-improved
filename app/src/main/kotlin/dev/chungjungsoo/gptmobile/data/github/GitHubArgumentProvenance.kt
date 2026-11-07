package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.diagnostics.redactChatDebugData
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Diagnostic selectors are retained; credential values and repository content are not. */
internal object GitHubArgumentProvenance {
    private val contentFields = setOf("body", "body_text", "content", "content_base64", "text", "source", "markdown", "patch", "diff", "message", "review", "query", "old", "new", "new_code_text")

    fun sanitize(arguments: JsonObject): JsonObject {
        var remainingBytes = 8 * 1024
        var incomplete = false
        fun omitted(): JsonElement {
            incomplete = true
            remainingBytes -= 24
            return JsonPrimitive("[omitted]")
        }
        fun bounded(value: JsonElement, depth: Int): JsonElement {
            if (depth > 8 || remainingBytes <= 0) return omitted()
            return when (value) {
                is JsonObject -> {
                    if (value.size > 64) incomplete = true
                    JsonObject(
                        value.entries.take(64).mapNotNull { (key, child) ->
                            val keyBytes = key.encodeToByteArray().size + 8
                            if (keyBytes > minOf(136, remainingBytes)) {
                                incomplete = true
                                return@mapNotNull null
                            }
                            remainingBytes -= keyBytes
                            key to if (key.lowercase(java.util.Locale.ROOT) in contentFields) omitted() else bounded(child, depth + 1)
                        }.toMap()
                    )
                }
                is JsonArray -> {
                    if (value.size > 32) incomplete = true
                    JsonArray(value.take(32).map { bounded(it, depth + 1) })
                }
                else -> {
                    val size = value.toString().encodeToByteArray().size
                    if (size > minOf(1024, remainingBytes)) omitted() else value.also { remainingBytes -= size }
                }
            }
        }
        val safe = bounded(redactChatDebugData(arguments), 0) as JsonObject
        val fields = safe.toMutableMap()
        // Include encoding and omission markers in the byte bound, including
        // adversarial escaped keys; never truncate a selector into another one.
        while (fields.isNotEmpty() && JsonObject(fields).toString().encodeToByteArray().size > 8 * 1024 - 64) {
            fields.remove(fields.keys.last())
            incomplete = true
        }
        if (incomplete) fields["_projection_incomplete"] = JsonPrimitive(true)
        return JsonObject(fields)
    }
}
