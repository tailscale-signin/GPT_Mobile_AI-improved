package dev.chungjungsoo.gptmobile.data.voice

import com.google.mediapipe.tasks.audio.texttospeech.WordTimestamp
import java.util.TreeMap
import kotlin.math.roundToLong

/**
 * The 1.2.3 C API reports UTF-8 byte offsets and estimated times. Google's released
 * mediapipe_audio 0.4.1 native wrapper documents times from the stream's start and
 * forwards them unchanged; neither offsets nor times are relative to a PCM chunk.
 *
 * Cumulative/repeated spans are emitted once. Cues beyond the audio produced so far
 * stay pending, so truncated or invalid metadata cannot point past audible PCM.
 */
internal class MediaPipeWordCueMapper(text: String) {
    private val boundaries = VoiceStreamFrames.utf8Boundaries(text)
    private val pending = TreeMap<Int, VoiceWordCue>()
    private val emitted = mutableSetOf<Pair<Int, Int>>()
    private var lastEndByte = 0
    private var lastEndFrame = 0L

    fun accept(timestamps: List<WordTimestamp>, availableFrames: Long): List<VoiceWordCue> {
        timestamps.take(VoiceStreamFrames.MAX_CUES).forEach { timestamp ->
            val start = timestamp.startChar
            val end = timestamp.endChar
            if (start < lastEndByte ||
                end <= start ||
                boundaries.getOrNull(start)?.takeIf { it >= 0 } == null ||
                boundaries.getOrNull(end)?.takeIf { it >= 0 } == null ||
                (start to end) in emitted
            ) {
                return@forEach
            }
            if (!timestamp.startSec.isFinite() || !timestamp.endSec.isFinite() || timestamp.startSec < 0f || timestamp.endSec <= timestamp.startSec) return@forEach
            val startFrame = (timestamp.startSec.toDouble() * VOICE_PCM_SAMPLE_RATE).roundToLong()
            val endFrame = (timestamp.endSec.toDouble() * VOICE_PCM_SAMPLE_RATE).roundToLong()
            if (startFrame < lastEndFrame || endFrame <= startFrame || endFrame > VoiceStreamFrames.MAX_STREAM_FRAMES) return@forEach
            if (pending.size + emitted.size >= VoiceStreamFrames.MAX_CUES) return@forEach
            pending.putIfAbsent(start, VoiceWordCue(start, end, startFrame, endFrame, VoiceWordCueProvenance.ESTIMATED))
        }
        val ready = mutableListOf<VoiceWordCue>()
        val entries = pending.entries.iterator()
        while (entries.hasNext()) {
            val cue = entries.next().value
            if (cue.startByte < lastEndByte || cue.startFrame < lastEndFrame) {
                entries.remove()
                continue
            }
            if (cue.endFrame > availableFrames) break
            entries.remove()
            emitted += cue.startByte to cue.endByte
            lastEndByte = cue.endByte
            lastEndFrame = cue.endFrame
            ready += cue
        }
        return ready
    }
}
