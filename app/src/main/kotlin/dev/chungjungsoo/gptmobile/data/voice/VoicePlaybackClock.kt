package dev.chungjungsoo.gptmobile.data.voice

internal data class VoicePlaybackPosition(val frames: Long, val basis: String, val presenting: Boolean)

/** Converts a valid route timestamp to the presentation clock, with a conservative fallback. */
internal class VoicePlaybackClock(private val sampleRate: Int) {
    private var last = 0L
    private var resumeAfter = 0L
    var routeEpoch = 0L
        private set

    fun position(head: Long, frame: Long?, timestampNanos: Long?, nowNanos: Long, playing: Boolean, interrupted: Boolean = false): VoicePlaybackPosition {
        require(head >= 0)
        if (interrupted || !playing) resumeAfter = last
        val age = timestampNanos?.let { nowNanos - it }
        val valid = frame != null && frame >= 0 && age != null && age in -100_000_000L..15_000_000_000L
        val predicted = if (valid && playing) frame + age * sampleRate / 1_000_000_000L else head
        val current = if (playing) maxOf(last, predicted.coerceIn(0, head)) else last
        last = current
        return VoicePlaybackPosition(current, if (valid && playing) "audio_timestamp" else "playback_head", playing && current > resumeAfter)
    }

    fun routeChanged() {
        // A new route invalidates the timestamp anchor, not words already presented.
        // A new playback owns a new clock; only that boundary may reset progress.
        resumeAfter = last
        routeEpoch++
    }

    companion object {
        fun drained(written: Long, consumed: Long, presented: Long): Boolean = consumed >= written && presented >= written

        fun captionsAdvancing(presenting: Boolean, presented: Long, caption: Long, accepted: Long, draining: Boolean): Boolean =
            presenting && presented > 0 && presented < accepted || draining && caption < accepted

        fun captionFrames(presented: Long, sampleRate: Int, offsetMs: Int, drainElapsedMs: Long = 0, acceptedFrames: Long = Long.MAX_VALUE): Long {
            val offset = offsetMs.coerceIn(-500, 500)
            val tail = if (offset > 0) drainElapsedMs.coerceIn(0, offset.toLong()) else 0L
            return (presented - (offset - tail) * sampleRate / 1_000).coerceIn(0, acceptedFrames.coerceAtLeast(0))
        }
    }
}

/** Terminal PCM must drain even when starvation left less audio than AudioTrack's restart threshold. */
internal class VoicePlaybackDrain {
    var startedAt: Long? = null
        private set
    private var releasedTail = false

    fun complete(written: Long, consumed: Long, presented: Long, nowMs: Long, captionOffsetMs: Int, releaseTail: () -> Unit): Boolean {
        if (consumed < written) {
            if (!releasedTail) {
                releaseTail()
                releasedTail = true
            }
            return false
        }
        if (presented < written) return false
        val started = startedAt ?: nowMs.also { startedAt = it }
        return nowMs - started >= captionOffsetMs.coerceAtLeast(0)
    }
}
