package dev.chungjungsoo.gptmobile.data.voice

internal enum class VoicePauseDuration(val label: String, val maxQuietMs: Int?) {
    NATURAL("Natural", null),
    SHORT("Short", 140),
    MINIMAL("Minimal", 70)
}

internal enum class VoicePerformanceMode(val label: String, val followUpWords: Int, val bufferMs: Int, val refillWaitMs: Long) {
    QUALITY("Quality", 24, 180, 100),
    BALANCED("Balanced", 16, 100, 60),
    FAST("Fast", 12, 45, 0)
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
