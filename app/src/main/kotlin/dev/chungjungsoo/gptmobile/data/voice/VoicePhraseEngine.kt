package dev.chungjungsoo.gptmobile.data.voice

import dev.melo.kokoro.KokoroFrontend
import dev.melo.kokoro.TimedKokoro
import java.io.File

/** All backends return durations and audio from the same inference operation. */
internal data class VoicePhraseAudio(val samples: FloatArray, val durations: LongArray, val wordTokens: IntArray)

internal const val VOICE_PCM_SAMPLE_RATE = 24_000

/** Timing provenance is independent of the measured audio presentation clock. */
enum class VoiceWordCueProvenance { NATIVE_DURATION, ESTIMATED }

/** UTF-8 byte offsets are end-exclusive in the exact stream input; frames are relative to that call. */
internal data class VoiceWordCue(
    val startByte: Int,
    val endByte: Int,
    val startFrame: Long,
    val endFrame: Long,
    val provenance: VoiceWordCueProvenance = VoiceWordCueProvenance.ESTIMATED,
    val finalEnd: Boolean = true
)

/** Mono float PCM. The producer must not mutate samples after handing them to the consumer. */
internal data class VoicePcmChunk(
    val samples: FloatArray,
    val sampleRate: Int = VOICE_PCM_SAMPLE_RATE,
    val wordCues: List<VoiceWordCue> = emptyList()
)

/** Safe to poll while native work is running; elapsed times belong to this attempt and phase. */
internal data class VoiceSpeechProgress(
    val stage: VoiceSpeechStage,
    val attemptId: Long,
    val stageSequence: Long,
    val stageElapsedMs: Long,
    val attemptElapsedMs: Long,
    val cancellationRequested: Boolean,
    val active: Boolean = true
)

internal fun TimedKokoro.speechProgress(): VoiceSpeechProgress? = progress()?.let { current ->
    val stage = VoiceSpeechStage.entries.firstOrNull { it.name.equals(current.stage, ignoreCase = true) } ?: return@let null
    VoiceSpeechProgress(stage, current.attemptId, current.stageSequence, current.stageElapsedMs, current.attemptElapsedMs, current.cancellationRequested, current.active)
}

internal interface VoicePhraseEngine : AutoCloseable {
    val executionProvider: String get() = "cpu"
    val executionEvidence: VoiceSpeechExecutionEvidence? get() = null
    val executionProgress: VoiceSpeechProgress? get() = null
    val supportsStreaming: Boolean get() = false
    val supportsIncrementalInput: Boolean get() = false
    fun generate(text: String, speaker: Int, speed: Float): VoicePhraseAudio = throw UnsupportedOperationException("This engine streams PCM")
    fun preparePhrase(text: String): KokoroFrontend.EncodedPhrase? = null
    fun generatePrepared(text: String, encoded: KokoroFrontend.EncodedPhrase?, speaker: Int, speed: Float): VoicePhraseAudio = generate(text, speaker, speed)

    /**
     * Runs on the synthesis owner. emit blocks for bounded playback backpressure;
     * false requires prompt cancellation. Only 24 kHz mono PCM is currently accepted.
     * cancel() may interrupt this call; close() runs only after the owner returns.
     */
    fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean): Unit =
        throw UnsupportedOperationException("This engine generates complete phrases")

    /**
     * One reply owns one stream. nextText runs on a dedicated input worker and returns
     * stable, ordered text or null exactly at input completion. It must respond to
     * interruption. emit remains on the synthesis owner with playback backpressure.
     * Cancellation retires both workers before releasing a stream or model lease.
     */
    fun streamInput(nextText: () -> String?, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean): Unit =
        throw UnsupportedOperationException("This engine accepts complete phrase input")

    /** Selected only while the previous synthesis owner has retired. */
    fun selectVoice(voiceId: String): Unit = throw UnsupportedOperationException("This engine uses numeric speakers")

    /** Optional CPU-only inference of a fixed public phrase. Discard PCM and keep reply evidence untouched. */
    fun warmFirstInference(speaker: Int, speed: Float): Boolean = false
    fun resetCancellation()
    fun cancel()
}

internal interface VoicePhraseEngineFactory {
    val selectedBackend: VoiceSpeechBackend get() = VoiceSpeechBackend.CPU

    /** Thread calibration must use the same model artifact as the selected native engine. */
    val cpuCalibrationFactory: VoicePhraseEngineFactory get() = TimedKokoroPhraseEngineFactory
    val preparationTimeoutMs: Long get() = 30_000
    val requiresKokoroAssets: Boolean get() = true

    /** A failed native destructor cannot authorize a replacement owner or model removal. */
    val nativeCleanupPending: Boolean get() = false
    fun isReady(): Boolean = true

    /** Includes model/runtime/configuration revision; changing it invalidates the warm engine. */
    val identity: String

    /** Model residency may outlive a per-utterance voice selection. */
    val residentIdentity: String get() = identity
    fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine
    fun prepareForPlayback(engine: VoicePhraseEngine) = Unit

    /** Stops qualification before the engine has been published to the playback owner. */
    fun cancelPending() = Unit
}

internal object TimedKokoroPhraseEngineFactory : VoicePhraseEngineFactory {
    override val identity = "kokoro-cpu-${TimedKokoro.RUNTIME_ID}-${KokoroModelPack.MODEL_SHA256}"
    override val cpuCalibrationFactory: VoicePhraseEngineFactory get() = this
    private val original by lazy {
        object : VoicePhraseEngineFactory {
            override val identity = "kokoro-cpu-${TimedKokoro.RUNTIME_ID}-${KokoroModelVariant.ORIGINAL_FP32.modelSha256}"
            override val cpuCalibrationFactory: VoicePhraseEngineFactory get() = this
            override fun create(directory: File, lexicon: String, threads: Int) = createVariant(directory, lexicon, threads, KokoroModelVariant.ORIGINAL_FP32)
        }
    }

    fun forVariant(variant: KokoroModelVariant): VoicePhraseEngineFactory = if (variant == KokoroModelVariant.INT8) this else original

    override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine = createVariant(directory, lexicon, threads, KokoroModelVariant.INT8)

    private fun createVariant(directory: File, lexicon: String, threads: Int, variant: KokoroModelVariant): VoicePhraseEngine {
        val engine = TimedKokoro(
            File(directory, variant.modelFile).absolutePath,
            File(directory, KokoroModelPack.VOICES).absolutePath,
            File(directory, KokoroModelPack.TOKENS).absolutePath,
            File(directory, lexicon).absolutePath,
            threads
        )
        return object : VoicePhraseEngine {
            override val executionProgress: VoiceSpeechProgress? get() = engine.speechProgress()
            override fun generate(text: String, speaker: Int, speed: Float): VoicePhraseAudio =
                engine.generate(text, speaker, speed).let { VoicePhraseAudio(it.samples, it.durations, it.wordTokens) }
            override fun preparePhrase(text: String): KokoroFrontend.EncodedPhrase = engine.encode(text)
            override fun generatePrepared(text: String, encoded: KokoroFrontend.EncodedPhrase?, speaker: Int, speed: Float): VoicePhraseAudio {
                val audio = if (encoded == null) engine.generate(text, speaker, speed) else engine.generate(encoded, speaker, speed)
                return VoicePhraseAudio(audio.samples, audio.durations, audio.wordTokens)
            }
            override fun warmFirstInference(speaker: Int, speed: Float): Boolean = engine.warmCpuInference(speaker, speed)
            override fun resetCancellation() = engine.resetCancellation()
            override fun cancel() = engine.cancel()
            override fun close() = engine.close()
        }
    }
}

internal fun TimedKokoro.warmCpuInference(speaker: Int, speed: Float): Boolean {
    // generate validates native PCM and durations. None of these samples enter the
    // playback queue, captions, benchmark observations or conversational diagnostics.
    generate(VoiceSilentWarmup.PHRASE, speaker, speed)
    return true
}
