package dev.chungjungsoo.gptmobile.data.voice

import kotlin.math.abs

internal enum class VoiceSpeechJoinPunctuation { NONE, CLAUSE, SENTENCE }
internal enum class VoiceSpeechJoinBoundary { PHRASE, TOKEN_LIMIT }

/** A native phrase join in the retained PCM timeline. A refill is wall time observed by the AudioTrack writer. */
internal data class VoiceSpeechPhraseJoin(
    val startFrame: Long,
    val boundary: VoiceSpeechJoinBoundary,
    val punctuation: VoiceSpeechJoinPunctuation,
    val rawQuietMs: Long,
    val retainedQuietMs: Long,
    val headroomMs: Long,
    val textWaitMs: Long,
    val refillMs: Long = 0
) {
    val readyUnpunctuatedGapMs: Long?
        get() = (retainedQuietMs + refillMs).takeIf { punctuation == VoiceSpeechJoinPunctuation.NONE && textWaitMs == 0L }
}

internal class VoiceSpeechJoinTracker {
    private var rawTail = 0
    private var retainedTail = 0
    private var previousBoundary: VoiceSpeechJoinBoundary? = null
    private var punctuation = VoiceSpeechJoinPunctuation.NONE

    fun observe(last: Char, tokenCut: Boolean, raw: FloatArray, retained: FloatArray, frame: Long, headroomMs: Long, textWaitMs: Long, rate: Int): VoiceSpeechPhraseJoin? {
        fun quietMs(tail: Int, samples: FloatArray): Long {
            val first = samples.indexOfFirst { abs(it) > QUIET }
            return (tail + (if (first < 0) samples.size else first)) * 1000L / rate
        }
        val join = previousBoundary?.let { VoiceSpeechPhraseJoin(frame, it, punctuation, quietMs(rawTail, raw), quietMs(retainedTail, retained), headroomMs, textWaitMs) }
        rawTail = raw.size - 1 - raw.indexOfLast { abs(it) > QUIET }
        retainedTail = retained.size - 1 - retained.indexOfLast { abs(it) > QUIET }
        previousBoundary = if (tokenCut) VoiceSpeechJoinBoundary.TOKEN_LIMIT else VoiceSpeechJoinBoundary.PHRASE
        punctuation = when (last) {
            '.', '!', '?' -> VoiceSpeechJoinPunctuation.SENTENCE
            ',', ';', ':' -> VoiceSpeechJoinPunctuation.CLAUSE
            else -> VoiceSpeechJoinPunctuation.NONE
        }
        return join
    }

    companion object {
        const val QUIET = .0003f
    }
}
