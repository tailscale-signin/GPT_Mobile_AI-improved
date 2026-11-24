package dev.chungjungsoo.gptmobile.data.voice

import java.text.BreakIterator
import java.util.Locale

internal data class VoiceStreamingPhrase(val text: String, val sourceCharacters: List<IntRange>)

/** Natural SDK input stays bounded while byte cues can still point to the original displayed segment. */
internal class VoiceStreamingPhrasePlan(input: String, overrides: Map<String, String>, speed: Float) {
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
        while (consumed < text.length && text[consumed].isWhitespace()) consumed++
        if (consumed == text.length) return null
        val ceiling = minOf(text.length, consumed + characterLimit)
        val limit = wordLimit.coerceIn(4, 24)
        var end = consumed
        var words = 0
        for (word in chunkWords.findAll(text, consumed)) {
            if (word.range.last + 1 > ceiling || words == limit) break
            end = word.range.last + 1
            words++
        }
        if (end == consumed) {
            // Oversized unbroken words still make progress without splitting a code point or
            // combining sequence. Preserve every character; no ellipsis or silent omission.
            end = if (ceiling == text.length || characters.isBoundary(ceiling)) ceiling else characters.preceding(ceiling)
            if (end <= consumed) end = characters.following(consumed)
        }
        val phrase = VoiceStreamingPhrase(text.substring(consumed, end), sources.subList(consumed, end))
        consumed = end
        return phrase
    }

    private companion object {
        val chunkWords = Regex("\\S+\\s*")
    }
}
