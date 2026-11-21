package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** File pages and lossless JSON recovery share the existing scoped, issued-cursor store. */
internal object GitHubSourcePages {
    // Raw JSON fragment indices are bounded by the 4 MiB decompression limit.
    const val FIRST = 8 * 1024 * 1024
    private val revision = Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    fun supports(source: JsonElement): Boolean {
        val root = source as? JsonObject ?: return false
        val items = root["items"] as? JsonArray ?: return false
        val commit = root.text("commit_sha") ?: return false
        return revision.matches(commit) &&
            items.size in 1..8 &&
            items.all { raw ->
                val row = raw as? JsonObject ?: return@all false
                val start = (row["start_line"] as? JsonPrimitive)?.intOrNull ?: return@all false
                row.text("path")?.isNotBlank() == true &&
                    row.text("sha")?.matches(revision) == true &&
                    (row["content"] as? JsonPrimitive)?.isString == true &&
                    start > 0 &&
                    (row.text("commit_sha") ?: commit) == commit &&
                    ((row["start_offset"] as? JsonPrimitive)?.intOrNull in listOf(null, 0) || "content_start_line" in row)
            }
    }

    fun page(source: JsonObject, offset: Int, bytes: Int, continuations: List<JsonObject>, call: (Int, Int) -> JsonObject): JsonObject? {
        val inputs = source["items"] as JsonArray
        val slices = mutableListOf<JsonObject>()
        val end = FIRST + inputs.sumOf { (it as JsonObject).text("content").orEmpty().length + 1 }
        if (offset !in FIRST until end) return null
        val jumps = mutableListOf<JsonObject>()
        if (offset == FIRST && bytes >= 3072) {
            var base = FIRST
            var used = 0
            fun offer(row: JsonObject, line: Int, position: Int) {
                val recipe = buildJsonObject {
                    put("path", row.getValue("path"))
                    put("line", line)
                    put("call", call(position, bytes))
                }
                val size = recipe.toString().encodeToByteArray().size + 1
                if (jumps.size < 8 && used + size <= bytes / 4) {
                    jumps += recipe
                    used += size
                }
            }
            for (raw in inputs) {
                val row = raw as JsonObject
                val content = row.text("content").orEmpty()
                val line = ((row["content_start_line"] ?: row["start_line"]) as JsonPrimitive).intOrNull ?: return null
                if (base > FIRST) offer(row, line, base)
                if (inputs.size == 1 && content.length > bytes * 2) {
                    var lines = line
                    var threshold = bytes
                    for (i in content.indices) {
                        if (content[i] != '\n') continue
                        lines++
                        if (i >= threshold && i + 1 < content.length) {
                            offer(row, lines, base + i + 1)
                            threshold = i + maxOf(bytes, content.length / 6)
                            if (jumps.size >= 6) break
                        }
                    }
                }
                base += content.length + 1
            }
        }
        var next = offset
        fun envelope() = buildJsonObject {
            put("schema", GitHubResultViews.SCHEMA)
            put("operation", "github_read")
            put("commit_sha", source.getValue("commit_sha"))
            put("items", JsonArray(slices))
            if (jumps.isNotEmpty()) put("range_calls", JsonArray(jumps))
            put("source_page", true)
            put("local_page_complete", next == end)
            source["evidence_id"]?.let { put("evidence_id", it) }
            (source["failed_ranges"] as? JsonArray)?.let { put("failed_range_count", it.size) }
            (source["active_instructions"] as? JsonObject)?.let { put("instruction_files", (it["files"] as? JsonArray)?.size ?: 0) }
            put("recovery_call", call(offset, minOf(bytes, 1536)))
            put("original_call", call(0, bytes))
            if (next < end) {
                put("continuation_call", call(next, bytes))
            } else if (continuations.isNotEmpty()) {
                put("remote_continuations", JsonArray(continuations))
                if (continuations.size == 1) put("continuation_call", continuations.single().getValue("call"))
            }
        }
        var base = FIRST
        for (raw in inputs) {
            val row = raw as JsonObject
            val content = row.text("content").orEmpty()
            val after = base + content.length + 1
            if (next >= after) {
                base = after
                continue
            }
            val from = next - base
            val line = ((row["content_start_line"] ?: row["start_line"]) as JsonPrimitive).intOrNull ?: return null
            var firstLine = line
            for (i in 0 until from) if (content[i] == '\n') firstLine++
            fun place(to: Int) {
                val part = content.substring(from, to)
                next = if (to == content.length) after else base + to
                slices += buildJsonObject {
                    put("path", row.getValue("path"))
                    put("sha", row.getValue("sha"))
                    put("start_line", firstLine)
                    put("end_line", firstLine + part.count { it == '\n' } - if (part.endsWith('\n')) 1 else 0)
                    put("range_offset", ((row["start_offset"] as? JsonPrimitive)?.intOrNull ?: 0) + from)
                    put("content", part)
                    put("range_end", to == content.length)
                }
            }
            val maxEnd = minOf(content.length, from + bytes)
            var low = from
            var high = maxEnd
            var fit = -1
            while (low <= high) {
                val mid = (low + high) ushr 1
                val to = if (mid < content.length && content[mid].isLowSurrogate()) mid - 1 else mid
                place(to)
                val fits = envelope().toString().encodeToByteArray().size <= bytes
                slices.removeAt(slices.lastIndex)
                if (fits) {
                    fit = to
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            next = base + from
            if (fit < from || fit == from && from != content.length) break
            if (fit < content.length) {
                val newline = content.lastIndexOf('\n', fit - 1)
                if (newline >= from) fit = newline + 1
            }
            place(fit)
            if (fit < content.length) break
            base = after
        }
        return envelope().takeIf { slices.isNotEmpty() && it.toString().encodeToByteArray().size <= bytes }
    }
}
