package dev.chungjungsoo.gptmobile.data.voice

import android.os.Build
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.sqrt

internal interface PocketCompiledSession : AutoCloseable {
    val tokenCount: Int
    fun receipt(): String
    fun resetMeasurements()
    fun synthesize(text: String, voice: String, emit: (FloatArray) -> Unit)
}

/** Serialized ownership keeps native buffers alive until the current invocation has returned. */
internal class PocketCompiledVoicePhraseEngine(
    private val session: PocketCompiledSession,
    private val cancelled: AtomicBoolean,
    private val backend: VoiceSpeechBackend,
    private val speakingRate: Float,
    private var voice: String,
    private val prepareMs: Long,
    private val deviceKey: String = Build.SOC_MODEL.orEmpty()
) : VoicePhraseEngine {
    private val operationLock = Any()
    private val attempts = AtomicLong()
    private var closed = false

    @Volatile private var active = false

    @Volatile private var started = 0L

    @Volatile private var evidence: VoiceSpeechExecutionEvidence? = null

    override val supportsStreaming = true
    override val supportsIncrementalInput = false
    override val executionProvider = if (backend == VoiceSpeechBackend.POCKET_GPU) "litert-compiled-pocket-gpu-requested" else "litert-compiled-pocket-npu-requested"
    override val executionEvidence: VoiceSpeechExecutionEvidence? get() = evidence
    override val executionProgress: VoiceSpeechProgress?
        get() = started.takeIf { it != 0L }?.let {
            val elapsed = (System.nanoTime() - it).coerceAtLeast(0) / 1_000_000
            VoiceSpeechProgress(VoiceSpeechStage.SYNTHESIS, attempts.get(), 1, elapsed, elapsed, cancelled.get(), active)
        }

    init {
        require(backend.isCompiledPocket && speakingRate.isFinite() && speakingRate in .5f..2f)
        require(voice in PocketCompiledSpeechCatalog.VOICES)
    }

    override fun selectVoice(voiceId: String) = synchronized(operationLock) {
        check(!closed)
        require(voiceId in PocketCompiledSpeechCatalog.VOICES) { "POCKET_VOICE_UNAVAILABLE" }
        voice = voiceId
    }

    override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) = synchronized(operationLock) {
        check(!closed)
        require(speed == speakingRate) { "POCKET_SPEED_CONFIGURATION" }
        require(text.isNotBlank()) { "POCKET_TEXT_EMPTY" }
        checkActive()
        started = System.nanoTime()
        attempts.incrementAndGet()
        active = true
        session.resetMeasurements()
        val tempo = VoiceTempoProcessor(speed)
        var frames = 0L
        var sumSquares = 0.0
        var peak = 0.0
        var nonSilent = 0L
        var clipped = 0L
        var finite = true
        fun receipt(stage: VoiceSpeechStage, failure: VoiceSpeechFailureCode? = null) = VoiceSpeechExecutionEvidence(
            selectedBackend = backend,
            effectiveProvider = executionProvider,
            modelSha256 = PocketCompiledSpeechCatalog.MODEL_SHA256,
            runtimeId = PocketCompiledSpeechCatalog.RUNTIME_ID,
            deviceKey = deviceKey,
            stage = stage,
            failure = failure,
            prepareMs = prepareMs,
            synthesisMs = (System.nanoTime() - started).coerceAtLeast(0) / 1_000_000,
            tokenCount = session.tokenCount,
            verifiedAudio = stage == VoiceSpeechStage.READY && nonSilent > 0,
            observedAtMillis = System.currentTimeMillis(),
            profileAvailable = false,
            discoverySummary = session.receipt(),
            audioMeasurementAvailable = frames > 0,
            audioFinite = finite,
            audioRms = if (frames > 0 && finite) sqrt(sumSquares / frames) else null,
            audioPeak = peak,
            clippedSamples = clipped,
            nonSilentSamples = nonSilent,
            outputFrames = frames
        )
        fun forward(chunk: VoicePcmChunk) {
            checkActive()
            if (!emit(chunk)) {
                cancel()
                throw CancellationException("Pocket speech cancelled")
            }
        }
        try {
            evidence = receipt(VoiceSpeechStage.SYNTHESIS)
            session.synthesize(text, voice) { samples ->
                checkActive()
                require(frames + samples.size <= VoiceTempoProcessor.inputFrameLimit(speed, VoiceIncrementalStreamLimits.MAX_FRAMES)) { "POCKET_STREAM_FRAME_LIMIT" }
                for (sample in samples) {
                    finite = finite && sample.isFinite()
                    if (!sample.isFinite()) continue
                    val magnitude = abs(sample)
                    if (magnitude >= 1f) clipped++
                    if (magnitude >= 1e-5f) nonSilent++
                    sumSquares += sample.toDouble() * sample
                    peak = maxOf(peak, magnitude.toDouble())
                }
                frames += samples.size
                require(finite) { "POCKET_PCM_NONFINITE" }
                require(clipped == 0L) { "POCKET_PCM_CLIPPED" }
                var offset = 0
                while (offset < samples.size) {
                    checkActive()
                    val end = minOf(offset + VOICE_PCM_SAMPLE_RATE, samples.size)
                    tempo.accept(VoicePcmChunk(samples.copyOfRange(offset, end)), ::forward)
                    offset = end
                }
                evidence = receipt(VoiceSpeechStage.SYNTHESIS)
            }
            checkActive()
            check(nonSilent > 0) { "POCKET_PCM_SILENT" }
            tempo.finish(::forward)
            evidence = receipt(VoiceSpeechStage.READY)
        } catch (error: Throwable) {
            val failure = when {
                error is CancellationException -> null
                error.message?.startsWith("POCKET_PCM_") == true || error.message == "POCKET_EOS_LIMIT" -> VoiceSpeechFailureCode.OUTPUT_INVALID
                else -> VoiceSpeechFailureCode.EXECUTION_FAILED
            }
            evidence = receipt(VoiceSpeechStage.SYNTHESIS, failure)
            throw error
        } finally {
            active = false
        }
    }

    override fun resetCancellation() = synchronized(operationLock) {
        check(!closed)
        cancelled.set(false)
    }

    override fun cancel() {
        // LiteRT exposes no interrupt for CompiledModel.run. Check this immediately after that
        // invocation and before every PCM delivery; never release in-use native buffers here.
        cancelled.set(true)
    }

    override fun close() {
        cancel()
        synchronized(operationLock) {
            if (closed) return
            closed = true
            session.close()
        }
    }

    private fun checkActive() {
        if (cancelled.get()) throw CancellationException("Pocket speech cancelled")
    }
}
