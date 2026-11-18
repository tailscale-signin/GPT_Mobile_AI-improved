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
        return words.firstOrNull { range.first in it } ?: words.firstOrNull { it.first <= range.last && it.last >= range.first }
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
            val word = text.substring(start, end).any(Char::isLetterOrDigit) ||
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
            val text = if (response) state.reply else state.transcript
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

/** Separates a reader's scroll from the caption's own movement to a timed phrase. */
internal class VoiceCaptionFollowing(initialPosition: Int = 0) {
    var following: Boolean = true
        private set
    private var previousPosition = initialPosition
    private var automaticScrolls = 0

    fun pause() {
        following = false
    }

    fun beginAutomaticScroll() {
        automaticScrolls++
    }

    fun endAutomaticScroll(position: Int) {
        // Record the final position before the observer sees the end of an animation.
        previousPosition = position
        automaticScrolls = (automaticScrolls - 1).coerceAtLeast(0)
    }

    fun observe(position: Int, maximum: Int): Boolean {
        if (automaticScrolls == 0) {
            if (position < previousPosition) following = false
            if (position >= maximum - 1) following = true
        }
        previousPosition = position
        return following
    }

    fun userScroll(position: Int, maximum: Int): Boolean {
        following = position >= maximum - 1
        previousPosition = position
        return following
    }
}
