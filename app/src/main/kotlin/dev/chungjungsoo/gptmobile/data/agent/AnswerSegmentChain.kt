package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.AnswerSegment
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.responseSourceRanges
import dev.chungjungsoo.gptmobile.data.research.researchHash

internal object AnswerSegmentChain {
    fun text(segments: List<AnswerSegment>, runId: String, fence: Long): String {
        var previous: String? = null
        return buildString {
            segments.forEachIndexed { index, segment ->
                require(segment.runId == runId && segment.fence == fence && segment.sequence == index) { "Invalid answer segment identity or order" }
                require(segment.previousDigest == previous && segment.digest == researchHash(segment.text) && segment.utf8Bytes == segment.text.toByteArray(Charsets.UTF_8).size) { "Answer segment integrity failure" }
                append(segment.text)
                previous = segment.digest
            }
        }
    }

    fun append(segments: List<AnswerSegment>, runId: String, fence: Long, draft: String, timeline: List<AssistantTimelineItem> = emptyList()): List<AnswerSegment> {
        val prefix = text(segments, runId, fence)
        require(draft.startsWith(prefix)) { "A continuation cannot replace a committed answer prefix." }
        val appended = mutableListOf<AnswerSegment>()
        var previous = segments.lastOrNull()?.digest
        var offset = prefix.length
        val ranges = responseSourceRanges(draft, timeline)
        var sourceIndex = 0
        while (offset < draft.length) {
            while (sourceIndex < ranges.lastIndex && ranges[sourceIndex].end <= offset) sourceIndex++
            val source = ranges.getOrNull(sourceIndex)
            var end = minOf(draft.length, offset + 8192, source?.end ?: draft.length)
            if (end < draft.length && Character.isHighSurrogate(draft[end - 1]) && Character.isLowSurrogate(draft[end])) {
                if (end - offset > 1) end-- else end++
            }
            val body = draft.substring(offset, end)
            val digest = researchHash(body)
            val attributedSource = source?.source?.takeIf { end <= source.end }
            appended += AnswerSegment(runId, segments.size + appended.size, fence, body, digest, previous, body.toByteArray(Charsets.UTF_8).size, attributedSource)
            previous = digest
            offset = end
        }
        return appended
    }
}
