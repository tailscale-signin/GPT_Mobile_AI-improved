package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.ui.graphics.Color
import dev.chungjungsoo.gptmobile.data.agent.ValidatedCombinedProvenance
import dev.chungjungsoo.gptmobile.data.agent.combinedWordingSpans
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.research.researchHash

const val ATTRIBUTION_MARKDOWN_PARSER_VERSION = "chat-markdown-source-map-v1"

/** A verbatim UTF-16 run; generated placeholders and inserted separators have no source range. */
data class MarkdownSourceRun(
    val blockIndex: Int,
    val sourceStart: Int,
    val sourceEnd: Int,
    val transformedStart: Int
) {
    val length: Int get() = sourceEnd - sourceStart
}

data class MarkdownProvenancePresentation(
    val validated: ValidatedCombinedProvenance,
    val corpusDigest: String,
    val policyRevision: String,
    val profileColors: Map<String, Color>
)

/** Contribution display colours are distinct from verified factual or exclusive semantic provenance. */
data class CombinedWordingPresentation(
    val sources: List<CombinedModelResponse>,
    val profileColors: Map<String, Color>,
    val passages: List<dev.chungjungsoo.gptmobile.data.agent.CombinedPassageRecord> = emptyList(),
    val fullAnswer: String = ""
)

data class MappedProfileSpan(val start: Int, val end: Int, val profileUid: String, val profileUids: Set<String> = setOf(profileUid))

data class MarkdownSourceMap(
    val answerDigest: String,
    val markdownDigest: String,
    val runs: List<MarkdownSourceRun>
) {
    fun wordingSpans(original: String, markdown: String, sources: List<CombinedModelResponse>, passages: List<dev.chungjungsoo.gptmobile.data.agent.CombinedPassageRecord> = emptyList(), fullAnswer: String = original, includeShared: Boolean = false): List<MappedProfileSpan> {
        if (answerDigest != researchHash(original) || markdownDigest != researchHash(markdown)) return emptyList()
        if (runs.any {
                it.sourceStart < 0 ||
                    it.sourceEnd > original.length ||
                    it.length <= 0 ||
                    it.transformedStart < 0 ||
                    it.transformedStart.toLong() + it.length > markdown.length ||
                    original.substring(it.sourceStart, it.sourceEnd) != markdown.substring(it.transformedStart, it.transformedStart + it.length)
            }
        ) {
            return emptyList()
        }
        // Unchecked model labels cannot establish ownership. Validated passage links may
        // colour paraphrases, but conflicting declarations or shared source wording always
        // suppress exclusive colours.
        val fullAnswerOffset = fullAnswer.indexOf(original).takeIf { start ->
            start >= 0 && fullAnswer.indexOf(original, start + 1) < 0
        }
        val validPassages = if (fullAnswerOffset == null) emptyList() else passages.filter { it.matches(fullAnswer) }
        val scoped = validPassages.filter { it.linkVersion >= 1 }
        val knownUids = sources.mapTo(hashSetOf()) { it.platformUid }
        val links = scoped.flatMap { passage ->
            passage.links.filter { link ->
                link.start >= passage.start &&
                    link.end <= passage.end &&
                    link.matches(fullAnswer) &&
                    link.identityLinkStatus == "KNOWN_OCCURRENCES" &&
                    link.semanticSupportStatus == "CONSERVATIVE_LINK_CHECKED" &&
                    link.profileUids.all { it in knownUids }
            }
        }
        val scopedMapped = links.filter { link ->
            link.profileUids.isNotEmpty() && (includeShared || link.profileUids.size == 1) && links.none { other -> other.start < link.end && other.end > link.start && other.profileUids != link.profileUids }
        }.flatMap { link ->
            runs.mapNotNull { run ->
                val start = maxOf(link.start - requireNotNull(fullAnswerOffset), run.sourceStart)
                val end = minOf(link.end - fullAnswerOffset, run.sourceEnd)
                if (start >= end) null else MappedProfileSpan(run.transformedStart + start - run.sourceStart, run.transformedStart + end - run.sourceStart, link.profileUids.sorted().first(), link.profileUids)
            }
        }
        // Math placeholders and removed metadata have no prose source run. Mask those
        // gaps without moving UTF-16 offsets so their numbers cannot invalidate a claim.
        val prose = CharArray(original.length) { if (original[it] == '\n') '\n' else ' ' }
        runs.forEach { run -> original.toCharArray(prose, run.sourceStart, run.sourceStart, run.sourceEnd) }
        val wording = combinedWordingSpans(String(prose), sources)
        val spans = wording.filter { span ->
            val absoluteStart = (fullAnswerOffset ?: 0) + span.start
            val absoluteEnd = (fullAnswerOffset ?: 0) + span.end
            if (links.any { it.start < absoluteEnd && it.end > absoluteStart }) return@filter false
            if (scoped.any { it.start < absoluteEnd && it.end > absoluteStart }) {
                // Only exact source identity can fill a protocol gap. Lexical resemblance
                // remains a diagnostic hint and never manufactures an exclusive owner.
                val exactOwners = dev.chungjungsoo.gptmobile.data.agent.combinedVerbatimProfiles(original.substring(span.start, span.end), sources)
                if (exactOwners != span.profileUids) return@filter false
            }
            val owner = span.profileUids.singleOrNull() ?: return@filter includeShared
            val start = (fullAnswerOffset ?: 0) + span.start
            val end = (fullAnswerOffset ?: 0) + span.end
            validPassages.none { passage ->
                passage.semanticLinkStatus == "CONSERVATIVE_LINK_CHECKED" && passage.start < end && passage.end > start && owner !in passage.profileUids
            }
        }
        val sourceUids = sources.map { it.platformUid }.toSet()
        val declared = validPassages.filter { passage ->
            passage.linkVersion == 0 &&
                passage.semanticLinkStatus == "CONSERVATIVE_LINK_CHECKED" &&
                passage.profileUids.size == 1 &&
                passage.profileUids.single() in sourceUids &&
                validPassages.none { other -> other.start < passage.end && other.end > passage.start && other.profileUids != passage.profileUids } &&
                wording.none { span ->
                    (fullAnswerOffset ?: 0) + span.start < passage.end &&
                        (fullAnswerOffset ?: 0) + span.end > passage.start &&
                        span.profileUids != passage.profileUids
                }
        }
        val declaredMapped = declared.flatMap { passage ->
            runs.mapNotNull { run ->
                val start = maxOf(passage.start - requireNotNull(fullAnswerOffset), run.sourceStart)
                val end = minOf(passage.end - fullAnswerOffset, run.sourceEnd)
                if (start >= end) null else MappedProfileSpan(run.transformedStart + start - run.sourceStart, run.transformedStart + end - run.sourceStart, passage.profileUids.single())
            }
        }
        return scopedMapped + declaredMapped + spans.filter { span ->
            declared.none { it.start < (fullAnswerOffset ?: 0) + span.end && it.end > (fullAnswerOffset ?: 0) + span.start }
        }.flatMap { span ->
            val uid = span.profileUids.sorted().firstOrNull() ?: return@flatMap emptyList()
            runs.mapNotNull { run ->
                val start = maxOf(span.start, run.sourceStart)
                val end = minOf(span.end, run.sourceEnd)
                if (start >= end) null else MappedProfileSpan(run.transformedStart + start - run.sourceStart, run.transformedStart + end - run.sourceStart, uid, span.profileUids)
            }
        }
    }

    /** No raw source offset is handed to the renderer. Invalid/stale maps produce no overrides. */
    fun exclusiveSpans(
        original: String,
        markdown: String,
        provenance: ValidatedCombinedProvenance,
        corpusDigest: String,
        policyRevision: String
    ): List<MappedProfileSpan> {
        if (answerDigest != researchHash(original) ||
            markdownDigest != researchHash(markdown) ||
            provenance.answerDigest != answerDigest ||
            provenance.corpusDigest != corpusDigest ||
            provenance.policyRevision != policyRevision ||
            provenance.parserVersion != ATTRIBUTION_MARKDOWN_PARSER_VERSION ||
            provenance.warning != null
        ) {
            return emptyList()
        }
        if (runs.any {
                it.sourceStart < 0 ||
                    it.sourceEnd > original.length ||
                    it.length <= 0 ||
                    it.transformedStart < 0 ||
                    it.transformedStart.toLong() + it.length > markdown.length ||
                    original.substring(it.sourceStart, it.sourceEnd) != markdown.substring(it.transformedStart, it.transformedStart + it.length)
            }
        ) {
            return emptyList()
        }
        val ranges = provenance.sentences.sortedBy { it.startUtf16 }
        if (ranges.zipWithNext().any { (a, b) -> a.endUtf16 > b.startUtf16 }) return emptyList()
        return ranges.flatMap { sentence ->
            val uid = sentence.exclusiveProfileUid ?: return@flatMap emptyList()
            runs.mapNotNull { run ->
                val start = maxOf(sentence.startUtf16, run.sourceStart)
                val end = minOf(sentence.endUtf16, run.sourceEnd)
                if (start >= end) null else MappedProfileSpan(run.transformedStart + start - run.sourceStart, run.transformedStart + end - run.sourceStart, uid)
            }
        }
    }
}

internal fun buildMappedMarkdown(original: String, visibleSource: String, parsed: ParsedChatMarkdown, nonce: String): Pair<String, MarkdownSourceMap> {
    val offsets = mutableMapOf<Int, Int>()
    val markdown = buildCombinedMarkdown(parsed.blocks, nonce) { index, offset -> offsets[index] = offset }
    // ConversationSubject only removes a leading prefix. Verify that contract before shifting any map.
    val removedPrefix = original.length - visibleSource.length
    val runs = if (removedPrefix < 0 || !original.endsWith(visibleSource)) {
        emptyList()
    } else {
        parsed.sourceRuns.mapNotNull { run ->
            offsets[run.blockIndex]?.let { offset ->
                run.copy(sourceStart = run.sourceStart + removedPrefix, sourceEnd = run.sourceEnd + removedPrefix, transformedStart = run.transformedStart + offset)
            }
        }
    }
    return markdown to MarkdownSourceMap(researchHash(original), researchHash(markdown), runs)
}
