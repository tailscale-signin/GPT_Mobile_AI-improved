package dev.chungjungsoo.gptmobile.data.voice

internal data class VoicePlaybackPosition(val frames: Long, val basis: String)

/** Converts a valid route timestamp to the presentation clock, with a conservative fallback. */
internal class VoicePlaybackClock(private val sampleRate: Int) {
    private var last = 0L

    fun position(head: Long, frame: Long?, timestampNanos: Long?, nowNanos: Long, playing: Boolean): VoicePlaybackPosition {
        require(head >= 0)
        val age = timestampNanos?.let { nowNanos - it }
        val valid = frame != null && frame >= 0 && age != null && age in -100_000_000L..500_000_000L
        val predicted = if (valid && playing) frame + age * sampleRate / 1_000_000_000L else head
        val current = if (playing) predicted.coerceIn(last.coerceAtMost(head), head) else last.coerceAtMost(head)
        last = current
        return VoicePlaybackPosition(current, if (valid && playing) "audio_timestamp" else "playback_head")
    }

    fun routeChanged() {
        last = 0
    }

    companion object {
        fun captionFrames(presented: Long, sampleRate: Int, offsetMs: Int, drainElapsedMs: Long = 0): Long {
            val offset = offsetMs.coerceIn(-500, 500)
            val tail = if (offset > 0) drainElapsedMs.coerceIn(0, offset.toLong()) else 0L
            return (presented - (offset - tail) * sampleRate / 1_000).coerceAtLeast(0)
        }
    }
}
