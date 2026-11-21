package dev.chungjungsoo.gptmobile.presentation.ui.voice

import dev.chungjungsoo.gptmobile.data.voice.VoicePhase
import dev.chungjungsoo.gptmobile.data.voice.VoiceState
import java.text.BreakIterator
import java.util.Locale

/** Retains the complete phrase; a highlight requires an observed range in this exact text. */
internal data class VoiceCaption(val text: String, val speaker: String, val live: Boolean) {
    /** Speech engines may report a phrase. Never illuminate more than one spoken word. */
    fun highlightRange(observed: IntRange?, words: List<IntRange> = wordRanges()): IntRange? {
        val range = observed?.takeIf {
            speaker == "AI reply" &&
                !it.isEmpty() &&
                it.first >= 0 &&
                it.last < text.length &&
                isCharacterBoundary(it.first) &&
                isCharacterBoundary(it.last + 1)
        } ?: return null
        // Speech ranges advance through long replies. Find the first intersecting
        // word without walking the entire preceding transcript on every callback.
        val index = words.binarySearchBy(range.first) { it.last }
        val first = if (index >= 0) index else -index - 1
        return words.getOrNull(first)?.takeIf { it.first <= range.last }
    }

    /** BreakIterator respects punctuation, non-Latin text and surrogate boundaries. */
    fun wordRanges(): List<IntRange> {
        val iterator = BreakIterator.getWordInstance(Locale.getDefault())
        iterator.setText(text)
        val ranges = mutableListOf<IntRange>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val firstPoint = text.codePointAt(start)
            val word = Character.isLetterOrDigit(firstPoint) ||
                text.substring(start, end).any(Char::isLetterOrDigit) ||
                Character.getType(firstPoint) == Character.OTHER_SYMBOL.toInt()
            if (word && isCharacterBoundary(start) && isCharacterBoundary(end)) ranges += start until end
            start = end
            end = iterator.next()
        }
        return ranges
    }

    private fun isCharacterBoundary(index: Int): Boolean =
        index == 0 || index == text.length || !(text[index].isLowSurrogate() && text[index - 1].isHighSurrogate())

    companion object {
        fun from(state: VoiceState): VoiceCaption? {
            val capturing = state.phase in setOf(VoicePhase.STARTING, VoicePhase.LISTENING, VoicePhase.FINALIZING, VoicePhase.REVIEWING) || state.hasDraft
            val response = !capturing && state.reply.isNotBlank()
            val text = if (response) state.playbackCaption ?: state.reply else state.transcript
            if (text.isBlank()) return null
            val live = if (response) {
                state.phase in setOf(VoicePhase.THINKING, VoicePhase.PREPARING, VoicePhase.SPEAKING)
            } else {
                state.phase in setOf(VoicePhase.LISTENING, VoicePhase.FINALIZING)
            }
            return VoiceCaption(text, if (response) "AI reply" else "You", live)
        }
    }
}

internal fun captionLineScale(offset: Int): Float = 2f - kotlin.math.abs(offset) * .5f

internal fun captionLineAlpha(offset: Int): Float = 1f - kotlin.math.abs(offset) * .25f

/** Holds a fractional line while browsing. Scrolling to the end resumes the playback cursor. */
internal data class VoiceCaptionReader(private val manualLine: Float? = null) {
    val following get() = manualLine == null

    fun position(live: Int, last: Int): Float = (manualLine ?: live.toFloat()).coerceIn(0f, last.toFloat())

    fun scroll(line: Float, last: Int) = VoiceCaptionReader(line.takeIf { it < last }?.coerceAtLeast(0f))
}
