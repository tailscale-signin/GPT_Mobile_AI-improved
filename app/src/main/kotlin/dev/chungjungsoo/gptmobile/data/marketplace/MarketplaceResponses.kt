package dev.chungjungsoo.gptmobile.data.marketplace

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Empty provider error fields are not errors; never display arbitrary provider error text. */
internal fun hasMeaningfulProviderError(value: JsonElement?): Boolean = when (value) {
    null, JsonNull -> false
    is JsonArray -> value.any(::hasMeaningfulProviderError)
    is JsonObject -> value.isNotEmpty()
    is JsonPrimitive -> value.booleanOrNull != false && value.content.isNotBlank()
}

internal fun retryAfterMillis(value: String?, nowMillis: Long = System.currentTimeMillis()): Long {
    val trimmed = value?.trim().orEmpty()
    val seconds = trimmed.toLongOrNull()
    val delay = if (seconds != null) {
        seconds.coerceIn(0, 86_400) * 1000
    } else {
        runCatching { ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis }.getOrNull()
    }
    return (delay ?: 60_000).coerceIn(1000, 86_400_000)
}

data class BoundedMarketplaceEvidence(val data: JsonElement, val truncated: Boolean)

/** Keep valid, bounded JSON and identify every omission. Preserve useful IDs/URLs before descriptions. */
internal fun boundMarketplaceEvidence(data: JsonElement, maxResults: Int, maxChars: Int = 80_000): BoundedMarketplaceEvidence {
    var remaining = maxChars.coerceAtLeast(32)
    var truncated = false
    val priority = listOf("id", "place_id", "fsq_place_id", "url", "website", "googleMapsUri", "name", "title", "attributions")
    fun visit(value: JsonElement, depth: Int, field: String = ""): JsonElement {
        if (depth > 12 || remaining < 16) {
            truncated = true
            remaining -= 4
            return JsonNull
        }
        return when (value) {
            is JsonObject -> {
                remaining -= 2
                val fields = linkedMapOf<String, JsonElement>()
                for ((key, child) in value.entries.sortedBy { priority.indexOf(it.key).takeIf { index -> index >= 0 } ?: priority.size }) {
                    val cost = JsonPrimitive(key).toString().length + 2
                    if (cost + 16 >= remaining || fields.size >= 64) {
                        truncated = true
                        continue
                    }
                    remaining -= cost
                    fields[key] = visit(child, depth + 1, key)
                }
                JsonObject(fields)
            }
            is JsonArray -> {
                remaining -= 2
                val children = mutableListOf<JsonElement>()
                val limit = if (depth == 0 || field in setOf("results", "records", "businesses", "events", "candidates", "places", "elements", "features")) maxResults.coerceIn(1, 10) else 64
                for (child in value) {
                    if (remaining < 16 || children.size >= limit) {
                        truncated = true
                        break
                    }
                    remaining--
                    children += visit(child, depth + 1)
                }
                JsonArray(children)
            }
            is JsonPrimitive -> {
                val encoded = value.toString()
                if (encoded.length > remaining || (value.isString && value.content.length > 4000)) {
                    truncated = true
                    // Dropping a field value is explicit; never forge a shortened ID or URL.
                    remaining -= 4
                    JsonNull
                } else {
                    remaining -= encoded.length
                    value
                }
            }
        }
    }
    return BoundedMarketplaceEvidence(visit(data, 0), truncated)
}
