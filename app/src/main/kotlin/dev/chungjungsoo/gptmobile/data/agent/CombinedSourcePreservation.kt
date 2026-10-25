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
    val disposition: String,
    val claimIds: Set<String> = emptySet(),
    val finalLinks: List<CombinedCoverageLink> = emptyList()
)

/** Source accounting is text lineage, never a factual verification or omission verdict. */
internal object CombinedSourcePreservation {
    data class Block(val start: Int, val end: Int, val text: String)
    data class Result(val text: String, val passages: List<CombinedPassageRecord>, val dispositions: List<CombinedSourceDisposition>, val fragments: Int)

    /**
     * Keep the integrated writer answer intact. Uncertain text matching cannot prove
     * omission and must never trigger copying whole contributors into the answer.
     * The original bodies remain immutable in the contribution snapshot; the report
     * accounts for their known links and explicitly retains unresolved link states.
     */
    fun account(answer: String, sources: List<CombinedModelResponse>): Result {
        val inventory = CombinedContentIndexes.get(sources).records
        val mapping = CombinedAnswerMappings.get(answer, sources)
        val mapped = mapping.claims.associateBy { it.canonicalId }
        val dispositions = inventory.flatMap { claim ->
            val linked = mapped[claim.canonicalId]
            val links = linked?.links.orEmpty()
            claim.occurrences.mapNotNull { occurrence ->
                val span = occurrence.sourceSpan ?: return@mapNotNull null
                CombinedSourceDisposition(
                    occurrence.profileUid,
                    occurrence.bodyDigest,
                    span.startUtf16,
                    span.endUtf16,
                    links.minOfOrNull { it.startUtf16 } ?: 0,
                    links.maxOfOrNull { it.endUtf16 } ?: 0,
                    when (linked?.status) {
                        "EXACT" -> "INCLUDED"
                        "PARAPHRASE" -> "PARAPHRASED"
                        "PARTIAL" -> "PARTIALLY_LINKED"
                        "NOT_EVALUATED" -> "LINK_NOT_EVALUATED"
                        else -> "UNRESOLVED_LINK"
                    },
                    setOf(claim.canonicalId) + claim.candidateIds,
                    links
                )
            }
        }.toMutableList()
        sources.forEach { source ->
            val digest = researchHash(source.content)
            val omissions = if (source.claimExtractionVersion >= 3) CombinedEditorialView.prepare(source.content).omissions else emptyList()
            blocks(source.content).filter { block ->
                dispositions.none { it.profileUid == source.platformUid && it.sourceDigest == digest && it.sourceStart < block.end && it.sourceEnd > block.start }
            }.forEach { block ->
                val excluded = omissions.any { it.reason == "STANDALONE_SELF_EDITING" && it.start <= block.start && it.end >= block.end }
                dispositions += CombinedSourceDisposition(source.platformUid, digest, block.start, block.end, 0, 0, if (excluded) "OMITTED_SELF_EDITING" else "CONTEXT_RETAINED_IN_SOURCE")
            }
        }
        return Result(answer, CombinedAnswerMappings.passages(answer, mapping), dispositions, 0)
    }

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

    /** Legacy explicit excerpt reconstruction. Live synthesis uses [account], never this appendix. */
    fun assemble(draft: String, sources: List<CombinedModelResponse>, request: String = ""): Result {
        data class Occurrence(val source: CombinedModelResponse, val block: Block, val digest: String, val topic: String?)
        data class EditorialOmission(val source: CombinedModelResponse, val digest: String, val omission: CombinedEditorialView.Omission)
        val grouped = linkedMapOf<String, MutableList<Occurrence>>()
        val omissions = mutableListOf<EditorialOmission>()
        val history = request.isNotBlank() && CombinedTaskContract.from(request).historicalSurvey
        fun topic(text: String, heading: String?): String? = heading ?: if (history) CombinedHistoryOutline.periods(text).singleOrNull()?.let { CombinedHistoryOutline.labels[it] } else null
        sources.forEach { source ->
            val digest = researchHash(source.content)
            val view = if (source.claimExtractionVersion >= 3) CombinedEditorialView.prepare(source.content) else null
            view?.omissions?.forEach { omissions += EditorialOmission(source, digest, it) }
            blocks(source.content).forEach blockLoop@{ block ->
                if (view?.omissions?.any { it.start == block.start && it.end == block.end } == true) return@blockLoop
                val heading = view?.spans?.firstOrNull { it.sourceStart == block.start }?.topic
                grouped.getOrPut("$digest:${block.start}:${block.end}") { mutableListOf() } += Occurrence(source, block, digest, if (view != null) topic(block.text, heading) else null)
            }
        }
        val answer = StringBuilder(draft)
        val original = answer.toString()
        val inventory = CombinedContentIndexes.get(sources).records
        val mapping = CombinedAnswerMappings.get(original, sources)
        val mapped = mapping.claims.associateBy { it.canonicalId }
        val passages = mutableListOf<CombinedPassageRecord>()
        val dispositions = mutableListOf<CombinedSourceDisposition>()
        var fragments = 0
        fun representedBy(occurrence: Occurrence): List<CombinedClaimRecord> = inventory.filter { claim ->
            claim.occurrences.any { item ->
                item.profileUid == occurrence.source.platformUid &&
                    item.bodyDigest == occurrence.digest &&
                    item.sourceSpan?.let { it.startUtf16 >= occurrence.block.start && it.endUtf16 <= occurrence.block.end } == true
            }
        }
        fun prose(text: String): Boolean = !text.contains("```") &&
            !text.contains("~~~") &&
            !text.lineSequence().any { it.startsWith("    ") || it.trimStart().startsWith('|') || Regex("^\\s*(?:#{1,6} |[-*+] |[0-9]+[.)] )").containsMatchIn(it) }
        // Version two units keep adjacent qualifications together. Split only independent
        // prose units with complete source coverage, so retaining one missing sentence does
        // not repeat an already attributed sentence from the same paragraph.
        val groups = grouped.values.flatMap { copies ->
            val first = copies.first()
            val represented = representedBy(first)
            val ranges = represented.flatMap { claim ->
                claim.occurrences.filter { it.profileUid == first.source.platformUid && it.bodyDigest == first.digest }
                    .mapNotNull { it.sourceSpan }
                    .filter { it.startUtf16 >= first.block.start && it.endUtf16 <= first.block.end }
            }.distinctBy { it.startUtf16 to it.endUtf16 }.sortedBy { it.startUtf16 }
            val covered = BooleanArray(first.block.text.length)
            ranges.forEach { range -> for (offset in range.startUtf16 until range.endUtf16) covered[offset - first.block.start] = true }
            val canSplit = first.source.claimExtractionVersion >= 2 &&
                prose(first.block.text) &&
                ranges.size > 1 &&
                ranges.zipWithNext().all { (left, right) -> left.endUtf16 <= right.startUtf16 } &&
                first.block.text.indices.all { covered[it] || !first.block.text[it].isLetterOrDigit() } &&
                represented.any { mapped[it.canonicalId]?.fullyLinked == true } &&
                represented.any { mapped[it.canonicalId]?.fullyLinked != true } &&
                ranges.none { range -> Regex("^(?:it|they|this|that|these|those)\\b", RegexOption.IGNORE_CASE).containsMatchIn(first.source.content.substring(range.startUtf16, range.endUtf16).trim()) }
            if (canSplit) {
                ranges.map { range ->
                    copies.map { occurrence ->
                        val text = occurrence.source.content.substring(range.startUtf16, range.endUtf16)
                        occurrence.copy(block = Block(range.startUtf16, range.endUtf16, text), topic = if (occurrence.source.claimExtractionVersion >= 3) topic(text, occurrence.topic) else null)
                    }
                }
            } else {
                listOf(copies)
            }
        }
        var currentTopic: String? = null
        // Preserve the immutable draft prefix and its passage coordinates. Retained
        // excerpts are grouped by explicit source headings or a single historical
        // period; these are navigation aids, never semantic or factual verification.
        val topicGroups = groups.groupBy { it.first().topic }.entries
        val ordered = if (history) topicGroups.sortedBy { CombinedHistoryOutline.labels.indexOf(it.key).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE } else topicGroups.toList()
        ordered.flatMap { it.value }.forEach { copies ->
            val first = copies.first()
            val text = first.block.text
            // A matching sentence alone may refer to a different heading, person or
            // denominator. Merge only when the entire surrounding source also matches.
            val sourceOffset = original.indexOf(first.source.content)
            val existing = if (sourceOffset >= 0) Block(sourceOffset + first.block.start, sourceOffset + first.block.end, text) else null
            val represented = representedBy(first)
            // A match must account for the complete prose block, including qualifications.
            // Code, tables and text outside extracted claims retain exact-span preservation.
            val covered = BooleanArray(text.length)
            represented.flatMap { it.occurrences }.filter { it.profileUid == first.source.platformUid && it.bodyDigest == first.digest }.mapNotNull { it.sourceSpan }.forEach { span ->
                for (offset in maxOf(span.startUtf16, first.block.start) until minOf(span.endUtf16, first.block.end)) covered[offset - first.block.start] = true
            }
            val paraphrased = existing == null &&
                represented.isNotEmpty() &&
                !text.contains("```") &&
                !text.contains("~~~") &&
                !text.lineSequence().any { it.startsWith("    ") || it.trimStart().startsWith('|') } &&
                text.indices.all { covered[it] || !text[it].isLetterOrDigit() } &&
                represented.all { mapped[it.canonicalId]?.fullyLinked == true } &&
                // A generic pronoun without its surrounding heading is ambiguous.
                !Regex("^(?:it|they|this|that|these|those)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text.trim())
            if (paraphrased) {
                val links = represented.flatMap { mapped.getValue(it.canonicalId).links }.distinct()
                val ids = represented.mapTo(linkedSetOf()) { it.canonicalId }
                copies.forEachIndexed { index, occurrence ->
                    dispositions += CombinedSourceDisposition(occurrence.source.platformUid, occurrence.digest, occurrence.block.start, occurrence.block.end, links.minOf { it.startUtf16 }, links.maxOf { it.endUtf16 }, if (index == 0) "PARAPHRASED" else "MERGED_DUPLICATE", ids, links)
                }
                return@forEach
            }
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
                if (first.topic != currentTopic) {
                    val label = first.topic ?: "Other retained material"
                    answer.append("\n\n### ").append(label.replace(Regex("[\\r\\n*_`\\[\\]<>#]"), " ").trim())
                    currentTopic = first.topic
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
        omissions.forEach { item ->
            val omission = item.omission
            if (omission.reason == "STANDALONE_SELF_EDITING") {
                // An empty destination is explicit editorial accounting, not a
                // claim that this non-substantive control text appears in the answer.
                dispositions += CombinedSourceDisposition(item.source.platformUid, item.digest, omission.start, omission.end, 0, 0, "OMITTED_SELF_EDITING")
            } else {
                val destinations = dispositions.filter { it.profileUid == item.source.platformUid && it.sourceDigest == item.digest && it.sourceStart >= requireNotNull(omission.duplicateStart) && it.sourceEnd <= requireNotNull(omission.duplicateEnd) && it.finalEnd > it.finalStart }
                check(destinations.isNotEmpty()) { "An editorial duplicate must retain its original destination." }
                val links = destinations.flatMap { disposition -> disposition.finalLinks.ifEmpty { listOf(CombinedCoverageLink(disposition.finalStart, disposition.finalEnd, researchHash(answer.substring(disposition.finalStart, disposition.finalEnd)))) } }.distinct()
                dispositions += CombinedSourceDisposition(item.source.platformUid, item.digest, omission.start, omission.end, links.minOf { it.startUtf16 }, links.maxOf { it.endUtf16 }, "MERGED_DUPLICATE", destinations.flatMapTo(linkedSetOf()) { it.claimIds }, links)
            }
        }
        return Result(answer.toString(), passages, dispositions, fragments)
    }
}
