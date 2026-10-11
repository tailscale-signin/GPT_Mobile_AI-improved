package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Bound all three representations before they enter replay, trace persistence, or a UI event. */
internal object ToolObservationRetention {
    fun bound(result: AgentToolResult, maxBytes: Int = 64 * 1024): AgentToolResult = result.copy(
        content = content(result.content, maxBytes),
        retainedContent = result.retainedContent?.let { content(it, maxBytes) },
        traceContent = result.traceContent?.let { content(it, minOf(maxBytes, 8000)) }
    )

    fun content(value: ToolResultContent, maxBytes: Int): ToolResultContent {
        val budget = maxBytes.coerceIn(0, 256 * 1024)
        return when (value) {
            is ToolResultContent.Text -> {
                if (budget < 128) return ToolResultContent.Text(truncateUtf8(value.text, budget))
                if (value.text.length <= budget / 4 || value.text.take(budget + 1).toByteArray().size <= budget && value.text.length <= budget) return value
                var referenceBytes = budget / 2 - 64
                val references = Regex("https?://[^\\s<>\\\"]{1,2048}(?=[\\s<>\\\"]|$)").findAll(value.text).take(32).map { it.value }.distinct().filter {
                    val bytes = it.toByteArray().size + 1
                    if (bytes <= referenceBytes) {
                        referenceBytes -= bytes
                        true
                    } else {
                        false
                    }
                }.joinToString("\n")
                val footer = "\n[Observation excerpt; remaining content omitted.]\n$references"
                ToolResultContent.Text(truncateUtf8(value.text, budget - footer.toByteArray().size) + footer)
            }
            is ToolResultContent.ResourceLinks -> {
                var remaining = budget
                val kept = value.links.asSequence().take(32).filter { it.uri.length <= 2048 }.map { it.copy(name = it.name?.take(256)) }.takeWhile {
                    remaining -= (it.uri.length + it.name.orEmpty().length) * 6 + 64
                    remaining >= 0
                }.toList()
                ToolResultContent.ResourceLinks(kept)
            }
            is ToolResultContent.Json -> {
                if (budget < 128) return ToolResultContent.Text("[Observation omitted]".take(budget))
                if (boundedJsonBytes(value.value, budget) <= budget) return value
                var remaining = budget / 4
                fun shrink(element: JsonElement, depth: Int): JsonElement {
                    if (remaining <= 8 || depth > 16) return JsonPrimitive("[omitted]")
                    remaining -= 8
                    return when (element) {
                        is JsonPrimitive -> {
                            val safe = truncateUtf8(element.content, (remaining.coerceAtLeast(0) / 6).coerceAtMost(4096))
                            remaining -= safe.length * 6
                            if (safe == element.content) element else JsonPrimitive(safe)
                        }
                        is JsonArray -> JsonArray(element.asSequence().take(64).takeWhile { remaining > 16 }.map { shrink(it, depth + 1) }.toList())
                        is JsonObject -> {
                            val fields = linkedMapOf<String, JsonElement>()
                            for ((key, child) in element) {
                                if (remaining <= 16 || fields.size >= 64) break
                                val safeKey = key.take(128)
                                remaining -= safeKey.length * 6 + 4
                                fields[safeKey] = shrink(child, depth + 1)
                            }
                            JsonObject(fields)
                        }
                    }
                }
                val shrunk = shrink(value.value, 0)
                ToolResultContent.Json(
                    kotlinx.serialization.json.buildJsonObject {
                        put("observationTruncated", JsonPrimitive(true))
                        put("excerpt", shrunk)
                    }
                )
            }
        }
    }
}
