package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.conversation.ConversationSubject
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.util.stripAssistantErrorNote
import java.text.Normalizer
import java.util.Locale

/** Align contributions by section and chronology without a lossy extra model request. */
internal fun mergeCombinedResponses(sources: List<CombinedModelResponse>): String {
    val combined = ResponseSection()
    sources.forEach { source ->
        val text = ConversationSubject.withoutMetadata(stripAssistantErrorNote(source.content)).trim()
        mergeSections(combined, parseSections(text))
    }
    return renderSection(combined).trim()
}

private data class ResponseSection(
    val heading: String? = null,
    val title: String = "",
    val level: Int = 0,
    val blocks: MutableList<String> = mutableListOf(),
    val children: MutableList<ResponseSection> = mutableListOf()
) {
    val tokens = headingTokens(title)
    val year = startYear(title)
    val dates = Regex("\\b\\d{3,4}\\b").findAll(title).map { it.value.toInt() }.toList()
}

private val markdownHeading = Regex("^(#{1,6})\\s+(.+?)(?:\\s+#+)?$")
private val boldHeading = Regex("^\\*\\*([^*]+)\\*\\*:?$")
private val listStart = Regex("^(?:[-*+] |\\d+[.)] )")
private val codeFence = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")

private fun parseSections(text: String): ResponseSection {
    val root = ResponseSection()
    val stack = mutableListOf(root)
    val block = StringBuilder()
    var fence: Pair<Char, Int>? = null
    fun flush() {
        if (block.isNotBlank()) stack.last().blocks += block.toString().trimEnd()
        block.clear()
    }
    val lines = text.lines()
    lines.forEachIndexed { index, line ->
        val trimmed = line.trim()
        val fenceMatch = codeFence.matchEntire(line)
        val marker = fenceMatch?.groupValues?.get(1)
        val indented = line.startsWith("    ") || line.startsWith('\t')
        val heading = if (fence == null && marker == null && !indented) markdownHeading.matchEntire(trimmed) else null
        val bold = if (fence == null && marker == null && !indented && (index == 0 || lines[index - 1].isBlank()) && (index == lines.lastIndex || lines[index + 1].isBlank())) boldHeading.matchEntire(trimmed) else null
        if (heading != null || bold != null) {
            flush()
            val level = heading?.groupValues?.get(1)?.length ?: 2
            val title = heading?.groupValues?.get(2) ?: bold!!.groupValues[1]
            while (stack.size > 1 && stack.last().level >= level) stack.removeAt(stack.lastIndex)
            val section = ResponseSection(trimmed, title, level)
            stack.last().children += section
            stack += section
        } else {
            if (marker != null) {
                val open = fence
                if (open == null) {
                    fence = marker.first() to marker.length
                } else if (marker.first() == open.first && marker.length >= open.second && fenceMatch.groupValues[2].isBlank()) {
                    fence = null
                }
            }
            if (line.isBlank() && fence == null) flush() else block.appendLine(line)
        }
    }
    flush()
    return root
}

private fun headingTokens(title: String): Set<String> {
    val normalized = Normalizer.normalize(title, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("\\([^)]*\\d[^)]*\\)"), " ")
        .replace(Regex("\\bmiddle\\s+ages\\b"), "medieval")
        .replace(Regex("\\b(?:ancient\\s+history|antiquity)\\b"), "ancient")
        .replace(Regex("\\b(?:chronology|chronological)\\b"), "timeline")
    val stopWords = setOf("a", "an", "the", "of", "and", "in", "on", "for", "from", "to", "under", "history", "historical", "era", "period", "centuries", "century", "ad", "bc", "bce", "ce")
    val tokens = Regex("\\p{L}+").findAll(normalized).map { it.value }.filterNot { it in stopWords }.map {
        when (it) {
            "french" -> "france"
            "napoleonic" -> "napoleon"
            "political" -> "politics"
            "cultural" -> "culture"
            "economic", "economics" -> "economy"
            "religious" -> "religion"
            "scientific" -> "science"
            "technological" -> "technology"
            "origins" -> "origin"
            "effects", "consequences", "impacts" -> "impact"
            else -> it
        }
    }.toSet()
    return if (tokens.size > 1) tokens - "timeline" else tokens
}

private val temporalStart = Regex("^[*_]*(?:c\\.?\\s*)?(\\d{1,4})(?:\\s*(BC|BCE|AD|CE)\\b)?(?=\\D|$)", RegexOption.IGNORE_CASE)

private fun startYear(text: String, listEntry: Boolean = false): Int? {
    // Strip an ordinal once, before looking for a date. An optional ordinal in
    // the date regex could backtrack and mistake "1. Some event" for year 1.
    val candidate = if (listEntry) text.trimStart().replaceFirst(listStart, "") else text.trimStart()
    val match = temporalStart.find(candidate) ?: return null
    val year = match.groupValues[1].toIntOrNull() ?: return null
    val following = candidate.drop(match.range.last + 1).trimStart('*', '_', ' ')
    if (match.groupValues[2].isBlank() && following.isNotEmpty() && !following.startsWith(':') && !following.startsWith('–') && !following.startsWith('—') && !following.startsWith('-') && !following.startsWith('.')) return null
    return if (match.groupValues[2].uppercase(Locale.ROOT) in setOf("BC", "BCE")) -year else year
}

private fun sectionSimilarity(left: ResponseSection, right: ResponseSection): Double {
    val a = left.tokens
    val b = right.tokens
    if (left.dates.isNotEmpty() && right.dates.isNotEmpty() && (left.dates.max() < right.dates.min() || right.dates.max() < left.dates.min())) return 0.0
    if (a == b && a.isNotEmpty()) return 1.0
    val leftYear = left.year
    val rightYear = right.year
    if (leftYear != null && leftYear == rightYear) return 0.95
    val common = a.intersect(b)
    if (common.isEmpty()) return 0.0
    val knownTopic = common.any { it in setOf("medieval", "ancient", "napoleon", "timeline", "politics", "culture", "economy", "religion", "science", "technology") }
    val subset = common.size == minOf(a.size, b.size)
    if (subset && (common.size >= 2 || knownTopic || left.level == 1 && right.level == 1)) return 0.9
    val similarity = common.size.toDouble() / a.union(b).size
    return if (common.size >= 2 && similarity >= 0.6) similarity else 0.0
}

private fun mergeSections(destination: ResponseSection, source: ResponseSection) {
    destination.blocks += source.blocks
    source.children.forEach { child ->
        val match = destination.children.maxByOrNull { sectionSimilarity(it, child) }
            ?.takeIf { sectionSimilarity(it, child) > 0.0 }
        if (match == null) {
            destination.children += child
        } else {
            // Keep differing date ranges under the aligned section, rather than
            // discarding a source's unique scope when its heading is replaced.
            val newDates = Regex("\\d+").findAll(child.title).map { it.value }.toSet() - Regex("\\d+").findAll(match.title).map { it.value }.toSet()
            if (newDates.isNotEmpty()) match.blocks += "**${child.title}**"
            mergeSections(match, child)
        }
    }
}

private fun renderSection(section: ResponseSection): String {
    val body = mergeBlocks(section.blocks)
    val children = if (section.children.size > 1 && section.children.all { it.year != null }) {
        section.children.sortedBy { it.year }
    } else {
        section.children
    }
    val ownContent = listOfNotNull(section.heading, body.takeIf { it.isNotBlank() }).joinToString("\n")
    return (listOf(ownContent) + children.map(::renderSection)).filter { it.isNotBlank() }.joinToString("\n\n")
}

private fun mergeBlocks(blocks: List<String>): String {
    val seen = mutableSetOf<String>()
    val seenSentences = mutableSetOf<String>()
    val merged = mutableListOf<String>()
    blocks.forEach { block ->
        val structural = block.lineSequence().any { line ->
            line.trimStart().let { it.startsWith("```") || it.startsWith("~~~") || it.startsWith("|") || listStart.containsMatchIn(it) }
        }
        val identity = if (structural) block else block.replace(Regex("\\s+"), " ").trim()
        if (seen.add(identity)) {
            if (structural) {
                merged += block
            } else {
                val unique = block.split(Regex("(?<=[.!?])\\s+(?=[A-Z0-9])"))
                    .filter { seenSentences.add(it.replace(Regex("\\s+"), " ").trim()) }
                if (unique.isNotEmpty()) merged += unique.joinToString(" ")
            }
        }
    }
    // Interleave dated list blocks across models. Code, tables and undated
    // lists retain their structure; continuations and conflicting claims remain.
    val result = mutableListOf<String>()
    var timeline = mutableListOf<String>()
    fun flushTimeline() {
        if (timeline.isEmpty()) return
        val numbered = timeline.all { Regex("^\\d+[.)] ").containsMatchIn(it.trimStart()) }
        result += timeline.distinct().sortedBy { startYear(it, listEntry = true) }.mapIndexed { index, entry ->
            if (numbered) entry.replaceFirst(Regex("^\\d+[.)] "), "${index + 1}. ") else entry
        }.joinToString("\n")
        timeline = mutableListOf()
    }
    merged.forEach { block ->
        val entries = splitList(block)
        if (entries.isNotEmpty() && entries.all { startYear(it, listEntry = true) != null }) {
            timeline += entries
        } else {
            flushTimeline()
            result += block
        }
    }
    flushTimeline()
    return result.joinToString("\n\n")
}

private fun splitList(block: String): List<String> {
    val entries = mutableListOf<String>()
    block.lineSequence().forEach { line ->
        if (listStart.containsMatchIn(line)) {
            entries += line
        } else if (entries.isNotEmpty() && (line.isBlank() || line.firstOrNull()?.isWhitespace() == true)) {
            entries[entries.lastIndex] += "\n$line"
        } else {
            return emptyList()
        }
    }
    return entries
}
