package dev.chungjungsoo.gptmobile.data.voice

import com.google.mediapipe.tasks.audio.texttospeech.WordTimestamp
import java.util.TreeMap
import kotlin.math.roundToLong

/**
 * The SDK reports UTF-8 offsets and estimated stream-relative times, not acoustic
 * alignment. Publish only observed PCM, replacing future estimates with newer
 * packets. Corrections to published intervals carry an explicit bounded revision;
 * the presentation queue decides whether that word has already been heard.
 */
internal class MediaPipeWordCueMapper(
    text: String,
    private val frameLimit: Long = VoiceStreamFrames.MAX_STREAM_FRAMES,
    private val cueLimit: Int = VoiceStreamFrames.MAX_CUES
) {
    private val boundaries = VoiceIncrementalTextMap().also { it.append(text) }
    private val pending = TreeMap<Int, VoiceWordCue>()
    private val published = TreeMap<Int, VoiceWordCue>()
    private val corrections = TreeMap<Int, VoiceWordCue>()
    private var emittedCount = 0
    private var lastEndByte = 0
    private var lastEndFrame = 0L
    private var retiredEndFrame = 0L
    private var openPrefix: VoiceWordCue? = null

    fun appendText(text: String) = boundaries.append(text)

    /** Validated SDK metadata may precede the PCM released by the caption buffer. */
    val timestampedThroughFrame: Long
        get() = maxOf(lastEndFrame, pending.values.maxOfOrNull { it.endFrame } ?: 0L)

    fun accept(timestamps: List<WordTimestamp>, availableFrames: Long): List<VoiceWordCue> {
        require(availableFrames in 0..frameLimit)
        retireHistory(availableFrames)
        for ((index, timestamp) in timestamps.withIndex()) {
            if (index >= cueLimit) break
            val start = timestamp.startChar
            val end = timestamp.endChar
            if (end <= start || !boundaries.contains(start, end)) continue
            if (!timestamp.startSec.isFinite() || !timestamp.endSec.isFinite() || timestamp.startSec < 0f || timestamp.endSec <= timestamp.startSec) continue
            val startFrame = (timestamp.startSec.toDouble() * VOICE_PCM_SAMPLE_RATE).roundToLong()
            val endFrame = (timestamp.endSec.toDouble() * VOICE_PCM_SAMPLE_RATE).roundToLong()
            if (startFrame < 0 || endFrame <= startFrame || endFrame > frameLimit) continue
            val next = VoiceWordCue(start, end, startFrame, endFrame, VoiceWordCueProvenance.ESTIMATED)
            val previous = published[start]
            if (previous != null) {
                if (previous.endByte != end) continue
                if (previous.startFrame == startFrame && previous.endFrame == endFrame) {
                    corrections.remove(start)
                    continue
                }
                // PCM production is ahead of presentation. A shorter final-word estimate
                // must reach the caption queue, which owns the actual heard-word watermark.
                // Updating lastEndFrame below also keeps a corrected tail from hiding the
                // next word when its SDK timestamp arrives in a later packet.
                if (previous.revision == Int.MAX_VALUE) continue
                corrections[start] = next.copy(revision = previous.revision + 1)
                continue
            }
            val open = openPrefix
            if (open != null && start == open.startByte) {
                // Observed prefixes keep their identity/start. A shorter estimate may
                // finalize the word, but may never retract the PCM prefix already sent.
                if (end == open.endByte && startFrame == open.startFrame && endFrame >= open.endFrame) pending[start] = next
                continue
            }
            if (start < (open?.endByte ?: lastEndByte)) continue
            val queued = pending[start]
            if (queued != null && queued.endByte != end) continue
            if (queued == null && pending.size + emittedCount >= cueLimit) continue
            // Frame order is checked after this packet's corrections are applied: a
            // shortened preceding estimate may release this newly reported word.
            // Repeated SDK packets may improve a word which is still ahead of PCM.
            pending[start] = next
        }
        val ready = mutableListOf<VoiceWordCue>()
        // Adjacent estimates can move a shared boundary in either direction. Stage
        // the whole packet, then publish each correction only when its neighbours
        // permit it. Revisit blocked peers after a neighbour moves; consumers see
        // valid, non-overlapping intervals at every step, without losing a revision.
        var revisedAny: Boolean
        do {
            revisedAny = false
            val revised = corrections.entries.iterator()
            while (revised.hasNext()) {
                val correction = revised.next().value
                if (correction.endFrame > availableFrames) continue
                val before = published.lowerEntry(correction.startByte)?.value?.endFrame ?: retiredEndFrame
                val following = published.higherEntry(correction.startByte)?.value ?: pending.higherEntry(correction.startByte)?.value
                if (correction.startFrame >= before && (following == null || correction.endFrame <= following.startFrame)) {
                    published[correction.startByte] = correction
                    if (correction.endByte == lastEndByte) lastEndFrame = correction.endFrame
                    ready += correction
                    revised.remove()
                    revisedAny = true
                }
            }
        } while (revisedAny && corrections.isNotEmpty())
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
            emittedCount++
            lastEndByte = cue.endByte
            lastEndFrame = cue.endFrame
            published[cue.startByte] = cue
            ready += cue
        }
        retireHistory(availableFrames)
        return ready
    }

    /** A truncated estimate closes where the audio ended; a wholly unheard word stays unpublished. */
    fun finish(): VoiceWordCue? = openPrefix?.copy(finalEnd = true).also {
        openPrefix = null
        pending.clear()
        corrections.clear()
        published.clear()
    }

    private fun retireHistory(availableFrames: Long) {
        val cutoff = availableFrames - REVISION_HISTORY_FRAMES
        while (published.isNotEmpty() &&
            (published.size > VoiceStreamFrames.MAX_REVISION_WORDS || published.firstEntry().value.endFrame < cutoff)
        ) {
            val retired = published.pollFirstEntry().value
            retiredEndFrame = retired.endFrame
            corrections.remove(retired.startByte)
        }
    }

    companion object {
        const val REVISION_HISTORY_FRAMES = VOICE_PCM_SAMPLE_RATE * 60L
    }
}
