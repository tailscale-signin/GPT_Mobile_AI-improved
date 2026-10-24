package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.conversation.ConversationSubject
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse

/** Source-aligned wording hints, never factual verification or exclusive authorship. */
data class CombinedWordingSpan(val start: Int, val end: Int, val profileUids: Set<String>)

private data class WordingSource(val uid: String, val fingerprint: CombinedClaimMatcher.Fingerprint)
private data class WordingCorpus(val claims: List<WordingSource>, val byTerm: Map<String, List<Int>>) {
    val lines = object : LinkedHashMap<String, List<CombinedWordingSpan>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<CombinedWordingSpan>>): Boolean = size > 128
    }
}

/** Immutable corpus keys reject stale source edits; bounded per-line caching reuses committed passages. */
private object WordingCache {
    private val corpora = object : LinkedHashMap<List<Pair<String, String>>, WordingCorpus>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<Pair<String, String>>, WordingCorpus>): Boolean = size > 4
    }
    fun corpus(sources: List<CombinedModelResponse>): WordingCorpus {
        val key = sources.map { it.platformUid to it.content }
        synchronized(corpora) { corpora[key]?.let { return it } }
        val claims = sources.flatMap { source ->
            CombinedClaimClauses.spans(CombinedContentCoverage.sourceCoverageText(source.content))
                .map { WordingSource(source.platformUid, CombinedClaimMatcher.fingerprint(it.text)) }
                .filter { it.fingerprint.terms.size >= 3 }
        }
        val byTerm = HashMap<String, MutableList<Int>>()
        claims.forEachIndexed { index, source -> source.fingerprint.terms.forEach { byTerm.getOrPut(it) { mutableListOf() }.add(index) } }
        val result = WordingCorpus(claims, byTerm)
        if (sources.sumOf { it.content.length.toLong() } <= 262_144) synchronized(corpora) { corpora[key] = result }
        return result
    }
}

fun combinedWordingSpans(answer: String, sources: List<CombinedModelResponse>): List<CombinedWordingSpan> {
    val visible = ConversationSubject.withoutMetadata(answer)
    val offset = answer.length - visible.length
    if (offset < 0 || !answer.endsWith(visible)) return emptyList()
    val corpus = WordingCache.corpus(sources)
    if (corpus.claims.isEmpty()) return emptyList()
    var cursor = offset
    var fenced = false
    return buildList {
        visible.lineSequence().forEach { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) fenced = !fenced
            if (!fenced && line.isNotBlank() && !trimmed.startsWith("#") && !trimmed.startsWith("```") && !trimmed.startsWith("~~~")) {
                val cached = synchronized(corpus.lines) { corpus.lines[line] }
                val spans = cached ?: wordingLine(line, corpus).also { found ->
                    if (line.length <= 16_384) synchronized(corpus.lines) { corpus.lines[line] = found }
                }
                addAll(spans.map { it.copy(start = it.start + cursor, end = it.end + cursor) })
            }
            cursor += line.length + 1
        }
    }
}

private val wordingWords = Regex("[\\p{L}\\p{N}]+(?:[’'][\\p{L}\\p{N}]+)?")

private fun wordingLine(line: String, corpus: WordingCorpus): List<CombinedWordingSpan> = buildList {
    for (clause in CombinedClaimClauses.spans(line)) {
        if (clause.text.length < 24 || !CombinedSourceClaims.material(clause.text)) continue
        val claim = CombinedClaimMatcher.fingerprint(clause.text)
        val words = wordingWords.findAll(clause.text).toList()
        val wordPrints = words.map { CombinedClaimMatcher.fingerprint(it.value) }
        val candidates = claim.terms.flatMapTo(linkedSetOf()) { corpus.byTerm[it].orEmpty() }
        val fingerprints = HashMap<Pair<Int, Int>, CombinedClaimMatcher.Fingerprint>()
        val aligned = candidates.mapNotNull { id ->
            val source = corpus.claims[id]
            if (!CombinedClaimMatcher.compatible(source.fingerprint, claim) || !CombinedClaimMatcher.compatible(claim, source.fingerprint)) return@mapNotNull null
            if (source.fingerprint.terms.intersect(claim.terms).size < 3) return@mapNotNull null
            val expected = source.fingerprint.terms + source.fingerprint.numbers
            val relevant = words.indices.filter { index ->
                wordPrints[index].let { part -> part.terms.any { it in expected } || part.numbers.any { it in expected } }
            }.take(64)
            var best: CombinedWordingSpan? = null
            // Longest compatible window first; a shared noun phrase can be linked without
            // painting unsupported surrounding clauses. Every window retains date/polarity checks.
            search@ for (width in relevant.size downTo 3) {
                for (first in 0..relevant.size - width) {
                    val start = words[relevant[first]].range.first
                    val end = words[relevant[first + width - 1]].range.last + 1
                    if (end - start < 20) continue
                    if ((relevant[first]..relevant[first + width - 1]).none { words[it].value.first().isLowerCase() && wordPrints[it].terms.isNotEmpty() }) continue
                    val clipped = fingerprints.getOrPut(start to end) { CombinedClaimMatcher.fingerprint(clause.text.substring(start, end)) }
                    if (!CombinedClaimMatcher.compatible(source.fingerprint, clipped) || !CombinedClaimMatcher.compatible(clipped, source.fingerprint)) continue
                    val reverse = CombinedClaimMatcher.match(clipped, source.fingerprint)
                    if (reverse >= CombinedClaimMatcher.Match.PARAPHRASE &&
                        (CombinedClaimMatcher.match(source.fingerprint, clipped) >= CombinedClaimMatcher.Match.POSSIBLE || reverse == CombinedClaimMatcher.Match.EXACT && clipped.terms.size >= 3)
                    ) {
                        best = CombinedWordingSpan(clause.start + start, clause.start + end, setOf(source.uid))
                        break@search
                    }
                }
            }
            best
        }
        // Split overlapping support at exact boundaries instead of assigning an entire
        // sentence to a single owner or treating shared wording as exclusive authorship.
        val boundaries = aligned.flatMap { listOf(it.start, it.end) }.distinct().sorted()
        boundaries.zipWithNext().forEach { (start, end) ->
            val owners = aligned.filter { it.start <= start && it.end >= end }.flatMapTo(linkedSetOf()) { it.profileUids }
            // A generic word left between overlap boundaries is no independent claim.
            if (owners.isNotEmpty() && CombinedSourceClaims.material(line.substring(start, end))) {
                val previous = lastOrNull()
                if (previous?.end == start && previous.profileUids == owners) {
                    this[lastIndex] = previous.copy(end = end)
                } else {
                    add(CombinedWordingSpan(start, end, owners))
                }
            }
        }
    }
}
