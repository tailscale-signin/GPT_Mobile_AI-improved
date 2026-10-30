package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bounded source context. Heuristic declarations never claim compiler resolution. */
internal object GitHubSearchContext {
    private val declaration = Regex("\\b(class|interface|object|fun|def|function|struct|enum|trait)\\s+([A-Za-z_][A-Za-z_0-9]*)")

    fun extract(lines: List<String>, line: Int, radius: Int) = buildJsonObject {
        val at = (line - 1).coerceIn(0, lines.lastIndex.coerceAtLeast(0))
        val start = (at - radius).coerceAtLeast(0)
        val end = (at + radius + 1).coerceAtMost(lines.size)
        put("start_line", start + 1)
        put("end_line", end)
        put("excerpt", lines.subList(start, end).joinToString("\n") { it.take(500) })
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
