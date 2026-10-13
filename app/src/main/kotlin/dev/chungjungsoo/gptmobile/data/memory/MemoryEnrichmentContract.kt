package dev.chungjungsoo.gptmobile.data.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object MemoryEnrichmentContract {
    fun parse(output: String, source: String? = null): JsonObject? {
        val text = output.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        if (root.keys != setOf("observations")) return null
        val rows = root["observations"] as? JsonArray ?: return null
        if (rows.size > 6) return null
        val quotes = mutableSetOf<String>()
        for (row in rows) {
            val observation = row as? JsonObject ?: return null
            if (observation.keys != setOf("quote", "kind")) return null
            val quote = (observation["quote"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val kind = (observation["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (quote.isBlank() || !quotes.add(quote) || source != null && quote !in source) return null
            if (kind !in setOf("preference", "profile", "project", "goal", "constraint", "interest")) return null
        }
        return root
    }
}
