package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CancellationException

/** A native stream operation failed. Input, callbacks, and cancellation keep their own failures. */
internal class MediaPipeDelegateFailure(cause: Throwable) : RuntimeException(cause)

/** Live playback only. Each failed accelerator can be replaced by the CPU-safe pack once. */
internal class MediaPipePlaybackRecovery(
    initial: MediaPipeVoicePhraseEngine,
    private var voice: String,
    private var report: (MediaPipeVoicePhraseEngine) -> Unit,
    private val openCpu: (String) -> MediaPipeVoicePhraseEngine,
    private val cancelPreparation: () -> Unit
) : VoicePhraseEngine {
    private val operations = Any()
    private val state = Any()

    @Volatile private var current: MediaPipeVoicePhraseEngine? = initial
    private var cancelled = false
    private var closed = false
    private var retirementFailed = false
    private var cpuNeeded = false
    private var preparingCpu = false

    override val supportsStreaming = true
    override val supportsIncrementalInput = initial.supportsIncrementalInput
    override val executionProvider: String get() = current?.executionProvider ?: "unavailable"

    override val sdkCancelToReadReturnMs: Long? get() = current?.sdkCancelToReadReturnMs
    override val executionProgress: VoiceSpeechProgress? get() = current?.executionProgress

    override fun warmFirstInference(speaker: Int, speed: Float): Boolean = synchronized(operations) {
        synchronized(state) { checkActive() }
        val engine = if (cpuNeeded) cpu() else checkNotNull(current)
        try {
            engine.warmFirstInference(speaker, speed)
        } catch (failure: MediaPipeDelegateFailure) {
            // A warmup publishes neither audio nor voice evidence, even if the accelerator
            // fails after returning some PCM. Prepare the CPU before the first audible reply.
            retryOnCpu(failure, canReplay = true) { it.warmFirstInference(speaker, speed) }
        }
    }

    fun prepare(voiceId: String, reporter: (MediaPipeVoicePhraseEngine) -> Unit) {
        synchronized(operations) {
            report = reporter
            selectVoice(voiceId)
            current?.let(report)
        }
    }

    override fun selectVoice(voiceId: String) {
        synchronized(operations) {
            current?.selectVoice(voiceId, sameLanguageFallback = true)
            voice = voiceId
        }
    }

    override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
        synthesize(null, emit) { engine, _, sink -> engine.stream(text, speaker, speed, sink) }
    }

    override fun streamInput(nextText: () -> String?, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
        synthesize(nextText, emit) { engine, input, sink -> engine.streamInput(requireNotNull(input), speaker, speed, sink) }
    }

    private fun synthesize(
        source: (() -> String?)?,
        emit: (VoicePcmChunk) -> Boolean,
        run: (MediaPipeVoicePhraseEngine, (() -> String?)?, (VoicePcmChunk) -> Boolean) -> Unit
    ) {
        synchronized(operations) {
            synchronized(state) { checkActive() }
            if (cpuNeeded) {
                run(cpu(), source, emit)
                return
            }
            val prefix = source?.let(::PendingInput)
            var delivered = false
            try {
                run(checkNotNull(current), prefix?.let { it::next }, { chunk ->
                    // Timings alone can already have advanced the presentation timeline.
                    if (!delivered) {
                        delivered = true
                        prefix?.discard()
                    }
                    emit(chunk)
                })
            } catch (failure: MediaPipeDelegateFailure) {
                retryOnCpu(failure, !delivered && prefix?.canReplay != false) { replacement ->
                    run(replacement, prefix?.let { it::replay }, emit)
                }
            }
        }
    }

    private inline fun <T> retryOnCpu(failure: MediaPipeDelegateFailure, canReplay: Boolean, operation: (MediaPipeVoicePhraseEngine) -> T): T {
        synchronized(state) { checkActive() }
        cpuNeeded = true
        val cause = checkNotNull(failure.cause)
        if (!canReplay) throw cause
        try {
            return operation(cpu())
        } catch (cpuFailure: Throwable) {
            if (cpuFailure !== cause) cpuFailure.addSuppressed(cause)
            throw cpuFailure
        }
    }

    /** Called on the synthesis owner after both native stream workers have retired. */
    private fun cpu(): MediaPipeVoicePhraseEngine {
        val old = synchronized(state) {
            checkActive()
            current?.takeIf { it.configuredBackend == VoiceSpeechBackend.LITERT_KOKORO_CPU }?.let { return it }
            current.also { current = null }
        }
        try {
            old?.close()
        } catch (failure: Throwable) {
            synchronized(state) {
                current = old
                retirementFailed = true
            }
            throw failure
        }
        synchronized(state) {
            checkActive()
            preparingCpu = true
        }
        val replacement = try {
            openCpu(voice)
        } finally {
            synchronized(state) { preparingCpu = false }
        }
        try {
            synchronized(state) {
                checkActive()
                current = replacement
            }
        } catch (failure: Throwable) {
            try {
                replacement.close()
            } catch (closeFailure: Throwable) {
                synchronized(state) {
                    current = replacement
                    retirementFailed = true
                }
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
        report(replacement)
        return replacement
    }

    override fun resetCancellation() {
        synchronized(operations) {
            synchronized(state) {
                check(!closed && !retirementFailed) { "Speech engine has been retired." }
                current?.resetCancellation()
                cancelled = false
            }
        }
    }

    override fun cancel() {
        synchronized(state) {
            if (closed || retirementFailed) return
            cancelled = true
            current?.cancel()
            if (preparingCpu) cancelPreparation()
        }
    }

    override fun close() {
        cancel()
        synchronized(operations) {
            val last = synchronized(state) {
                check(!retirementFailed) { "Native speech cleanup is unconfirmed." }
                if (closed) return
                closed = true
                current.also { current = null }
            }
            try {
                last?.close()
            } catch (failure: Throwable) {
                synchronized(state) {
                    current = last
                    retirementFailed = true
                }
                throw failure
            }
        }
    }

    private fun checkActive() {
        check(!closed && !retirementFailed) { "Speech engine has been retired." }
        if (cancelled || Thread.currentThread().isInterrupted) throw CancellationException("Speech playback cancelled.")
    }

    /** Only retains the already-consumed prefix until the first callback can reach playback. */
    private class PendingInput(private val source: () -> String?) {
        private val history = ArrayDeque<String>()
        private var characters = 0
        private var ended = false

        @Volatile var canReplay = true
            private set

        fun next(): String? {
            if (!canReplay) return source()
            val text = source()
            synchronized(this) {
                if (text == null) {
                    ended = true
                } else if (canReplay) {
                    if (text.length > VoiceIncrementalStreamLimits.MAX_INPUT_BYTES - characters ||
                        history.size >= VoiceIncrementalStreamLimits.MAX_CUES
                    ) {
                        discard()
                    } else {
                        characters += text.length
                        history.addLast(text)
                    }
                }
            }
            return text
        }

        @Synchronized fun discard() {
            canReplay = false
            history.clear()
        }

        // The original worker has retired before the CPU input worker starts.
        fun replay(): String? = history.removeFirstOrNull() ?: if (ended) null else source()
    }
}
