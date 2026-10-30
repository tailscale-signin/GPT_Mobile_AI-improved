package dev.chungjungsoo.gptmobile.data.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object MemoryEnrichmentContract {
    fun repair(output: String, source: String): JsonObject? {
        if (output.length > 32_768) return null
        val text = output.trim().removePrefix("```JSON").removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end < start || text.substring(0, start).contains('{') || text.substring(end + 1).contains('}')) return null
        val root = runCatching { Json.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject }.getOrNull() ?: return null
        if (root.keys != setOf("observations")) return null
        val rows = root["observations"] as? JsonArray ?: return null
        if (rows.size > 6) return null
        val seen = mutableSetOf<String>()
        val normalized = mutableListOf<JsonObject>()
        for (row in rows) {
            val observation = row as? JsonObject ?: return null
            if (observation.keys != setOf("quote", "kind")) return null
            val quote = (observation["quote"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val kind = (observation["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (quote.isBlank() || quote !in source) return null
            if (seen.add(quote)) normalized += JsonObject(observation + ("kind" to JsonPrimitive(kind.lowercase(java.util.Locale.ROOT))))
        }
        return parse(JsonObject(mapOf("observations" to JsonArray(normalized))).toString(), source)
    }

    fun parse(output: String, source: String? = null): JsonObject? {
        if (output.length > 32_768) return null
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
            if (kind !in setOf("preference", "profile", "project", "goal", "constraint", "interest", "intent", "decision", "requirement", "failure", "correction")) return null
        }
        return root
    }
}
