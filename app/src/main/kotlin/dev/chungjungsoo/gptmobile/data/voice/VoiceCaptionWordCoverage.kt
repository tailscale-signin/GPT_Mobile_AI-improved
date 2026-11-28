package dev.chungjungsoo.gptmobile.data.voice

/** Source-span completeness only. SDK estimates do not establish acoustic word alignment. */
internal data class VoiceCaptionWordCoverageResult(
    val expectedWords: Int,
    val coveredWords: Int,
    val individuallyTimedWords: Int,
    val groupedWordSpans: Int,
    val partialWordSpans: Int,
    val provisionalWordSpans: Int
) {
    val complete: Boolean get() = expectedWords > 0 && individuallyTimedWords == expectedWords && groupedWordSpans == 0 && partialWordSpans == 0 && provisionalWordSpans == 0
}

/** Bounded fixed-fixture validator; punctuation and unspoken symbols are not counted as lexical words. */
internal class VoiceCaptionWordCoverage(private val text: String) {
    private val words: List<IntRange>
    private val spans = linkedMapOf<IntRange, Boolean>()

    init {
        require(text.length in 1..4_000)
        words = lexicalWords.findAll(text).map { it.range }.toList()
        require(words.size <= 1_024)
    }

    fun observe(cues: List<VoiceStreamCue>) {
        cues.forEach { cue ->
            val range = cue.range
            if (range.isEmpty()) return@forEach
            require(range.first >= 0 && range.last < text.length && boundary(range.first) && boundary(range.last + 1)) { "CAPTION_SOURCE_BOUNDARY" }
            require(range in spans || spans.size < 2_048) { "CAPTION_COVERAGE_LIMIT" }
            spans[range] = spans[range] == true || cue.finalEnd
        }
    }

    fun result(): VoiceCaptionWordCoverageResult {
        val covered = mutableSetOf<IntRange>()
        val individual = mutableSetOf<IntRange>()
        var grouped = 0
        var partial = 0
        var provisional = 0
        for ((range, final) in spans) {
            val intersected = words.filter { it.first <= range.last && range.first <= it.last }
            if (intersected.isEmpty()) continue
            if (!final) provisional++
            val whole = intersected.filter { range.first <= it.first && range.last >= it.last }
            if (whole.size != intersected.size) partial++
            if (intersected.size > 1) grouped++
            if (final) {
                covered += whole
                if (intersected.size == 1 && whole.size == 1) individual += whole.single()
            }
        }
        return VoiceCaptionWordCoverageResult(words.size, covered.size, individual.size, grouped, partial, provisional)
    }

    private fun boundary(index: Int): Boolean = index == 0 || index == text.length || !(text[index].isLowSurrogate() && text[index - 1].isHighSurrogate())

    private companion object {
        val lexicalWords = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’][\\p{L}\\p{M}\\p{N}]+)*")
    }
}
