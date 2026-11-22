package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CancellationException

/** Retires a failed accelerator before retrying one entirely unplayed phrase. */
internal class VoiceFailoverPhraseEngine(
    initial: VoicePhraseEngine,
    allowFallback: Boolean,
    private val fallback: (IllegalStateException) -> VoicePhraseEngine
) : VoicePhraseEngine {
    private val lifetime = Any()

    @Volatile private var active = initial

    @Volatile private var cancelled = false

    @Volatile private var closed = false
    private var canFallback = allowFallback
    override val executionProvider: String get() = active.executionProvider
    override val executionEvidence: VoiceSpeechExecutionEvidence? get() = active.executionEvidence

    override fun generate(text: String, speaker: Int, speed: Float): VoicePhraseAudio {
        checkActive()
        val audio = try {
            active.generate(text, speaker, speed)
        } catch (error: IllegalStateException) {
            if (error is CancellationException) throw error
            checkActive()
            if (!canFallback) throw error
            canFallback = false
            active.close()
            val replacement = fallback(error)
            val accepted = synchronized(lifetime) {
                if (closed || cancelled) {
                    false
                } else {
                    active = replacement
                    true
                }
            }
            if (!accepted) {
                replacement.cancel()
                replacement.close()
                throw CancellationException("Speech cancelled")
            }
            checkActive()
            replacement.generate(text, speaker, speed)
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
