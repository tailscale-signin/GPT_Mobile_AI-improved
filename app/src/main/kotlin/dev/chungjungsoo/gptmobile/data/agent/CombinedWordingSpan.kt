package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.conversation.ConversationSubject
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import java.text.BreakIterator
import java.util.Locale

/** Source-aligned wording/claim hints, never qualified semantic verification. */
data class CombinedWordingSpan(val start: Int, val end: Int, val profileUids: Set<String>)

/**
 * Colour meaningful answer claims, not arbitrary shared five-word openers.
 * Compare every competing contributor before assigning exclusive ownership; multi-source
 * contributions remain neutral in the prose renderer and are inspectable in Debug Mode.
 * UTF-16 ranges always refer to the original answer, not transformed Markdown.
 */
fun combinedWordingSpans(answer: String, sources: List<CombinedModelResponse>): List<CombinedWordingSpan> {
    val visible = ConversationSubject.withoutMetadata(answer)
    val offset = answer.length - visible.length
    if (offset < 0 || !answer.endsWith(visible)) return emptyList()

    data class Indexed(val uid: String, val fingerprint: CombinedClaimMatcher.Fingerprint)
    val indexed = sources.flatMap { source ->
        CombinedContentCoverage.sentences(CombinedContentCoverage.sourceCoverageText(source.content))
            .map { Indexed(source.platformUid, CombinedClaimMatcher.fingerprint(it)) }
            .filter { it.fingerprint.terms.size >= 3 }
    }
    if (indexed.isEmpty()) return emptyList()

    // Index by distinctive terms to avoid quadratic comparisons for long combined essays.
    val byTerm = HashMap<String, MutableList<Int>>()
    indexed.forEachIndexed { i, source ->
        source.fingerprint.terms.forEach { term ->
            byTerm.getOrPut(term) { mutableListOf() }.add(i)
        }
    }
    val result = mutableListOf<CombinedWordingSpan>()
    var cursor = 0
    visible.lineSequence().forEach { line ->
        val trimmed = line.trimStart()
        val eligible = line.isNotBlank() && !trimmed.startsWith("#") && !trimmed.startsWith(96.toChar().toString().repeat(3))
        if (eligible) {
            val iterator = BreakIterator.getSentenceInstance(Locale.ROOT).apply { setText(line) }
            var localStart = iterator.first()
            var localEnd = iterator.next()
            while (localEnd != BreakIterator.DONE) {
                var start = cursor + localStart
                var end = cursor + localEnd
                while (start < end && !visible[start].isLetterOrDigit()) start++
                while (end > start && !visible[end - 1].isLetterOrDigit()) end--
                if (end > start && end - start >= 24) {
                    val claim = CombinedClaimMatcher.fingerprint(visible.substring(start, end))
                    val candidates = claim.terms.asSequence().flatMap { byTerm[it].orEmpty().asSequence() }.distinct()
                    val matchingIds = candidates.filter { id ->
                        val match = CombinedClaimMatcher.match(indexed[id].fingerprint, claim)
                        match == CombinedClaimMatcher.Match.EXACT || match == CombinedClaimMatcher.Match.PARAPHRASE
                    }.toList()
                    if (matchingIds.isNotEmpty()) {
                        val expected = matchingIds.flatMapTo(hashSetOf()) { id ->
                            indexed[id].fingerprint.terms + indexed[id].fingerprint.numbers
                        }
                        // Leave unrelated math, labels and trailing code neutral. The source
                        // lineage should colour only the meaningful claim inside the paragraph.
                        val words = Regex("[\\p{L}\\p{N}]+(?:[’'][\\p{L}\\p{N}]+)?")
                        val relevant = words.findAll(visible.substring(start, end)).filter { word ->
                            val fingerprint = CombinedClaimMatcher.fingerprint(word.value)
                            fingerprint.terms.any { it in expected } || fingerprint.numbers.any { it in expected }
                        }.toList()
                        if (relevant.isNotEmpty()) {
                            val colouredStart = start + relevant.first().range.first
                            val colouredEnd = start + relevant.last().range.last + 1
                            val clipped = CombinedClaimMatcher.fingerprint(visible.substring(colouredStart, colouredEnd))
                            // Require support in both directions for the final coloured range;
                            // matching an opener cannot assign its source to unrelated facts.
                            val matching = matchingIds.filter { id ->
                                val source = indexed[id].fingerprint
                                CombinedClaimMatcher.match(source, clipped) >= CombinedClaimMatcher.Match.PARAPHRASE &&
                                    CombinedClaimMatcher.match(clipped, source) >= CombinedClaimMatcher.Match.PARAPHRASE
                            }.mapTo(linkedSetOf()) { indexed[it].uid }
                            if (matching.isNotEmpty()) {
                                result += CombinedWordingSpan(colouredStart + offset, colouredEnd + offset, matching)
                            }
                        }
                    }
                }
                localStart = localEnd
                localEnd = iterator.next()
            }
        }
        cursor += line.length + 1
    }
    return result
}
