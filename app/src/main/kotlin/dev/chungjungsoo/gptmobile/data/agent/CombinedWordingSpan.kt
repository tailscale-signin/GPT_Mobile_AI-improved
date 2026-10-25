package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse

/** Source-aligned wording hints, never factual verification or exclusive authorship. */
data class CombinedWordingSpan(val start: Int, val end: Int, val profileUids: Set<String>)

/** All consumers use the same versioned claim map and preserve shared ownership. */
fun combinedWordingSpans(answer: String, sources: List<CombinedModelResponse>): List<CombinedWordingSpan> =
    CombinedAnswerMappings.get(answer, sources).links.map { CombinedWordingSpan(it.start, it.end, it.profileUids) }
