package dev.chungjungsoo.gptmobile.data.voice

internal typealias SpokenRangeObserver = (Int, String, IntRange) -> Unit

internal data class VoicePcmCue(
    val frame: Long,
    val segment: Int,
    val text: String,
    val range: IntRange,
    val endFrame: Long = frame,
    val provenance: VoiceWordCueProvenance = VoiceWordCueProvenance.NATIVE_DURATION,
    val streamStart: Long = -1,
    val startByte: Int = -1,
    val endByte: Int = -1,
    val revision: Int = 0
)

/** The pinned pack produces 600 PCM samples per predicted duration unit, at the requested speed. */
internal fun kokoroWordFrames(durations: LongArray, wordTokens: IntArray, wordCount: Int, samples: Int): IntArray {
    require(wordTokens.size == wordCount * 2)
    val prefix = IntArray(durations.size + 1)
    for ((index, units) in durations.withIndex()) {
        require(units in 1L..((samples - prefix[index]) / 600).toLong())
        prefix[index + 1] = prefix[index] + units.toInt() * 600
    }
    require(prefix.last() == samples)
    return IntArray(wordTokens.size) { i ->
        val token = wordTokens[i]
        require(token in 1 until durations.size && (i == 0 || token >= wordTokens[i - 1]))
        prefix[token]
    }
}

/** The synthesis worker registers known cue timing before the output worker can consume its PCM. */
internal class VoicePcmCaptions {
    private val pending = ArrayDeque<VoicePcmCue>()
    private var produced = 0L
    private var current: VoicePcmCue? = null
    private var lastConsumed = 0L
    private var openStreamCue: Pair<Long, VoicePcmCue>? = null
    private var lastStreamEnd = 0L
    private var currentRevised = false

    @Synchronized fun streamStart(): Long = produced

    /** Register cue metadata before accepting each PCM chunk, without buffering an entire utterance. */
    @Synchronized fun appendStream(segment: Int, text: String, streamStart: Long, cues: List<VoiceStreamCue>, frames: Int) {
        require(streamStart in 0..produced && frames >= 0)
        for (cue in cues) {
            require(cue.range.isEmpty() || cue.range.first >= 0 && cue.range.last < text.length)
            require(cue.startFrame >= 0 && cue.endFrame > cue.startFrame)
            if (!cue.range.isEmpty()) {
                val mapped = VoicePcmCue(streamStart + cue.startFrame, segment, text, cue.range, streamStart + cue.endFrame, cue.provenance, streamStart, cue.startByte, cue.endByte, cue.revision)
                if (cue.revision > 0) {
                    require(cue.finalEnd && cue.startByte >= 0 && cue.endByte > cue.startByte && mapped.endFrame <= produced + frames) { "STREAM_CUE_REVISION" }
                    reviseEstimated(mapped)
                    continue
                }
                val open = openStreamCue
                if (!cue.finalEnd || open != null) require(mapped.endFrame <= produced + frames) { "STREAM_CUE_UNOBSERVED" }
                val retained = if (open == null) {
                    require(mapped.frame >= lastStreamEnd) { "STREAM_CUE_OVERLAP" }
                    pending.addLast(mapped)
                    mapped
                } else {
                    val previous = open.second
                    require(
                        open.first == streamStart &&
                            previous.frame == mapped.frame &&
                            previous.segment == mapped.segment &&
                            previous.text == mapped.text &&
                            previous.range == mapped.range &&
                            previous.startByte == mapped.startByte &&
                            previous.endByte == mapped.endByte &&
                            previous.provenance == mapped.provenance &&
                            mapped.endFrame >= previous.endFrame
                    ) { "STREAM_CUE_REVISION" }
                    if (mapped == previous) {
                        previous
                    } else {
                        // An open cue is always the last queued cue or the current one.
                        // Replace it in place, rather than retaining one event per callback.
                        if (pending.lastOrNull() === previous) {
                            pending.removeLast()
                            pending.addLast(mapped)
                        } else {
                            check(current === previous && pending.isEmpty()) { "STREAM_CUE_STATE" }
                            current = mapped
                            currentRevised = true
                        }
                        mapped
                    }
                }
                openStreamCue = if (cue.finalEnd) null else streamStart to retained
                lastStreamEnd = retained.endFrame
                if (cue.finalEnd) pending.addLast(retained.copy(frame = retained.endFrame, range = IntRange.EMPTY))
            }
        }
        produced += frames
    }

    /**
     * The presented-frame watermark commits a word's identity and onset. Delayed
     * SDK estimates can repair queued words and an active word's end, but cannot
     * move its heard onset or replay completed words. Native duration cues remain
     * authoritative for their stream.
     */
    private fun reviseEstimated(mapped: VoicePcmCue) {
        val index = pending.indexOfFirst {
            !it.range.isEmpty() && it.streamStart == mapped.streamStart && it.startByte == mapped.startByte
        }
        if (index < 0) {
            val previous = current?.takeIf { it.streamStart == mapped.streamStart && it.startByte == mapped.startByte } ?: return
            require(
                previous.segment == mapped.segment &&
                    previous.text == mapped.text &&
                    (previous.range.isEmpty() || previous.range == mapped.range) &&
                    previous.endByte == mapped.endByte &&
                    previous.provenance == mapped.provenance &&
                    mapped.revision > previous.revision
            ) { "STREAM_CUE_REVISION" }
            if (previous.provenance == VoiceWordCueProvenance.NATIVE_DURATION) return
            if (previous.range.isEmpty()) {
                // A final estimate can arrive after its old end was presented. Keep
                // the completed state, but release its revised bound for later cues.
                if (pending.isEmpty() && openStreamCue == null) {
                    lastStreamEnd = mapped.endFrame
                    current = mapped.copy(frame = mapped.endFrame, range = IntRange.EMPTY)
                }
                return
            }
            val marker = pending.firstOrNull() ?: return
            if (!marker.range.isEmpty() || marker.frame != previous.endFrame || marker.startByte != previous.startByte || marker.streamStart != previous.streamStart) return
            val following = pending.getOrNull(1)?.frame ?: Long.MAX_VALUE
            if (mapped.endFrame <= previous.frame || mapped.endFrame > following) return
            val corrected = previous.copy(endFrame = mapped.endFrame, revision = mapped.revision)
            current = corrected
            currentRevised = true
            pending[0] = corrected.copy(frame = corrected.endFrame, range = IntRange.EMPTY)
            if (pending.size == 1 && openStreamCue == null) lastStreamEnd = corrected.endFrame
            return
        }
        val previous = pending[index]
        require(
            previous.segment == mapped.segment &&
                previous.text == mapped.text &&
                previous.range == mapped.range &&
                previous.endByte == mapped.endByte &&
                previous.provenance == mapped.provenance &&
                mapped.revision > previous.revision
        ) { "STREAM_CUE_REVISION" }
        if (lastConsumed > 0 && (previous.frame <= lastConsumed || mapped.frame <= lastConsumed) || previous.provenance == VoiceWordCueProvenance.NATIVE_DURATION) return
        val marker = pending.getOrNull(index + 1) ?: return
        if (!marker.range.isEmpty() || marker.frame != previous.endFrame || marker.startByte != previous.startByte || marker.streamStart != previous.streamStart) return
        val before = if (index == 0) current?.endFrame ?: 0L else pending[index - 1].endFrame
        val after = pending.getOrNull(index + 2)?.frame ?: Long.MAX_VALUE
        // A neighbour may have crossed the watermark since the SDK made its
        // correction. Preserve that committed interval instead of overlapping it.
        if (mapped.frame < before || mapped.endFrame > after) return
        pending[index] = mapped
        pending[index + 1] = mapped.copy(frame = mapped.endFrame, range = IntRange.EMPTY)
        if (index + 2 == pending.size && openStreamCue == null) lastStreamEnd = mapped.endFrame
    }

    @Synchronized fun append(segment: Int, text: String, ranges: List<IntRange>, boundaries: IntArray, frames: Int) {
        require(openStreamCue == null)
        require(boundaries.size == ranges.size * 2)
        require(frames >= 0 && boundaries.all { it in 0..frames } && boundaries.asSequence().zipWithNext().all { (a, b) -> a <= b })
        var word = 0
        while (word < ranges.size) {
            val range = ranges[word]
            val start = boundaries[word * 2]
            // Keep the displayed source highlighted across all words of an expansion.
            do {
                word++
            } while (word < ranges.size && ranges[word] == range)
            val end = boundaries[word * 2 - 1]
            if (!range.isEmpty() && start < end) {
                pending.addLast(VoicePcmCue(produced + start, segment, text, range, produced + end))
                pending.addLast(VoicePcmCue(produced + end, segment, text, IntRange.EMPTY))
            }
        }
        produced += frames
        lastStreamEnd = produced
    }

    @Synchronized fun consumed(frames: Long, advancing: Boolean = true, completedThrough: Long? = null, onWordCrossed: ((VoicePcmCue) -> Unit)? = null): VoicePcmCue? {
        if (frames <= 0) return null
        if (!advancing) {
            if (completedThrough == null) return null
            val active = current?.takeUnless { it.range.isEmpty() } ?: return null
            val end = pending.firstOrNull()?.takeIf {
                it.range.isEmpty() &&
                    it.frame == active.endFrame &&
                    it.frame <= minOf(frames, lastConsumed, completedThrough) &&
                    it.segment == active.segment &&
                    it.text == active.text &&
                    it.provenance == active.provenance
            } ?: return null
            // A zero-output tail can finalize the already-presented word during refill.
            // Clear only that word; neither the clock nor a following word may advance.
            pending.removeFirst()
            current = end
            currentRevised = false
            return end
        }
        lastConsumed = maxOf(lastConsumed, frames)
        var cue: VoicePcmCue? = current.takeIf { currentRevised }
        currentRevised = false
        while (pending.isNotEmpty() && pending.first().frame <= lastConsumed) {
            val next = pending.removeFirst()
            if (!next.range.isEmpty()) onWordCrossed?.invoke(next)
            cue = next
        }
        if (cue != null) current = cue
        return cue
    }

    /** Retain the active cue during a pause/refill so the renderer can freeze the same word. */
    @Synchronized fun active(): VoicePcmCue? = current
}
