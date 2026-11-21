package dev.chungjungsoo.gptmobile.data.voice

internal typealias SpokenRangeObserver = (Int, String, IntRange) -> Unit

internal data class VoicePcmCue(val frame: Long, val segment: Int, val text: String, val range: IntRange)

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

    @Synchronized fun append(segment: Int, text: String, ranges: List<IntRange>, boundaries: IntArray, frames: Int) {
        require(boundaries.size == ranges.size * 2)
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
                pending.addLast(VoicePcmCue(produced + start, segment, text, range))
                pending.addLast(VoicePcmCue(produced + end, segment, text, IntRange.EMPTY))
            }
        }
        produced += frames
    }

    @Synchronized fun consumed(frames: Long): VoicePcmCue? {
        if (frames <= 0) return null
        var cue: VoicePcmCue? = null
        while (pending.isNotEmpty() && pending.first().frame <= frames) cue = pending.removeFirst()
        return cue
    }
}
