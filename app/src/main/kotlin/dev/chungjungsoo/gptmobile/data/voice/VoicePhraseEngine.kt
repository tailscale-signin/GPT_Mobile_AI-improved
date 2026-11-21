package dev.chungjungsoo.gptmobile.data.voice

import dev.melo.kokoro.TimedKokoro
import java.io.File

/** All backends return durations and audio from the same inference operation. */
internal data class VoicePhraseAudio(val samples: FloatArray, val durations: LongArray, val wordTokens: IntArray)

internal interface VoicePhraseEngine : AutoCloseable {
    val executionProvider: String get() = "cpu"
    fun generate(text: String, speaker: Int, speed: Float): VoicePhraseAudio
    fun resetCancellation()
    fun cancel()
}

internal interface VoicePhraseEngineFactory {
    /** Includes model/runtime/configuration revision; changing it invalidates the warm engine. */
    val identity: String
    fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine

    /** Stops qualification before the engine has been published to the playback owner. */
    fun cancelPending() = Unit
}

internal object TimedKokoroPhraseEngineFactory : VoicePhraseEngineFactory {
    override val identity = "kokoro-cpu-ort-1.28.2-v2"
    override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine {
        val engine = TimedKokoro(
            File(directory, KokoroModelPack.MODEL).absolutePath,
            File(directory, KokoroModelPack.VOICES).absolutePath,
            File(directory, KokoroModelPack.TOKENS).absolutePath,
            File(directory, lexicon).absolutePath,
            threads
        )
        return object : VoicePhraseEngine {
            override fun generate(text: String, speaker: Int, speed: Float): VoicePhraseAudio =
                engine.generate(text, speaker, speed).let { VoicePhraseAudio(it.samples, it.durations, it.wordTokens) }
            override fun resetCancellation() = engine.resetCancellation()
            override fun cancel() = engine.cancel()
            override fun close() = engine.close()
        }
    }
}
