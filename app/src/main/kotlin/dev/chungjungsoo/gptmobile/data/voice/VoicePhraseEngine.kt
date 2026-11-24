package dev.chungjungsoo.gptmobile.data.voice

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
    val provenance: VoiceWordCueProvenance = VoiceWordCueProvenance.ESTIMATED
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
    fun generate(text: String, speaker: Int, speed: Float): VoicePhraseAudio = throw UnsupportedOperationException("This engine streams PCM")

    /**
     * Runs on the synthesis owner. emit blocks for bounded playback backpressure;
     * false requires prompt cancellation. Only 24 kHz mono PCM is currently accepted.
     * cancel() may interrupt this call; close() runs only after the owner returns.
     */
    fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean): Unit =
        throw UnsupportedOperationException("This engine generates complete phrases")
    fun resetCancellation()
    fun cancel()
}

internal interface VoicePhraseEngineFactory {
    val selectedBackend: VoiceSpeechBackend get() = VoiceSpeechBackend.CPU
    val preparationTimeoutMs: Long get() = 30_000
    val requiresKokoroAssets: Boolean get() = true

    /** A failed native destructor cannot authorize a replacement owner or model removal. */
    val nativeCleanupPending: Boolean get() = false
    fun isReady(): Boolean = true

    /** Includes model/runtime/configuration revision; changing it invalidates the warm engine. */
    val identity: String
    fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine

    /** Stops qualification before the engine has been published to the playback owner. */
    fun cancelPending() = Unit
}

internal object TimedKokoroPhraseEngineFactory : VoicePhraseEngineFactory {
    override val identity = "kokoro-cpu-${TimedKokoro.RUNTIME_ID}-${KokoroModelPack.MODEL_SHA256}"
    override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine {
        val engine = TimedKokoro(
            File(directory, KokoroModelPack.MODEL).absolutePath,
            File(directory, KokoroModelPack.VOICES).absolutePath,
            File(directory, KokoroModelPack.TOKENS).absolutePath,
            File(directory, lexicon).absolutePath,
            threads
        )
        return object : VoicePhraseEngine {
            override val executionProgress: VoiceSpeechProgress? get() = engine.speechProgress()
            override fun generate(text: String, speaker: Int, speed: Float): VoicePhraseAudio =
                engine.generate(text, speaker, speed).let { VoicePhraseAudio(it.samples, it.durations, it.wordTokens) }
            override fun resetCancellation() = engine.resetCancellation()
            override fun cancel() = engine.cancel()
            override fun close() = engine.close()
        }
    }
}
