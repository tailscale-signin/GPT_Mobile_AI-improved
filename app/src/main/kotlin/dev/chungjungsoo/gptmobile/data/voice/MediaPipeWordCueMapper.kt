package dev.chungjungsoo.gptmobile.data.voice

import com.google.mediapipe.tasks.audio.texttospeech.WordTimestamp
import java.util.TreeMap
import kotlin.math.roundToLong

/**
 * The 1.2.3 C API reports UTF-8 byte offsets and estimated times. Google's released
 * mediapipe_audio 0.4.1 native wrapper documents times from the stream's start and
 * forwards them unchanged; neither offsets nor times are relative to a PCM chunk.
 *
 * Release each word at its first available PCM, extending the same cue as more
 * audio arrives. Never project a timestamp past the PCM actually received.
 */
internal class MediaPipeWordCueMapper(
    text: String,
    private val frameLimit: Long = VoiceStreamFrames.MAX_STREAM_FRAMES,
    private val cueLimit: Int = VoiceStreamFrames.MAX_CUES
) {
    private val boundaries = VoiceIncrementalTextMap().also { it.append(text) }
    private val pending = TreeMap<Int, VoiceWordCue>()
    private val emitted = mutableSetOf<Pair<Int, Int>>()
    private var lastEndByte = 0
    private var lastEndFrame = 0L
    private var openPrefix: VoiceWordCue? = null

    fun appendText(text: String) = boundaries.append(text)

    fun accept(timestamps: List<WordTimestamp>, availableFrames: Long): List<VoiceWordCue> {
        timestamps.take(cueLimit).forEach { timestamp ->
            val start = timestamp.startChar
            val end = timestamp.endChar
            if (start < (openPrefix?.endByte ?: lastEndByte) ||
                end <= start ||
                !boundaries.contains(start, end) ||
                (start to end) in emitted
            ) {
                return@forEach
            }
            if (!timestamp.startSec.isFinite() || !timestamp.endSec.isFinite() || timestamp.startSec < 0f || timestamp.endSec <= timestamp.startSec) return@forEach
            val startFrame = (timestamp.startSec.toDouble() * VOICE_PCM_SAMPLE_RATE).roundToLong()
            val endFrame = (timestamp.endSec.toDouble() * VOICE_PCM_SAMPLE_RATE).roundToLong()
            if (startFrame < lastEndFrame || endFrame <= startFrame || endFrame > frameLimit) return@forEach
            if (pending.size + emitted.size >= cueLimit) return@forEach
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
            if (cue.startFrame >= availableFrames) break
            if (cue.endFrame > availableFrames) {
                val prefix = cue.copy(endFrame = availableFrames, finalEnd = false)
                if (openPrefix != prefix) ready += prefix
                openPrefix = prefix
                break
            }
            openPrefix = null
            entries.remove()
            emitted += cue.startByte to cue.endByte
            lastEndByte = cue.endByte
            lastEndFrame = cue.endFrame
            ready += cue
        }
        return ready
    }

    /** A truncated estimate closes where the audio ended; a wholly unheard word stays unpublished. */
    fun finish(): VoiceWordCue? = openPrefix?.copy(finalEnd = true).also {
        openPrefix = null
        pending.clear()
    }
}
