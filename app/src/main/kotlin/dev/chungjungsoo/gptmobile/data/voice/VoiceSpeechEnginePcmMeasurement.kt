package dev.chungjungsoo.gptmobile.data.voice

import kotlin.math.abs
import kotlin.math.sqrt

internal data class VoicePcmSignal(val samples: Long, val rms: Double, val peak: Double, val clipped: Long, val nonSilent: Long)

/** Constant-space statistics for observed engine output before the playback queue. */
internal class VoicePcmSignalMeasurement {
    private var count = 0L
    private var sumSquares = 0.0
    private var peak = 0.0
    private var clipped = 0L
    private var nonSilent = 0L

    fun accept(samples: FloatArray) {
        for (sample in samples) {
            val value = sample.toDouble()
            val absolute = abs(value)
            sumSquares += value * value
            peak = maxOf(peak, absolute)
            if (absolute >= 1.0) clipped++
            if (absolute >= 1e-5) nonSilent++
        }
        count += samples.size
    }

    fun snapshot(): VoicePcmSignal? = if (count == 0L) null else VoicePcmSignal(count, sqrt(sumSquares / count), peak, clipped, nonSilent)
}

/** Bounded incremental measurements before playback trimming; no waveform is copied or retained. */
internal class VoiceSpeechEnginePcmMeasurement(
    private val fixture: VoiceSpeechBenchmarkFixture,
    private val repeat: Int,
    private val text: String,
    private val thermalBefore: Int?,
    private val preparedAtNanos: Long? = null,
    private val nanoTime: () -> Long = System::nanoTime
) {
    private val started = nanoTime()
    private var frames = 0L
    private var chunks = 0
    private var calls = 0
    private var first: Long? = null
    private var last: Long? = null
    private var maximumGap: Long? = null
    private var firstFromPreparation: Long? = null
    private val signal = VoicePcmSignalMeasurement()
    private var activeFrames: VoiceStreamFrames? = null
    private val timeline = mutableListOf<VoiceSpeechEnginePcmReceipt>()

    fun beginStream(input: String, incrementalInput: Boolean = false) {
        check(activeFrames == null) { "BENCHMARK_STREAM_ALREADY_OPEN" }
        require(++calls <= VoiceSpeechWorkload.MAX_CHUNKS) { "BENCHMARK_STREAM_LIMIT" }
        activeFrames = if (incrementalInput) {
            VoiceStreamFrames.incremental(VoiceIncrementalTextMap().also { it.append(input) })
        } else {
            VoiceStreamFrames(input)
        }
    }

    fun accept(chunk: VoicePcmChunk) {
        checkNotNull(activeFrames) { "BENCHMARK_STREAM_NOT_OPEN" }.accept(chunk)
        if (chunk.samples.isEmpty()) return
        require(chunks < MAX_PCM_CALLBACKS) { "BENCHMARK_PCM_CALLBACK_LIMIT" }
        val received = nanoTime()
        val elapsed = elapsed(received)
        if (first == null) {
            first = elapsed
            firstFromPreparation = preparedAtNanos?.let { (received - it).coerceAtLeast(0) / 1_000_000 }
        }
        last?.let { maximumGap = maxOf(maximumGap ?: 0, elapsed - it) }
        last = elapsed
        if (timeline.size < MAX_PCM_RECEIPTS) timeline += VoiceSpeechEnginePcmReceipt(chunks, elapsed, chunk.samples.size, calls - 1)
        chunks++
        frames += chunk.samples.size
        signal.accept(chunk.samples)
    }

    fun finishStream() {
        checkNotNull(activeFrames).finish()
        activeFrames = null
    }

    fun snapshot(complete: Boolean, thermalAfter: Int?): VoiceSpeechEngineBenchmarkSample {
        val pcm = signal.snapshot()
        if (complete) check(activeFrames == null && calls > 0 && frames > 0 && (pcm?.nonSilent ?: 0) > 0) { "BENCHMARK_OUTPUT_INVALID" }
        return VoiceSpeechEngineBenchmarkSample(
            fixture, repeat, text.toByteArray(Charsets.UTF_8).size, VoiceSpeechWorkload.digest(text),
            elapsed(nanoTime()), first, last, firstFromPreparation, frames, chunks, calls, maximumGap,
            complete, thermalBefore, thermalAfter, pcm?.rms,
            pcm?.peak, pcm?.clipped ?: 0, pcm?.nonSilent ?: 0, timeline.toList(), (chunks - timeline.size).coerceAtLeast(0)
        )
    }

    private fun elapsed(now: Long) = (now - started).coerceAtLeast(0) / 1_000_000

    companion object {
        const val MAX_PCM_RECEIPTS = 128
        const val MAX_PCM_CALLBACKS = 16_384
    }
}
