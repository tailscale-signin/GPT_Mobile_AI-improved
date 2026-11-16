package dev.chungjungsoo.gptmobile.data.voice

/** Bounds queued audio by time as well as count; one native callback may contain seconds of PCM. */
internal object KokoroPcmBatches {
    const val DURATION_MS = 100
    const val QUEUE_BATCHES = 60
    const val QUEUE_LIMIT_MS = DURATION_MS * QUEUE_BATCHES

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

    fun filter(samples: FloatArray): FloatArray {
        var result: FloatArray? = null
        var count = 0
        for ((index, sample) in samples.withIndex()) {
            if (kotlin.math.abs(sample) <= 0.0003f) {
                if (quietFrames == limit) {
                    if (result == null) result = samples.copyOf()
                    continue
                }
                quietFrames++
            } else {
                quietFrames = 0
            }
            result?.set(count, samples[index])
            count++
        }
        return result?.copyOf(count) ?: samples
    }
}
