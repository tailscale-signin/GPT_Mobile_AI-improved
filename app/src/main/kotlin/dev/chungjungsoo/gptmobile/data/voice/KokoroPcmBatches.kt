package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.BlockingQueue

/** Immutable view into a phrase owned by the queue until its last slice has played. */
internal data class VoicePcmSlice(val samples: FloatArray, val offset: Int, val size: Int) {
    init {
        require(offset >= 0 && size > 0 && offset <= samples.size - size)
    }
}

internal enum class VoicePcmReserveExit { TARGET, QUEUE_FULL, NEXT_CHUNK_COVERED, NEXT_CHUNK_LATE, DEADLINE, PRODUCER_IDLE, STOPPED }

/** Bounds queued audio by time as well as count; one native callback may contain seconds of PCM. */
internal object KokoroPcmBatches {
    const val DURATION_MS = 100
    const val QUEUE_BATCHES = 60
    const val QUEUE_LIMIT_MS = DURATION_MS * QUEUE_BATCHES

    /** Wait in place: only the playback worker may consume this bounded queue. */
    fun awaitReserve(
        queue: BlockingQueue<VoicePcmSlice>,
        targetFrames: Long,
        acceptedFrames: () -> Long,
        canWait: () -> Boolean,
        producerPending: () -> Boolean,
        deadlineReached: () -> Boolean,
        nativeReserveExit: () -> VoicePcmReserveExit? = { null },
        poll: () -> Unit
    ): VoicePcmReserveExit {
        while (canWait()) {
            // Short phrase tails can fill every slot before the duration target.
            // Playback must consume a slot before the producer can make progress.
            if (acceptedFrames() >= targetFrames) return VoicePcmReserveExit.TARGET
            if (queue.remainingCapacity() == 0) return VoicePcmReserveExit.QUEUE_FULL
            if (deadlineReached()) return VoicePcmReserveExit.DEADLINE
            nativeReserveExit()?.let { return it }
            if (!producerPending()) return VoicePcmReserveExit.PRODUCER_IDLE
            poll()
        }
        return VoicePcmReserveExit.STOPPED
    }

    /** Once the reserve releases, prime the hardware with the audio already available. */
    fun primeReady(queue: BlockingQueue<VoicePcmSlice>, first: VoicePcmSlice, targetFrames: Int): List<VoicePcmSlice> {
        val batches = mutableListOf(first)
        var frames = first.size
        while (frames < targetFrames) {
            val next = queue.poll() ?: break
            batches += next
            frames += next.size
        }
        return batches
    }

    fun slices(samples: FloatArray, sampleRate: Int, offer: (VoicePcmSlice) -> Boolean): Boolean {
        require(sampleRate in 8_000..48_000)
        val frames = sampleRate * DURATION_MS / 1_000
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(frames, samples.size - offset)
            if (!offer(VoicePcmSlice(samples, offset, count))) return false
            offset += count
        }
        return true
    }

    fun emit(samples: FloatArray, sampleRate: Int, offer: (FloatArray) -> Boolean): Boolean {
        require(sampleRate in 8_000..48_000)
        val frames = sampleRate * DURATION_MS / 1_000
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(samples.size, offset + frames)
            val batch = if (offset == 0 && end == samples.size) samples else samples.copyOfRange(offset, end)
            if (!offer(batch)) return false
            offset = end
        }
        return true
    }
}

/** Caps near-digital silence across callback and utterance boundaries without time-scaling speech. */
internal class VoiceQuietLimiter(sampleRate: Int, maxQuietMs: Int) {
    private val limit = sampleRate * maxQuietMs / 1000
    private var quietFrames = 0

    /** Rewrites sorted frame boundaries; reports contiguous removed spans in this input chunk. */
    fun filter(samples: FloatArray, boundaries: IntArray = IntArray(0), onRemoved: ((Int, Int) -> Unit)? = null): FloatArray {
        var nextBoundary = 0
        var result: FloatArray? = null
        var count = 0
        var removedStart = -1
        for ((index, sample) in samples.withIndex()) {
            while (nextBoundary < boundaries.size && boundaries[nextBoundary] == index) boundaries[nextBoundary++] = count
            if (kotlin.math.abs(sample) <= VoiceSpeechJoinTracker.QUIET) {
                if (quietFrames == limit) {
                    if (result == null) result = samples.copyOf()
                    if (removedStart < 0) removedStart = index
                    continue
                }
                quietFrames++
            } else {
                quietFrames = 0
            }
            if (removedStart >= 0) {
                onRemoved?.invoke(removedStart, index)
                removedStart = -1
            }
            result?.set(count, samples[index])
            count++
        }
        if (removedStart >= 0) onRemoved?.invoke(removedStart, samples.size)
        while (nextBoundary < boundaries.size) {
            require(boundaries[nextBoundary] == samples.size)
            boundaries[nextBoundary++] = count
        }
        return result?.copyOf(count) ?: samples
    }
}
