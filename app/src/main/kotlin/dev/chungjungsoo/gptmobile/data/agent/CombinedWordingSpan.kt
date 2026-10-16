package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.conversation.ConversationSubject
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import java.util.Locale

/** Textual lineage only: matching wording is not a verification of the underlying claim. */
data class CombinedWordingSpan(val start: Int, val end: Int, val profileUids: Set<String>)

/**
 * Index contiguous five-word phrases, retaining every submitter. Offsets always refer to
 * the original UTF-16 answer, including emoji and Markdown; normalization never shifts them.
 * Overlapping matches are partitioned so shared wording cannot acquire an exclusive colour.
 */
fun combinedWordingSpans(answer: String, sources: List<CombinedModelResponse>): List<CombinedWordingSpan> {
    val word = Regex("[\\p{L}\\p{N}]+(?:[’'][\\p{L}\\p{N}]+)?")
    val visible = ConversationSubject.withoutMetadata(answer)
    val offset = answer.length - visible.length
    if (offset < 0 || !answer.endsWith(visible)) return emptyList()
    val words = word.findAll(visible).toList()
    if (words.size < 5) return emptyList()
    // Bound indexing work for very large retained corpora; no truncated attribution is published.
    if (answer.length.toLong() + sources.sumOf { it.content.length.toLong() } > 262_144L) return emptyList()
    fun key(window: List<MatchResult>) = window.joinToString(" ") { it.value.lowercase(Locale.ROOT) }
    val index = HashMap<String, MutableSet<String>>()
    sources.forEach { source ->
        val text = ConversationSubject.withoutMetadata(source.content)
        word.findAll(text).toList().windowed(5).forEach { window ->
            // Never join unrelated paragraphs into a phrase.
            if ('\n' !in text.substring(window.first().range.first, window.last().range.last + 1)) {
                index.getOrPut(key(window)) { linkedSetOf() }.add(source.platformUid)
            }
        }
    }
    val events = sortedMapOf<Int, MutableList<Pair<String, Int>>>()
    words.windowed(5).forEach { window ->
        val start = window.first().range.first
        val end = window.last().range.last + 1
        if (end - start >= 24 && '\n' !in visible.substring(start, end)) {
            index[key(window)]?.forEach { uid ->
                events.getOrPut(start + offset) { mutableListOf() }.add(uid to 1)
                events.getOrPut(end + offset) { mutableListOf() }.add(uid to -1)
            }
        }
    }
    val active = mutableMapOf<String, Int>()
    val result = mutableListOf<CombinedWordingSpan>()
    var previous = 0
    events.forEach { (position, changes) ->
        val profiles = active.filterValues { it > 0 }.keys.toSet()
        if (position > previous && profiles.isNotEmpty()) {
            val last = result.lastOrNull()
            if (last?.end == previous && last.profileUids == profiles) {
                result[result.lastIndex] = last.copy(end = position)
            } else {
                result += CombinedWordingSpan(previous, position, profiles)
            }
        }
        changes.forEach { (uid, delta) -> active[uid] = (active[uid] ?: 0) + delta }
        previous = position
    }
    return result
}
