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

data class MappedProfileSpan(val start: Int, val end: Int, val profileUid: String)

data class MarkdownSourceMap(
    val answerDigest: String,
    val markdownDigest: String,
    val runs: List<MarkdownSourceRun>
) {
    fun wordingSpans(original: String, markdown: String, sources: List<CombinedModelResponse>, passages: List<dev.chungjungsoo.gptmobile.data.agent.CombinedPassageRecord> = emptyList(), fullAnswer: String = original): List<MappedProfileSpan> {
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
        val offset = fullAnswer.indexOf(original)
        val allowed = sources.mapTo(hashSetOf()) { it.platformUid }
        val explicit = if (offset >= 0 && fullAnswer.indexOf(original, offset + 1) < 0) {
            passages.filter { it.matches(fullAnswer) && it.profileUids.all(allowed::contains) }.mapNotNull { passage ->
                val start = maxOf(0, passage.start - offset)
                val end = minOf(original.length, passage.end - offset)
                if (end > start) dev.chungjungsoo.gptmobile.data.agent.CombinedWordingSpan(start, end, passage.profileUids) else null
            }
        } else {
            emptyList()
        }
        val spans = explicit.ifEmpty { combinedWordingSpans(original, sources) }
        return spans.flatMap { span ->
            val uid = span.profileUids.sorted().firstOrNull() ?: return@flatMap emptyList()
            runs.mapNotNull { run ->
                val start = maxOf(span.start, run.sourceStart)
                val end = minOf(span.end, run.sourceEnd)
                if (start >= end) null else MappedProfileSpan(run.transformedStart + start - run.sourceStart, run.transformedStart + end - run.sourceStart, uid)
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
