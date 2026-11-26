package dev.chungjungsoo.gptmobile.data.voice

import java.text.BreakIterator
import java.util.Locale

internal data class VoiceStreamingPhrase(val text: String, val sourceCharacters: List<IntRange>)

/** Natural SDK input stays bounded while byte cues can still point to the original displayed segment. */
internal class VoiceStreamingPhrasePlan(input: String, dictionary: VoicePronunciationDictionary, speed: Float, englishFallback: Boolean = false) {
    constructor(input: String, overrides: Map<String, String>, speed: Float) : this(input, VoicePronunciationDictionary(overrides), speed)

    private val groups = VoicePhraseBoundaries(input)
    private val text: String
    private val sources: List<IntRange>
    private var consumed = 0
    private val characterLimit = (128 * VoiceCpuCalibrationProtocol.rate(speed)).toInt().coerceIn(64, 192)
    private val characters = BreakIterator.getCharacterInstance(Locale.ROOT)

    init {
        val projection = dictionary.project(input, englishFallback)
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
        // A bounded phrase can stop immediately before a word separator. Keep that
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
                if (words >= limit || VoicePhraseBoundaries.endsSentence(text, last)) break
            }
        }
        if (end == consumed) {
            // A separator may cross the ceiling even when the preceding group fits.
            // Find a character boundary, then require a complete pronunciation group below.
            end = if (ceiling == text.length || characters.isBoundary(ceiling)) ceiling else characters.preceding(ceiling)
            if (end <= consumed) end = characters.following(consumed)
        }
        // Do not send a whitespace-only phrase or silently discard an oversized
        // separator to make it fit. The same bound applies to incremental SDK input.
        require(end > firstContent && end <= ceiling) { "STREAM_INPUT_LIMIT" }
        var lastContent = end - 1
        while (lastContent > consumed && text[lastContent].isWhitespace()) lastContent--
        var nextContent = end
        while (nextContent < text.length && text[nextContent].isWhitespace()) nextContent++
        if (nextContent < text.length) {
            val left = sources[lastContent]
            val right = sources[nextContent]
            // At the SDK ceiling, adjacent literal characters can continue in the next input.
            // Repeated source ranges belong to a pronunciation, and contextual readings span words.
            val literalContinuation = nextContent == lastContent + 1 &&
                left.first == left.last &&
                right.first == right.last &&
                right.first == left.last + 1 &&
                !groups.isContextual(left.first)
            require(groups.groupAt(left.first) != groups.groupAt(right.first) || literalContinuation) { "STREAM_INPUT_LIMIT" }
        }
        val phrase = VoiceStreamingPhrase(text.substring(consumed, end), sources.subList(consumed, end))
        consumed = end
        return phrase
    }

    private companion object {
        val chunkWords = Regex("\\S+\\s*")
    }
}
