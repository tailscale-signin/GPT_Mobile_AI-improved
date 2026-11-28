package dev.chungjungsoo.gptmobile.data.voice

/**
 * An SDK packet may carry timing for audio returned by an earlier read. Hold that audio until
 * its supplied timing is available, so the presentation clock cannot pass it first. The bound
 * limits missing-timing retention; overflow and EOS preserve PCM without inventing word times.
 * This owner is confined to one synthesis call and never retains the SDK's mutable arrays.
 */
internal class MediaPipeCaptionBuffer(private val capacityFrames: Int = MAX_BUFFERED_FRAMES) : AutoCloseable {
    init {
        require(capacityFrames in 1..MAX_BUFFERED_FRAMES)
    }

    private val samples = FloatArray(capacityFrames)
    private var start = 0
    private var ended = false
    var bufferedFrames = 0
        private set
    var forwardedFrames = 0L
        private set

    fun accept(incoming: FloatArray, timestampedThroughFrame: Long, emit: (FloatArray) -> Unit) {
        check(!ended)
        require(timestampedThroughFrame >= 0)
        fun drainTimed() {
            val available = (timestampedThroughFrame - forwardedFrames).coerceIn(0, bufferedFrames.toLong()).toInt()
            drain(available, emit)
        }
        drainTimed()
        var offset = 0
        while (offset < incoming.size) {
            if (bufferedFrames == capacityFrames) drain(minOf(OUTPUT_PACKET_FRAMES, bufferedFrames), emit)
            val end = (start + bufferedFrames) % capacityFrames
            val count = minOf(incoming.size - offset, capacityFrames - bufferedFrames, capacityFrames - end)
            incoming.copyInto(samples, end, offset, offset + count)
            bufferedFrames += count
            offset += count
            drainTimed()
        }
    }

    fun finish(emit: (FloatArray) -> Unit) {
        check(!ended)
        drain(bufferedFrames, emit)
        ended = true
    }

    private fun drain(count: Int, emit: (FloatArray) -> Unit) {
        var remaining = count
        while (remaining > 0) {
            val size = minOf(remaining, OUTPUT_PACKET_FRAMES)
            val owned = FloatArray(size)
            val first = minOf(size, capacityFrames - start)
            samples.copyInto(owned, 0, start, start + first)
            samples.fill(0f, start, start + first)
            if (first < size) {
                samples.copyInto(owned, first, 0, size - first)
                samples.fill(0f, 0, size - first)
            }
            start = (start + size) % capacityFrames
            bufferedFrames -= size
            forwardedFrames += size
            remaining -= size
            emit(owned)
        }
    }

    /** Cancellation discards pending audio; it must never flush a stopped reply. */
    override fun close() {
        samples.fill(0f)
        bufferedFrames = 0
        ended = true
    }

    companion object {
        // Same eight-second PCM bound as the independent compiled Pocket caption aligner.
        const val MAX_BUFFERED_FRAMES = VOICE_PCM_SAMPLE_RATE * 8
        private const val OUTPUT_PACKET_FRAMES = VOICE_PCM_SAMPLE_RATE
    }
}
