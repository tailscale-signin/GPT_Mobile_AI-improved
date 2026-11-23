package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Parse a bounded user selection; the Gateway still verifies every path against its pinned source. */
object RepositoryContextPackSelection {
    fun arguments(text: String, ranges: Boolean, engine: String, format: String, tokenLimit: String): JsonObject? {
        val selected = text.lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList()
        if (selected.size !in 1..60 || engine !in setOf("builtin", "repomix") || format !in setOf("markdown", "json", "xml")) return null
        val maximum = tokenLimit.trim().takeIf(String::isNotEmpty)?.toIntOrNull()
        if (tokenLimit.isNotBlank() && (maximum == null || maximum !in 128..131072)) return null
        val rows = mutableListOf<JsonObject>()
        val paths = mutableListOf<String>()
        for (value in selected) {
            if (ranges) {
                val match = Regex("^(.+):([0-9]+)-([0-9]+)$").matchEntire(value) ?: return null
                val path = match.groupValues[1]
                val start = match.groupValues[2].toIntOrNull() ?: return null
                val end = match.groupValues[3].toIntOrNull() ?: return null
                if (!path.isWorkspacePath() || start < 1 || end < start || end.toLong() - start >= 1000) return null
                paths += path
                rows += buildJsonObject {
                    put("path", path)
                    put("start_line", start)
                    put("end_line", end)
                }
            } else {
                if (!value.isWorkspacePath()) return null
                paths += value
            }
        }
        if (engine == "repomix" && paths.distinct().size != paths.size) return null
        return buildJsonObject {
            if (ranges) put("ranges", JsonArray(rows)) else put("paths", JsonArray(paths.map(::JsonPrimitive)))
            put("format", format)
            put("engine", engine)
            put("max_bytes", 65536)
            put("max_input_bytes", 2 * 1024 * 1024)
            maximum?.let { put("max_tokens", it) }
        }
    }
}
