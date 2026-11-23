package dev.chungjungsoo.gptmobile.data.voice

internal typealias SpokenRangeObserver = (Int, String, IntRange) -> Unit

internal data class VoicePcmCue(val frame: Long, val segment: Int, val text: String, val range: IntRange, val endFrame: Long = frame, val provenance: VoiceWordCueProvenance = VoiceWordCueProvenance.NATIVE_DURATION)

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

/** The synthesis worker registers a whole phrase before the output worker can consume its PCM. */
internal class VoicePcmCaptions {
    private val pending = ArrayDeque<VoicePcmCue>()
    private var produced = 0L
    private var current: VoicePcmCue? = null
    private var lastConsumed = 0L

    @Synchronized fun streamStart(): Long = produced

    /** Register cue metadata before accepting each PCM chunk, without buffering an entire utterance. */
    @Synchronized fun appendStream(segment: Int, text: String, streamStart: Long, cues: List<VoiceStreamCue>, frames: Int) {
        require(streamStart in 0..produced && frames >= 0)
        for (cue in cues) {
            require(cue.range.isEmpty() || cue.range.first >= 0 && cue.range.last < text.length)
            if (!cue.range.isEmpty()) {
                pending.addLast(VoicePcmCue(streamStart + cue.startFrame, segment, text, cue.range, streamStart + cue.endFrame, cue.provenance))
                pending.addLast(VoicePcmCue(streamStart + cue.endFrame, segment, text, IntRange.EMPTY, provenance = cue.provenance))
            }
        }
        produced += frames
    }

    @Synchronized fun append(segment: Int, text: String, ranges: List<IntRange>, boundaries: IntArray, frames: Int) {
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
    }

    @Synchronized fun consumed(frames: Long, advancing: Boolean = true): VoicePcmCue? {
        if (frames <= 0 || !advancing) return null
        lastConsumed = maxOf(lastConsumed, frames)
        var cue: VoicePcmCue? = null
        while (pending.isNotEmpty() && pending.first().frame <= lastConsumed) cue = pending.removeFirst()
        if (cue != null) current = cue
        return cue
    }

    /** Retain the active cue during a pause/refill so the renderer can freeze the same word. */
    @Synchronized fun active(): VoicePcmCue? = current
}
