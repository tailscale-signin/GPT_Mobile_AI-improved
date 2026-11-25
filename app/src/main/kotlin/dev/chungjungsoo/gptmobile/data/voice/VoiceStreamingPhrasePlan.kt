package dev.chungjungsoo.gptmobile.data.voice

import java.text.BreakIterator
import java.util.Locale

internal data class VoiceStreamingPhrase(val text: String, val sourceCharacters: List<IntRange>)

/** Natural SDK input stays bounded while byte cues can still point to the original displayed segment. */
internal class VoiceStreamingPhrasePlan(input: String, overrides: Map<String, String>, speed: Float) {
    private val groups = VoicePhraseBoundaries(input)
    private val text: String
    private val sources: List<IntRange>
    private var consumed = 0
    private val characterLimit = (128 * VoiceCpuCalibrationProtocol.rate(speed)).toInt().coerceIn(64, 192)
    private val characters = BreakIterator.getCharacterInstance(Locale.ROOT)

    init {
        val projection = VoicePronunciationOverrides.project(input, overrides)
        text = projection.text
        sources = projection.sourceCharacters
        characters.setText(text)
    }

    fun next(wordLimit: Int): VoiceStreamingPhrase? {
        var firstContent = consumed
        while (firstContent < text.length && text[firstContent].isWhitespace()) firstContent++
        if (firstContent == text.length) {
            consumed = firstContent
            return null
        }
        // A hard split can stop immediately before a word separator. Keep that
        // original separator in the following chunk; only initial padding is skipped.
        if (consumed == 0) consumed = firstContent
        val ceiling = minOf(text.length, consumed + characterLimit)
        require(firstContent < ceiling) { "STREAM_INPUT_LIMIT" }
        val limit = wordLimit.coerceIn(4, 24)
        var end = consumed
        var words = 0
        for (word in chunkWords.findAll(text, consumed)) {
            val candidate = word.range.last + 1
            if (candidate > ceiling) break
            words++
            var last = candidate - 1
            while (last > consumed && text[last].isWhitespace()) last--
            val legal = candidate == text.length || groups.groupAt(sources[last].first) != groups.groupAt(sources[candidate].first)
            if (legal) {
                if (words > limit && end > consumed) break
                end = candidate
                if (words >= limit || text[last] in ".!?") break
            }
        }
        if (end == consumed) {
            // Oversized unbroken words still make progress without splitting a code point or
            // combining sequence. Preserve every character; no ellipsis or silent omission.
            end = if (ceiling == text.length || characters.isBoundary(ceiling)) ceiling else characters.preceding(ceiling)
            if (end <= consumed) end = characters.following(consumed)
        }
        // Do not send a whitespace-only phrase or silently discard an oversized
        // separator to make it fit. The same bound applies to incremental SDK input.
        require(end > firstContent) { "STREAM_INPUT_LIMIT" }
        val phrase = VoiceStreamingPhrase(text.substring(consumed, end), sources.subList(consumed, end))
        consumed = end
        return phrase
    }

    private companion object {
        val chunkWords = Regex("\\S+\\s*")
    }
}
