package dev.chungjungsoo.gptmobile.data.voice

/** An immutable observation of the caption clock; monotonic nanoseconds share System.nanoTime's domain. */
data class VoiceCaptionTiming(
    val playbackId: String,
    val wordStartFrame: Long,
    val wordEndFrame: Long,
    val presentedFrame: Long,
    val sampleRate: Int,
    val observedAtNanos: Long,
    val playing: Boolean,
    val routeEpoch: Long = 0,
    val bufferedEndFrame: Long = wordEndFrame,
    val cueProvenance: VoiceWordCueProvenance = VoiceWordCueProvenance.NATIVE_DURATION
) {
    init {
        require(sampleRate > 0 && wordStartFrame >= 0 && wordEndFrame >= wordStartFrame && presentedFrame >= 0)
    }

    /** UI interpolation never invents PCM beyond the producer's accepted buffer or advances a paused clock. */
    fun frameAt(nowNanos: Long): Long {
        if (!playing) return presentedFrame
        val elapsed = (nowNanos - observedAtNanos).coerceIn(0, 1_000_000_000L)
        return (presentedFrame + elapsed * sampleRate / 1_000_000_000L).coerceAtMost(maxOf(presentedFrame, bufferedEndFrame))
    }
}

internal typealias TimedSpokenRangeObserver = (Int, String, IntRange, VoiceCaptionTiming) -> Unit
