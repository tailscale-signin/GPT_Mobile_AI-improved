package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.diagnostics.CombinedOperationOwner
import dev.chungjungsoo.gptmobile.data.diagnostics.CombinedOperationTrace
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.Serializable

/** A text-lineage result, never an assertion of factual accuracy or proof of omission. */
@Serializable
data class CombinedMappedClaim(
    val claimId: String,
    val canonicalId: String,
    val aliases: Set<String>,
    val profileUids: Set<String>,
    val status: String,
    val links: List<CombinedCoverageLink> = emptyList()
) {
    val fullyLinked: Boolean get() = status in setOf("EXACT", "PARAPHRASE")
}

@Serializable
data class CombinedAnswerMapping(
    val version: Int = 1,
    val answerDigest: String,
    val corpusDigest: String,
    val evaluated: Boolean,
    val claims: List<CombinedMappedClaim>,
    val links: List<CombinedPassageLink>
) {
    fun matches(answer: String): Boolean = researchHash(answer) == answerDigest &&
        links.all { it.matches(answer) } &&
        claims.all { claim ->
            claim.links.all { link ->
                link.startUtf16 >= 0 &&
                    link.endUtf16 > link.startUtf16 &&
                    link.endUtf16 <= answer.length &&
                    researchHash(answer.substring(link.startUtf16, link.endUtf16)) == link.textDigest
            }
        }
}

/** One versioned map is consumed by delivery checks, colours, inspector and export. */
internal object CombinedAnswerMappings {
    private const val MAX_COMPARISONS = 1_000_000L
    private data class Key(val answer: String, val corpus: String, val selection: String)
    private val cache = LinkedHashMap<Key, CombinedAnswerMapping>(4, 0.75f, true)
    private data class Destination(val text: String, val span: CombinedCoverageLink, val partialMention: Boolean = false)

    fun get(answer: String, sources: List<CombinedModelResponse>, claimIds: Set<String>? = null): CombinedAnswerMapping {
        val index = CombinedContentIndexes.get(sources)
        val corpus = researchHash(sources.joinToString("\n") { "${it.platformUid.length}:${it.platformUid}:${researchHash(it.content)}:${it.claimExtractionVersion}:${it.contentCompleteness}" })
        val selection = claimIds?.sorted()?.joinToString("|")?.let(::researchHash).orEmpty()
        val key = Key(researchHash(answer), corpus, selection)
        synchronized(cache) { cache[key]?.let { return it } }
        return CombinedOperationTrace.measure("source_mapping", CombinedOperationOwner(digest = key.answer)) {
            build(answer, sources, index, corpus, key, claimIds)
        }
    }

    private fun build(
        answer: String,
        sources: List<CombinedModelResponse>,
        index: CombinedContentIndex,
        corpus: String,
        key: Key,
        claimIds: Set<String>?
    ): CombinedAnswerMapping {
        val destinations = destinations(answer)
        val prints = destinations.map { CombinedClaimMatcher.fingerprint(it.text) }
        val byTerm = mutableMapOf<String, MutableSet<Int>>()
        prints.forEachIndexed { i, value -> value.terms.forEach { byTerm.getOrPut(it) { linkedSetOf() } += i } }
        var comparisons = 0L
        var evaluated = true
        val selectedClaims = index.records.filter { claim ->
            claimIds == null ||
                claim.id in claimIds ||
                claim.canonicalId in claimIds ||
                claim.candidateIds.any { it in claimIds }
        }
        val claims = selectedClaims.map { claim ->
            val source = index.fingerprints.getValue(claim.proposition)
            // Qualifications that span several sentences cannot be detached by a split.
            val qualified = source.negative || source.hedged || source.restrictions.isNotEmpty() || claim.qualifiers.uncertainty.isNotEmpty() || claim.qualifiers.outcome in setOf("NULL_EFFECT", "INSUFFICIENT_EVIDENCE")
            val clauses = if (qualified || claim.occurrences.any { it.tableCells.isNotEmpty() }) listOf(claim.proposition) else CombinedClaimClauses.spans(claim.proposition).map { it.comparisonText }.ifEmpty { listOf(claim.proposition) }
            val clausePrints = clauses.associateWith { index.fingerprints[it] ?: CombinedClaimMatcher.fingerprint(it) }
            val parts = clauses.map { text ->
                val fingerprint = clausePrints.getValue(text)
                val candidates = fingerprint.terms.flatMapTo(linkedSetOf()) { byTerm[it].orEmpty() }
                val matches = mutableListOf<Pair<CombinedCoverageLink, CombinedClaimMatcher.Match>>()
                candidates.forEach { candidate ->
                    if (destinations[candidate].partialMention) return@forEach
                    if (++comparisons > MAX_COMPARISONS) {
                        evaluated = false
                    } else {
                        val target = destinations[candidate]
                        val match = CombinedClaimMatcher.match(fingerprint, prints[candidate])
                        val identical = CombinedContentCoverage.normalized(text) == CombinedContentCoverage.normalized(target.text) && CombinedClaimMatcher.compatible(fingerprint, prints[candidate])
                        if (match >= CombinedClaimMatcher.Match.PARAPHRASE || identical) {
                            val ranges = if (CombinedClaimMatcher.match(prints[candidate], fingerprint) >= CombinedClaimMatcher.Match.PARAPHRASE || identical) listOf(target.span) else fingerprintWindows(answer, fingerprint, listOf(target), listOf(prints[candidate]), prints[candidate].terms.associateWith { setOf(0) })
                            ranges.forEach { range -> matches += range to if (identical) CombinedClaimMatcher.Match.EXACT else match }
                        }
                    }
                }
                matches.distinctBy { it.first }
            }
            val fullyLinked = parts.all { it.isNotEmpty() }
            val linked = parts.flatten().map { it.first }.distinct()
            // A source can contribute a bounded phrase without the entire proposition being
            // represented. Keep that status separate from full claim inclusion.
            // Full inclusion elsewhere must not erase an independently valid
            // partial contribution in an earlier paragraph. This search retains
            // the same quantity, polarity and qualification safeguards.
            val fragments = if (evaluated && !qualified) {
                clauses.flatMap { fingerprintWindows(answer, clausePrints.getValue(it), destinations, prints, byTerm, linked) }
                    .filterNot { fragment -> linked.any { it.startUtf16 <= fragment.startUtf16 && it.endUtf16 >= fragment.endUtf16 } }
            } else {
                emptyList()
            }
            CombinedMappedClaim(
                claim.id,
                claim.canonicalId,
                claim.candidateIds,
                claim.profileUids,
                when {
                    !evaluated -> "NOT_EVALUATED"
                    fullyLinked && parts.all { matches -> matches.any { it.second == CombinedClaimMatcher.Match.EXACT } } -> "EXACT"
                    fullyLinked -> "PARAPHRASE"
                    linked.isNotEmpty() || fragments.isNotEmpty() -> "PARTIAL"
                    else -> "UNMATCHED"
                },
                if (evaluated) (linked + fragments).distinct() else emptyList()
            )
        }
        val cells = CombinedSourceClaims.units(answer).filter { it.cells.isNotEmpty() }.associate { unit ->
            (unit.start to unit.end) to unit.cells.map { CombinedCoverageLink(it.startUtf16, it.endUtf16, researchHash(answer.substring(it.startUtf16, it.endUtf16))) }
        }
        val cellRanges = cells.values.flatten().toSet()
        val substantiveClaims = index.records.filter { claim ->
            claim.proposition.length >= 24 || index.fingerprints.getValue(claim.proposition).numbers.isNotEmpty()
        }.mapTo(hashSetOf()) { it.id }
        val ranges = claims.flatMap { claim ->
            claim.links.flatMap { link ->
                cells[link.startUtf16 to link.endUtf16]?.map { it to claim } ?: run {
                    var start = link.startUtf16
                    var end = link.endUtf16
                    while (start < end && !answer[start].isLetterOrDigit()) start++
                    while (end > start && !answer[end - 1].isLetterOrDigit()) end--
                    // These are already proposition-checked links. A compressed object
                    // such as "the EEC in 1986" keeps its inherited subject for matching
                    // and must retain its colour even though the visible range is short.
                    // Do not join unrelated tiny paragraph fragments into a wording match.
                    // A short range may still inherit a substantive full source proposition.
                    if (end <= start || end - start < 24 && claim.claimId !in substantiveClaims) emptyList() else listOf(CombinedCoverageLink(start, end, researchHash(answer.substring(start, end))) to claim)
                }
            }
        }

        val boundaries = ranges.flatMap { listOf(it.first.startUtf16, it.first.endUtf16) }.distinct().sorted()
        val links = boundaries.zipWithNext().mapNotNull { (start, end) ->
            val owners = ranges.filter { it.first.startUtf16 <= start && it.first.endUtf16 >= end }.map { it.second }
            if (owners.isEmpty() || !CombinedSourceClaims.material(answer.substring(start, end)) && cellRanges.none { it.startUtf16 == start && it.endUtf16 == end }) {
                null
            } else {
                CombinedPassageLink(
                    start,
                    end,
                    researchHash(answer.substring(start, end)),
                    owners.flatMapTo(linkedSetOf()) { it.profileUids },
                    owners.flatMapTo(linkedSetOf()) { it.aliases + it.canonicalId }
                )
            }
        }
        val result = CombinedAnswerMapping(version = 2, answerDigest = key.answer, corpusDigest = corpus, evaluated = evaluated, claims = claims, links = links)
        if (answer.length.toLong() + sources.sumOf { it.content.length.toLong() } <= 262_144L) {
            synchronized(cache) {
                cache[key] = result
                while (cache.size > 4) cache.remove(cache.keys.first())
            }
        }
        return result
    }

    private fun destinations(answer: String): List<Destination> = CombinedSourceClaims.units(answer).flatMap { unit ->
        val text = answer.substring(unit.start, unit.end)
        val whole = Destination(unit.text, CombinedCoverageLink(unit.start, unit.end, researchHash(text)))
        if (unit.cells.isNotEmpty()) {
            // Matching uses the complete row with column headings; rendering uses exact cells.
            listOf(whole)
        } else {
            val clauses = CombinedClaimClauses.spans(text)
            val mentions = CombinedClaimClauses.datedMentions(text).map { mention ->
                Destination(mention.text, CombinedCoverageLink(unit.start + mention.start, unit.start + mention.end, researchHash(mention.text)), partialMention = true)
            }
            // The complete unit is essential when an adjacent sentence qualifies the
            // first ("... improves symptoms. However, evidence remains limited.").
            // Clause candidates supplement it; they must not erase the scoped unit.
            (if (clauses.size <= 1) listOf(whole) else listOf(whole) + clauses.map { clause -> Destination(clause.comparisonText, CombinedCoverageLink(unit.start + clause.start, unit.start + clause.end, researchHash(clause.text))) }) + mentions
        }
    }

    private val words = Regex("[\\p{L}\\p{N}]+(?:[’'][\\p{L}\\p{N}]+)?")
    private val datedMentionYear = Regex("[12][0-9]{3}")
    private fun fingerprintWindows(answer: String, source: CombinedClaimMatcher.Fingerprint, destinations: List<Destination>, prints: List<CombinedClaimMatcher.Fingerprint>, byTerm: Map<String, Set<Int>>, covered: List<CombinedCoverageLink> = emptyList()): List<CombinedCoverageLink> {
        val candidates = source.terms.flatMapTo(linkedSetOf()) { byTerm[it].orEmpty() }
        return candidates.mapNotNull { id ->
            val destination = destinations[id]
            if (covered.any { it.startUtf16 <= destination.span.startUtf16 && it.endUtf16 >= destination.span.endUtf16 }) return@mapNotNull null
            val target = prints[id]
            if (!CombinedClaimMatcher.compatible(source, target) || !CombinedClaimMatcher.compatible(target, source)) return@mapNotNull null
            val raw = answer.substring(destination.span.startUtf16, destination.span.endUtf16)
            if (raw != destination.text) return@mapNotNull null
            val tokens = words.findAll(raw).toList()
            val expected = source.terms + source.numbers
            val relevant = tokens.indices.filter { i -> CombinedClaimMatcher.fingerprint(tokens[i].value).let { (it.terms + it.numbers).any { token -> token in expected } } }.take(64)
            for (width in relevant.size downTo 3) {
                for (first in 0..relevant.size - width) {
                    val start = tokens[relevant[first]].range.first
                    val end = tokens[relevant[first + width - 1]].range.last + 1
                    if (end - start < 20) continue
                    val clipped = CombinedClaimMatcher.fingerprint(raw.substring(start, end))
                    if (!CombinedClaimMatcher.compatible(source, clipped) || !CombinedClaimMatcher.compatible(clipped, source)) continue
                    val reverse = CombinedClaimMatcher.match(clipped, source)
                    val exactDatedMention = destination.partialMention && reverse == CombinedClaimMatcher.Match.EXACT && clipped.terms.size >= 2 && clipped.numbers.singleOrNull()?.matches(datedMentionYear) == true
                    if (!exactDatedMention && (relevant[first]..relevant[first + width - 1]).none { tokens[it].value.first().isLowerCase() && CombinedClaimMatcher.fingerprint(tokens[it].value).terms.isNotEmpty() }) continue
                    if (reverse >= CombinedClaimMatcher.Match.PARAPHRASE && (CombinedClaimMatcher.match(source, clipped) >= CombinedClaimMatcher.Match.POSSIBLE || reverse == CombinedClaimMatcher.Match.EXACT && clipped.terms.size >= 3 || exactDatedMention)) {
                        return@mapNotNull CombinedCoverageLink(destination.span.startUtf16 + start, destination.span.startUtf16 + end, researchHash(raw.substring(start, end)))
                    }
                }
            }
            null
        }.distinct()
    }

    fun passages(answer: String, mapping: CombinedAnswerMapping, declared: List<CombinedPassageRecord> = emptyList()): List<CombinedPassageRecord> {
        val originals = if (declared.isEmpty()) {
            listOf(CombinedPassageRecord(0, answer.length, researchHash(answer), emptySet(), passageId = "mapped:${mapping.answerDigest}"))
        } else {
            declared.filter { it.matches(answer) }
        }
        return originals.flatMap { passage ->
            val ranges = passageRanges(answer, passage.start, passage.end)
            ranges.mapIndexed { index, range ->
                val links = mapping.links.mapNotNull { link ->
                    val start = maxOf(link.start, range.first)
                    val end = minOf(link.end, range.last + 1)
                    if (end <= start) null else link.copy(start = start, end = end, digest = researchHash(answer.substring(start, end)))
                }
                passage.copy(
                    start = range.first,
                    end = range.last + 1,
                    digest = researchHash(answer.substring(range.first, range.last + 1)),
                    profileUids = links.flatMapTo(linkedSetOf()) { it.profileUids },
                    claimIds = links.flatMapTo(linkedSetOf()) { it.claimIds },
                    links = links,
                    linkVersion = 1,
                    passageId = if (ranges.size == 1) passage.passageId else "${passage.passageId}:${index + 1}",
                    origin = "LOCAL_CLAIM_MAPPING_V${mapping.version}",
                    semanticLinkStatus = if (links.isNotEmpty()) "CONSERVATIVE_LINK_CHECKED" else "UNVERIFIED"
                )
            }
        }
    }

    /** A provider may place a whole section in one record; colours remain local to each item. */
    private fun passageRanges(answer: String, start: Int, end: Int): List<IntRange> = CombinedSourcePreservation.blocks(answer.substring(start, end)).flatMap { block ->
        val offset = start + block.start
        if (block.text.contains("```") || block.text.contains("~~~") || block.text.contains('|') && CombinedSourceClaims.tableRows(block.text) > 0) {
            listOf(offset until start + block.end)
        } else {
            val ranges = mutableListOf<IntRange>()
            var position = offset
            var from = offset
            block.text.lineSequence().forEach { line ->
                val heading = Regex("^\\s*#{1,6}\\s").containsMatchIn(line)
                val item = Regex("^\\s*(?:[-*+] |[0-9]+[.)] )").containsMatchIn(line)
                if ((heading || item) && position > from) {
                    ranges += from until position - 1
                    from = position
                }
                position += line.length + 1
                if (heading) {
                    ranges += from until position - 1
                    from = position
                }
            }
            if (from < start + block.end) ranges += from until start + block.end
            ranges.filter { !it.isEmpty() }
        }
    }

    internal fun clear() = synchronized(cache) { cache.clear() }
}
