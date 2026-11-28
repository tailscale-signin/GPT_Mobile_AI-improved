package dev.chungjungsoo.gptmobile.data.voice

/**
 * Piecewise input/output coordinates observed from the tempo processor. Absolute anchors avoid
 * per-packet rounding drift. Interpolation is local to an overlap-add operation; it does not turn
 * an estimated word boundary into an acoustically measured one.
 */
internal class VoiceTimelineTransform {
    private data class Anchor(val source: Long, val output: Long)
    private val anchors = ArrayList<Anchor>().apply { add(Anchor(0, 0)) }
    private var first = 0
    val sourceEnd: Long get() = anchors.last().source
    val outputEnd: Long get() = anchors.last().output
    val sourceStart: Long get() = anchors[first].source

    fun append(source: Long, output: Long) {
        val previous = anchors.last()
        require(source >= previous.source && output >= previous.output) { "TEMPO_TIMELINE_ORDER" }
        if (source == previous.source) {
            require(output == previous.output) { "TEMPO_TIMELINE_DUPLICATE" }
        } else {
            anchors.add(Anchor(source, output))
        }
    }

    /**
     * Sonic can drain a final overlap after consuming the last real source frame. Keep that
     * zero-input tail at the terminal boundary rather than moving earlier interpolation anchors.
     */
    fun finish(source: Long, output: Long) {
        require(source >= sourceEnd && output >= outputEnd) { "TEMPO_TIMELINE_TAIL" }
        if (source == sourceEnd && output > outputEnd) anchors.add(Anchor(source, output)) else append(source, output)
    }

    fun map(source: Long): Long? {
        if (source < sourceStart || source > sourceEnd) return null
        var low = first
        var high = anchors.lastIndex
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (anchors[middle].source <= source) low = middle else high = middle - 1
        }
        val before = anchors[low]
        if (before.source == source) return before.output
        val after = anchors[low + 1]
        val numerator = (source - before.source) * (after.output - before.output)
        val denominator = after.source - before.source
        return before.output + (numerator + denominator / 2) / denominator
    }

    /** Keep one bracketing point before the revision window; retain no PCM. */
    fun discardBefore(source: Long) {
        while (first + 1 < anchors.size && anchors[first + 1].source <= source) first++
        if (first >= 4_096) {
            anchors.subList(0, first).clear()
            first = 0
        }
    }
}
