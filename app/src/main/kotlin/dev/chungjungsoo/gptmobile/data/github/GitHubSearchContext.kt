package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bounded source context. Heuristic declarations never claim compiler resolution. */
internal object GitHubSearchContext {
    private val declaration = Regex("\\b(class|interface|object|fun|def|function|struct|enum|trait)\\s+([A-Za-z_][A-Za-z_0-9]*)")

    fun extract(lines: List<String>, line: Int, radius: Int, maxExcerptBytes: Int = 8192) = buildJsonObject {
        require(lines.isNotEmpty() && line in 1..lines.size && radius in 0..100 && maxExcerptBytes >= 0)
        val at = (line - 1).coerceIn(0, lines.lastIndex.coerceAtLeast(0))
        val requestedStart = (at - radius).coerceAtLeast(0)
        val requestedEnd = (at + radius + 1).coerceAtMost(lines.size)
        var start = at
        var end = at
        var used = 0
        var lineTruncated = false
        val excerpts = mutableMapOf<Int, String>()
        fun add(index: Int): Boolean {
            val separator = if (excerpts.isEmpty()) 0 else 1
            val remaining = maxExcerptBytes - used - separator
            if (remaining <= 0) return false
            val value = GitHubRepositorySession.utf8Prefix(lines[index], minOf(500, remaining))
            used += separator + value.toByteArray(Charsets.UTF_8).size
            lineTruncated = lineTruncated || value != lines[index]
            excerpts[index] = value
            return true
        }
        if (add(at)) {
            end = at + 1
            while (start > requestedStart || end < requestedEnd) {
                var added = false
                if (start > requestedStart && add(start - 1)) {
                    start--
                    added = true
                }
                if (end < requestedEnd && add(end)) {
                    end++
                    added = true
                }
                if (!added) break
            }
        }
        put("start_line", start + 1)
        put("end_line", end)
        put("requested_start_line", requestedStart + 1)
        put("requested_end_line", requestedEnd)
        put("excerpt", (start until end).joinToString("\n") { excerpts.getValue(it) })
        put("truncated", start != requestedStart || end != requestedEnd || lineTruncated)
        put("resolution", "lexical_nearby_declaration; verify scope in source")
        val nearby = (at downTo (at - 80).coerceAtLeast(0)).firstNotNullOfOrNull { index ->
            declaration.find(lines[index].take(2000))?.let { index to it }
        }
        if (nearby != null) {
            val (index, match) = nearby
            val signature = lines[index].trim().take(1000)
            put("nearby_declaration", match.groupValues[2])
            put("declaration_kind", match.groupValues[1])
            put("declaration_line", index + 1)
            put("signature", signature)
            val parameters = signature.substringAfter('(', "").substringBefore(')', "")
            put("parameters_text", parameters)
            put("return_type_text", signature.substringAfter("):", "").substringBefore('=').substringBefore('{').trim())
            val docs = (index - 1 downTo (index - 12).coerceAtLeast(0)).takeWhile { i ->
                lines[i].trim().let { it.startsWith("//") || it.startsWith('*') || it.startsWith("/*") || it.startsWith('#') }
            }.reversed().joinToString("\n") { lines[it].take(300) }
            put("documentation", docs)
        }
    }

    fun related(path: String, paths: List<String>): JsonArray {
        val stem = path.substringAfterLast('/').substringBeforeLast('.').removeSuffix("Test")
        return JsonArray(paths.asSequence().filter { it != path && it.substringAfterLast('/').substringBeforeLast('.').removeSuffix("Test") == stem }.distinct().take(5).map(::JsonPrimitive).toList())
    }
}
