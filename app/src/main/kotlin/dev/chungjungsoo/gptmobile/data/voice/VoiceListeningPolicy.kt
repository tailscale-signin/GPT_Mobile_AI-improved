package dev.chungjungsoo.gptmobile.data.voice

/** VAD windows are supported by the pinned Moonshine 0.1.5 native options. */
enum class VoiceListeningStyle(val label: String, val vadWindowSeconds: Float, val updateIntervalMillis: Long) {
    QUICK("Quick", .25f, 200),
    NATURAL("Natural", .5f, 350),
    THOUGHTFUL("Thoughtful", .9f, 500)
}

/** A transcription pass must earn back its own processing cost before another pass runs. */
internal class VoiceRecognitionCadence(private val floorMillis: Long) {
    private var pendingSamples = 0
    private var previousPassMillis = 0L

    fun added(samples: Int): Boolean {
        pendingSamples += samples
        val interval = maxOf(floorMillis, previousPassMillis).coerceAtMost(floorMillis * 4)
        if (pendingSamples * 1000L < interval * 16_000L) return false
        pendingSamples = 0
        return true
    }

    fun completed(durationMillis: Long) {
        previousPassMillis = durationMillis.coerceAtLeast(0)
    }
}

/** Reused arrays remain owned by the consumer until it has finished the JNI call. */
internal class VoiceCaptureBufferPool(private val frames: Int, private val capacity: Int = 8) {
    private val free = java.util.concurrent.ArrayBlockingQueue<FloatArray>(capacity)
    fun acquire(): FloatArray = free.poll() ?: FloatArray(frames)
    fun release(samples: FloatArray) {
        if (samples.size == frames) free.offer(samples)
    }
}
