package dev.chungjungsoo.gptmobile.presentation.ui.setting

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Patch only edited known fields. Preserve unsupported fields, including nested provider options. */
internal fun mergeProviderOptionsDraft(original: String?, before: String, after: String): String {
    if (before == after && !original.isNullOrBlank()) return original
    val json = Json { isLenient = true }
    val baseline = runCatching { json.parseToJsonElement(before) as JsonObject }.getOrThrow()
    val edited = runCatching { json.parseToJsonElement(after) as JsonObject }.getOrThrow()
    val source = original?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        ?: baseline
    fun patch(stored: JsonObject, old: JsonObject, next: JsonObject): JsonObject {
        val result = stored.toMutableMap()
        (old.keys + next.keys).forEach { key ->
            val previous = old[key]
            val value: JsonElement? = next[key]
            if (previous != value) {
                when {
                    value == null -> result.remove(key)
                    previous is JsonObject && value is JsonObject -> result[key] = patch(result[key] as? JsonObject ?: previous, previous, value)
                    else -> result[key] = value
                }
            }
        }
        return JsonObject(result)
    }
    return patch(source, baseline, edited).toString()
}
