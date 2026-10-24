package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.Serializable

@Serializable
data class CombinedSourceDisposition(
    val profileUid: String,
    val sourceDigest: String,
    val sourceStart: Int,
    val sourceEnd: Int,
    val finalStart: Int,
    val finalEnd: Int,
    val disposition: String
)

/** Source accounting is deterministic text preservation, never a factual verification claim. */
internal object CombinedSourcePreservation {
    data class Block(val start: Int, val end: Int, val text: String)
    data class Result(val text: String, val passages: List<CombinedPassageRecord>, val dispositions: List<CombinedSourceDisposition>, val fragments: Int)

    /** Keep tables, lists and fenced code intact; offsets refer to the unchanged UTF-16 source. */
    fun blocks(text: String): List<Block> {
        val result = mutableListOf<Block>()
        var start = 0
        var position = 0
        var fence: Char? = null
        var fenceLength = 0
        fun flush(end: Int) {
            var from = start
            var to = end
            // Trim separator lines, preserving indentation and trailing spaces
            // inside the first/last nonblank line (both can be Markdown syntax).
            while (from < to) {
                val lineEnd = text.indexOf('\n', from).takeIf { it in from until to } ?: to
                if (!text.substring(from, lineEnd).isBlank()) break
                from = (lineEnd + 1).coerceAtMost(to)
            }
            while (to > from) {
                if (text[to - 1] == '\n') {
                    to--
                    continue
                }
                val lineStart = text.lastIndexOf('\n', to - 1).coerceAtLeast(from - 1) + 1
                if (!text.substring(lineStart, to).isBlank()) break
                to = lineStart
            }
            if (from < to) result += Block(from, to, text.substring(from, to))
            start = end
        }
        text.splitToSequence('\n').forEach { line ->
            val trimmed = line.trimStart()
            val marker = trimmed.firstOrNull()
            val markerLength = if (marker == '`' || marker == '~') trimmed.takeWhile { it == marker }.length else 0
            if (markerLength >= 3) {
                if (fence == null) {
                    fence = marker
                    fenceLength = markerLength
                } else if (marker == fence && markerLength >= fenceLength && trimmed.drop(markerLength).isBlank()) {
                    fence = null
                }
            }
            val end = (position + line.length + 1).coerceAtMost(text.length)
            if (line.isBlank() && fence == null) flush(end)
            position = end
        }
        flush(text.length)
        return result
    }

    fun assemble(draft: String, sources: List<CombinedModelResponse>): Result {
        data class Occurrence(val source: CombinedModelResponse, val block: Block, val digest: String)
        val grouped = linkedMapOf<String, MutableList<Occurrence>>()
        sources.forEach { source ->
            val digest = researchHash(source.content)
            blocks(source.content).forEach { block ->
                grouped.getOrPut("$digest:${block.start}:${block.end}") { mutableListOf() } += Occurrence(source, block, digest)
            }
        }
        val answer = StringBuilder(draft.trimEnd())
        val original = answer.toString()
        val passages = mutableListOf<CombinedPassageRecord>()
        val dispositions = mutableListOf<CombinedSourceDisposition>()
        var fragments = 0
        grouped.forEach { (_, copies) ->
            val first = copies.first()
            val text = first.block.text
            // A matching sentence alone may refer to a different heading, person or
            // denominator. Merge only when the entire surrounding source also matches.
            val sourceOffset = original.indexOf(first.source.content)
            val existing = if (sourceOffset >= 0) Block(sourceOffset + first.block.start, sourceOffset + first.block.end, text) else null
            val start: Int
            val end: Int
            if (existing != null) {
                start = existing.start
                end = existing.end
            } else {
                if (fragments == 0) {
                    if (answer.isNotEmpty()) answer.append("\n\n")
                    answer.append("## Retained source details\n\nThese attributed excerpts preserve material that could not be safely mapped into the overview. They are contributor claims, not independent verification.")
                }
                val names = copies.map { it.source.platformName.ifBlank { it.source.platformUid } }.distinct()
                    .joinToString(" · ") { it.replace(Regex("[\\r\\n*_`\\[\\]<>#]"), " ") }
                answer.append("\n\n**").append(names).append("**\n\n")
                start = answer.length
                answer.append(text)
                end = answer.length
                fragments++
            }
            val owners = copies.mapTo(linkedSetOf()) { it.source.platformUid }
            val digest = researchHash(text)
            passages += CombinedPassageRecord(start, end, digest, owners, origin = "EXACT_SOURCE_FRAGMENT", passageId = "source:$start:$digest", semanticLinkStatus = "CONSERVATIVE_LINK_CHECKED", linkVersion = 1, links = listOf(CombinedPassageLink(start, end, digest, owners, emptySet())))
            copies.forEachIndexed { index, occurrence ->
                dispositions += CombinedSourceDisposition(
                    occurrence.source.platformUid,
                    occurrence.digest,
                    occurrence.block.start,
                    occurrence.block.end,
                    start,
                    end,
                    if (index > 0) {
                        "MERGED_DUPLICATE"
                    } else if (existing != null) {
                        "INCLUDED"
                    } else {
                        "RETAINED_FRAGMENT"
                    }
                )
            }
        }
        return Result(answer.toString(), passages, dispositions, fragments)
    }
}
