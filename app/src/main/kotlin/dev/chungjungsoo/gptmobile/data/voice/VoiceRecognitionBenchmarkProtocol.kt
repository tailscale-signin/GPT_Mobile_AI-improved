package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

internal enum class VoiceRecognitionBenchmarkInputMode {
    REALTIME_PACED,
    AS_FAST_AS_POSSIBLE
}

/**
 * Fixed-file recognition has independent preparation, input and decoder clocks. Pacing admits a
 * chunk only after its last sample would have been captured. A slow decoder keeps its lateness;
 * it cannot move the source clock forward or hide the delay by starting another sleep interval.
 */
internal class VoiceRecognitionBenchmarkProtocol(
    private val audioFrames: Long,
    private val sampleRate: Int = 16_000,
    private val tailSilenceFrames: Long = sampleRate.toLong(),
    private val mode: VoiceRecognitionBenchmarkInputMode = VoiceRecognitionBenchmarkInputMode.REALTIME_PACED,
    private val nowNanos: () -> Long = System::nanoTime,
    private val pauseNanos: (Long) -> Unit = { TimeUnit.NANOSECONDS.sleep(it) }
) {
    private val createdAt = nowNanos()
    private var lastObservedAt = createdAt
    private var preparedAt: Long? = null
    private var finishedAt: Long? = null
    private var firstPartialAt: Long? = null
    private var firstEndpointAt: Long? = null
    private var firstFinalAt: Long? = null
    private var lastFinalAt: Long? = null
    private var speechEndFrame: Long? = null
    private var acceptedFrames = 0L
    private var decodeNanos = 0L
    private var flushNanos = 0L
    private var maxFeedLatenessNanos = 0L
    private var finalCallbacks = 0
    private var timedUnits = 0
    private var wordDurationUnits = 0
    private var rejectedTiming = false

    init {
        require(sampleRate in 1..384_000)
        require(audioFrames in 1..sampleRate.toLong() * 3_600)
        require(tailSilenceFrames in 0..sampleRate.toLong() * 10)
    }

    val measurementScope: String get() = when (mode) {
        VoiceRecognitionBenchmarkInputMode.REALTIME_PACED -> "REALTIME_PACED_SYNTHETIC_FIXTURE_NOT_LIVE_MICROPHONE_OR_ACOUSTIC_QUALIFICATION"
        VoiceRecognitionBenchmarkInputMode.AS_FAST_AS_POSSIBLE -> "UNPACED_SYNTHETIC_FIXTURE_THROUGHPUT_NOT_REALTIME_OR_ACOUSTIC_QUALIFICATION"
    }

    val hasAcceptedInput: Boolean get() = acceptedFrames > 0
    val acceptedInputFrames: Long get() = acceptedFrames

    fun prepared() {
        check(preparedAt == null && finishedAt == null)
        preparedAt = now()
    }

    fun feed(frames: Int, active: () -> Boolean, decode: () -> Unit) {
        check(finishedAt == null)
        val start = checkNotNull(preparedAt)
        require(frames > 0 && frames <= audioFrames + tailSilenceFrames - acceptedFrames)
        val next = acceptedFrames + frames
        val due = frameNanos(next)
        checkActive(active)
        if (mode == VoiceRecognitionBenchmarkInputMode.REALTIME_PACED) {
            while (true) {
                checkActive(active)
                val remaining = due - (now() - start)
                if (remaining <= 0) break
                try {
                    pauseNanos(minOf(remaining, MAX_PAUSE_NANOS))
                } catch (failure: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw CancellationException("Recognition benchmark interrupted").apply { initCause(failure) }
                }
            }
        }
        checkActive(active)
        val before = now()
        if (mode == VoiceRecognitionBenchmarkInputMode.REALTIME_PACED) {
            maxFeedLatenessNanos = maxOf(maxFeedLatenessNanos, before - start - due)
        }
        // Decoder callbacks during this invocation may refer to the batch being accepted.
        acceptedFrames = next
        try {
            decode()
        } finally {
            decodeNanos += now() - before
        }
    }

    fun partial(text: String) {
        if (preparedAt == null || finishedAt != null || !hasAcceptedInput || text.isBlank() || firstPartialAt != null) return
        firstPartialAt = now()
    }

    fun final(text: String, transcript: VoiceRecognitionTranscript? = null) {
        if (preparedAt == null || finishedAt != null || !hasAcceptedInput || text.isBlank()) return
        val observed = now()
        if (firstFinalAt == null) firstFinalAt = observed
        lastFinalAt = observed
        finalCallbacks++
        timedUnits = transcript?.timings?.size ?: 0
        wordDurationUnits = transcript?.timings?.count {
            it.provenance == VoiceRecognitionTimingProvenance.WORD_ALIGNMENT && it.endFrame != null
        } ?: 0
        rejectedTiming = transcript?.timingIssue != null
    }

    /** A proposed endpoint stops capture; only a separate terminal callback qualifies final text. */
    fun endpoint(text: String) {
        if (preparedAt == null || finishedAt != null || !hasAcceptedInput || text.isBlank() || firstEndpointAt != null) return
        firstEndpointAt = now()
    }

    /** The decoder reports an audio age, not a wall-clock age; do not subtract it from callback time. */
    fun speechEnded(elapsedAudioAgoMs: Long) {
        if (preparedAt == null || finishedAt != null || !hasAcceptedInput || elapsedAudioAgoMs < 0 || elapsedAudioAgoMs > 3_610_000) return
        val ended = acceptedFrames - elapsedAudioAgoMs * sampleRate / 1_000
        if (ended in 0..audioFrames) speechEndFrame = ended
    }

    fun finish(active: () -> Boolean, flush: () -> Unit) {
        check(preparedAt != null && finishedAt == null && acceptedFrames == audioFrames + tailSilenceFrames)
        checkActive(active)
        val before = now()
        flush()
        val after = now()
        flushNanos = after - before
        decodeNanos += flushNanos
        finishedAt = after
    }

    fun metrics(): Map<String, Double> {
        val start = checkNotNull(preparedAt)
        val end = checkNotNull(finishedAt)
        return buildMap {
            put("recognitionProtocolVersion", VERSION.toDouble())
            put("realtimePacedInput", if (mode == VoiceRecognitionBenchmarkInputMode.REALTIME_PACED) 1.0 else 0.0)
            put("inputSampleRate", sampleRate.toDouble())
            put("inputAudioFrames", audioFrames.toDouble())
            put("tailSilenceFrames", tailSilenceFrames.toDouble())
            put("audioMs", frameNanos(audioFrames) / 1_000_000.0)
            put("prepareMs", (start - createdAt) / 1_000_000.0)
            put("inputAndFlushMs", (end - start) / 1_000_000.0)
            put("flushMs", flushNanos / 1_000_000.0)
            put("decodeBusyMs", decodeNanos / 1_000_000.0)
            put("decodeRealTimeFactor", decodeNanos.toDouble() / frameNanos(audioFrames))
            put("finalCallbacks", finalCallbacks.toDouble())
            put("timedRecognitionUnits", timedUnits.toDouble())
            put("wordDurationUnits", wordDurationUnits.toDouble())
            put("recognitionTimingRejected", if (rejectedTiming) 1.0 else 0.0)
            firstPartialAt?.let { put("firstPartialMs", (it - start) / 1_000_000.0) }
            firstEndpointAt?.let { put("firstEndpointMs", (it - start) / 1_000_000.0) }
            firstFinalAt?.let { put("firstFinalMs", (it - start) / 1_000_000.0) }
            lastFinalAt?.let { final ->
                put("finalMs", (final - start) / 1_000_000.0)
                if (mode == VoiceRecognitionBenchmarkInputMode.REALTIME_PACED) {
                    // A negative value honestly identifies a final produced before the fixture ended.
                    put("finalAfterInputMs", (final - start - frameNanos(audioFrames)) / 1_000_000.0)
                    speechEndFrame?.let { speechEnd -> put("finalAfterDecoderSpeechEndMs", (final - start - frameNanos(speechEnd)) / 1_000_000.0) }
                }
            }
            speechEndFrame?.let { put("decoderSpeechEndFrame", it.toDouble()) }
            if (mode == VoiceRecognitionBenchmarkInputMode.REALTIME_PACED) put("maxFeedLatenessMs", maxFeedLatenessNanos / 1_000_000.0)
        }
    }

    private fun now(): Long = nowNanos().also {
        check(it - lastObservedAt >= 0) { "Recognition benchmark clock moved backwards" }
        lastObservedAt = it
    }

    private fun frameNanos(frames: Long): Long = frames / sampleRate * 1_000_000_000 + frames % sampleRate * 1_000_000_000 / sampleRate

    private fun checkActive(active: () -> Boolean) {
        if (!active()) throw CancellationException("Recognition benchmark stopped")
    }

    companion object {
        const val VERSION = 2
        private const val MAX_PAUSE_NANOS = 10_000_000L
    }
}
