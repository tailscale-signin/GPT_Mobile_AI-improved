package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.atomic.AtomicBoolean

/** One reply's structural proof; no SDK delegate or token-duration evidence is invented. */
internal class VoiceSpeechEnginePlaybackObservation(private val nanoTime: () -> Long = System::nanoTime) {
    private var calls = 0
    private var completed = 0
    private var failed = false
    private var startedAt: Long? = null
    private var completedWallMs = 0L
    private var callbacks = 0
    private val signal = VoicePcmSignalMeasurement()
    private var firstPcmAfterStreamStartMs: Long? = null

    @Synchronized fun signalSnapshot(): VoicePcmSignal? = signal.snapshot()

    @Synchronized fun firstPcmMs(): Long? = firstPcmAfterStreamStartMs

    @Synchronized fun started() {
        check(startedAt == null)
        startedAt = nanoTime()
        calls++
    }

    @Synchronized fun completed() {
        completedWallMs += (nanoTime() - checkNotNull(startedAt)).coerceAtLeast(0) / 1_000_000
        startedAt = null
        completed++
    }

    @Synchronized fun failed() {
        failed = true
    }

    @Synchronized fun accepted(pcm: VoicePcmChunk) {
        if (pcm.samples.isNotEmpty()) {
            require(callbacks < VoiceSpeechEnginePcmMeasurement.MAX_PCM_CALLBACKS) { "BENCHMARK_PCM_CALLBACK_LIMIT" }
            if (firstPcmAfterStreamStartMs == null) firstPcmAfterStreamStartMs = startedAt?.let { (nanoTime() - it).coerceAtLeast(0) / 1_000_000 }
            signal.accept(pcm.samples)
            callbacks++
        }
    }

    @Synchronized fun callCount(): Int = calls

    @Synchronized fun validated(): Boolean = calls > 0 && completed == calls && !failed

    @Synchronized fun streamWallMs(): Long? = completedWallMs.takeIf { validated() }
}

internal class VoiceSpeechEnginePlaybackRelay {
    @Volatile var current: VoiceSpeechEnginePlaybackObservation? = null
}

/** Observes engine callback PCM after app tempo processing and before playback pause trimming. */
internal class VoiceSpeechBenchmarkStreamingEngine(
    private val delegate: VoicePhraseEngine,
    private val measurement: () -> VoiceSpeechPlaybackMeasurement?,
    private val observation: () -> VoiceSpeechEnginePlaybackObservation?,
    private val cleanupFailed: () -> Unit = {}
) : VoicePhraseEngine by delegate {
    private val cancelled = AtomicBoolean()
    init {
        require(delegate.supportsStreaming)
    }

    override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
        val receipt = observation()
        val timing = measurement()
        // Capture before entering the asynchronous producer; a late call can never validate
        // a new reply's receipt. Production preserves the captured privacy context as well.
        receipt?.started()
        timing?.attemptStarted(text)
        val frames = VoiceStreamFrames(text)
        try {
            consumeVoiceStream(delegate, text, speaker, speed, { !cancelled.get() }) { pcm ->
                frames.accept(pcm)
                receipt?.accepted(pcm)
                emit(pcm)
            }
            frames.finish()
            receipt?.completed()
        } catch (failure: Throwable) {
            receipt?.failed()
            throw failure
        }
    }

    override fun streamInput(nextText: () -> String?, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
        val receipt = observation()
        val timing = measurement()
        val text = VoiceIncrementalTextMap()
        val frames = VoiceStreamFrames.incremental(text)
        receipt?.started()
        timing?.incrementalAttemptStarted()
        try {
            consumeVoiceInputStream(delegate, {
                nextText()?.also { text.append(it) }
            }, speaker, speed, { !cancelled.get() }) { pcm ->
                frames.accept(pcm)
                receipt?.accepted(pcm)
                emit(pcm)
            }
            frames.finish()
            receipt?.completed()
        } catch (failure: Throwable) {
            receipt?.failed()
            throw failure
        }
    }

    override fun cancel() {
        cancelled.set(true)
        delegate.cancel()
    }

    override fun resetCancellation() {
        delegate.resetCancellation()
        cancelled.set(false)
    }

    override fun close() {
        try {
            delegate.close()
        } catch (failure: Throwable) {
            cleanupFailed()
            throw failure
        }
    }
}
