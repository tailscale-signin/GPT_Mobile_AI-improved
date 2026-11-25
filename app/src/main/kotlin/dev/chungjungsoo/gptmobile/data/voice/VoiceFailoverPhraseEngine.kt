package dev.chungjungsoo.gptmobile.data.voice

import dev.melo.kokoro.KokoroFrontend
import java.util.concurrent.CancellationException

/** Retires a failed accelerator before retrying one entirely unplayed phrase. */
internal class VoiceFailoverPhraseEngine(
    initial: VoicePhraseEngine,
    allowFallback: Boolean,
    private val canRecover: (IllegalStateException) -> Boolean = { true },
    private val fallback: (IllegalStateException) -> VoicePhraseEngine
) : VoicePhraseEngine {
    private val lifetime = Any()

    @Volatile private var active = initial

    @Volatile private var cancelled = false

    @Volatile private var closed = false

    @Volatile private var retired = false

    /** A failed or cancelled replacement must be rebuilt by the next playback owner. */
    val canReuse: Boolean get() = !closed && !retired

    private var canFallback = allowFallback
    override val executionProvider: String get() = active.executionProvider
    override val executionEvidence: VoiceSpeechExecutionEvidence? get() = active.executionEvidence
    override val executionProgress: VoiceSpeechProgress? get() = if (closed) null else active.executionProgress

    override fun warmFirstInference(speaker: Int, speed: Float): Boolean {
        checkActive()
        val warmed = active.warmFirstInference(speaker, speed)
        checkActive()
        return warmed
    }

    override fun preparePhrase(text: String): KokoroFrontend.EncodedPhrase? {
        checkActive()
        return active.preparePhrase(text)
    }

    override fun generate(text: String, speaker: Int, speed: Float): VoicePhraseAudio = generatePrepared(text, null, speaker, speed)

    override fun generatePrepared(text: String, encoded: KokoroFrontend.EncodedPhrase?, speaker: Int, speed: Float): VoicePhraseAudio {
        checkActive()
        val audio = try {
            active.generatePrepared(text, encoded, speaker, speed)
        } catch (error: IllegalStateException) {
            if (error is CancellationException) throw error
            checkActive()
            if (!canFallback || !canRecover(error)) throw error
            canFallback = false
            retired = true
            active.close()
            val replacement = fallback(error)
            val accepted = synchronized(lifetime) {
                if (closed || cancelled) {
                    false
                } else {
                    active = replacement
                    retired = false
                    true
                }
            }
            if (!accepted) {
                replacement.cancel()
                replacement.close()
                throw CancellationException("Speech cancelled")
            }
            checkActive()
            replacement.generatePrepared(text, encoded, speaker, speed)
        }
        checkActive()
        return audio
    }

    private fun checkActive() {
        if (closed || cancelled) throw CancellationException("Speech cancelled")
    }

    override fun resetCancellation() = synchronized(lifetime) {
        check(!closed)
        active.resetCancellation()
        cancelled = false
    }

    override fun cancel() {
        val engine = synchronized(lifetime) {
            cancelled = true
            active
        }
        engine.cancel()
    }

    override fun close() {
        val engine = synchronized(lifetime) {
            cancelled = true
            if (closed) {
                null
            } else {
                closed = true
                active
            }
        }
        engine?.close()
    }
}
