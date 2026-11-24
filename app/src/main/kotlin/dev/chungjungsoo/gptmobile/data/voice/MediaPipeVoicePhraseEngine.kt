package dev.chungjungsoo.gptmobile.data.voice

import dev.chungjungsoo.gptmobile.data.privacy.ConversationPrivacyContext
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A single SDK task with serialized synthesis and explicit native stream cancellation. */
internal class MediaPipeVoicePhraseEngine(
    private val task: MediaPipeSpeechTask,
    private val modelLease: AutoCloseable,
    backend: VoiceSpeechBackend,
    private val speakingRate: Float,
    private var voiceId: String,
    override val supportsIncrementalInput: Boolean = true
) : VoicePhraseEngine {
    private val operationLock = Any()
    private val stateLock = Any()
    private var activeStream: MediaPipeSpeechStream? = null
    private var owner: Thread? = null
    private var inputOwner: Thread? = null
    private var quarantinedStream: MediaPipeSpeechStream? = null
    private var opening = false
    private var cancellationRequested = false
    private var closing = false
    private var closed = false
    private var retirementFailed = false

    override val supportsStreaming: Boolean = true

    // A requested delegate is not evidence of actual kernel placement. The SDK provides
    // no such evidence or public thread-count control, so neither is manufactured here.
    override val executionProvider: String = when (backend) {
        VoiceSpeechBackend.LITERT_KOKORO_CPU -> "mediapipe-kokoro-cpu-requested"
        VoiceSpeechBackend.LITERT_KOKORO_GPU -> "mediapipe-kokoro-gpu-requested"
        VoiceSpeechBackend.PHONON_CPU -> "mediapipe-phonon-cpu-requested"
        VoiceSpeechBackend.POCKET_CPU -> "mediapipe-pocket-cpu-requested"
        else -> error("Unsupported MediaPipe speech backend")
    }

    override fun selectVoice(voiceId: String) {
        synchronized(operationLock) {
            synchronized(stateLock) {
                check(!closing && !closed && !retirementFailed && owner == null) { "Speech engine is not idle." }
            }
            require(voiceId in task.voiceIds()) { "The selected voice is unavailable in this model." }
            this.voiceId = voiceId
        }
    }

    override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
        require(text.isNotBlank()) { "Speech text is empty." }
        produce(text, null, speed, emit)
    }

    override fun streamInput(nextText: () -> String?, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
        check(supportsIncrementalInput) { "Incremental speech input is disabled for this experiment." }
        produce("", nextText, speed, emit)
    }

    private fun produce(text: String, nextText: (() -> String?)?, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
        require(speed.toBits() == speakingRate.toBits()) { "Speech rate changed; prepare a new SDK engine." }
        synchronized(operationLock) {
            synchronized(stateLock) {
                check(!closing && !closed && !retirementFailed) { "Speech engine is closed or could not retire a native stream." }
                checkCancellation()
                owner = Thread.currentThread()
                opening = true
            }
            var stream: MediaPipeSpeechStream? = null
            var feeder: Thread? = null
            val inputFailure = AtomicReference<Throwable?>()
            val inputFinished = AtomicBoolean(nextText == null)
            val frameLimit = if (nextText == null) VoiceStreamFrames.MAX_STREAM_FRAMES else VoiceIncrementalStreamLimits.MAX_FRAMES
            val cueLimit = if (nextText == null) VoiceStreamFrames.MAX_CUES else VoiceIncrementalStreamLimits.MAX_CUES
            val packetLimit = if (nextText == null) MAX_NATIVE_PACKETS else MAX_INCREMENTAL_PACKETS
            var completed = false
            try {
                val cues = MediaPipeWordCueMapper(text, frameLimit, cueLimit)
                stream = if (nextText == null) task.openStream(text, voiceId) else task.openIncrementalStream(voiceId)
                val opened = stream
                synchronized(stateLock) {
                    activeStream = opened
                    opening = false
                    if (cancellationRequested) opened.cancel()
                    checkCancellation()
                }
                if (nextText != null) {
                    val privacy = ConversationPrivacyContext.capture()
                    feeder = Thread({
                        ConversationPrivacyContext.withCaptured(privacy) {
                            try {
                                var deltas = 0
                                while (true) {
                                    synchronized(stateLock) { checkCancellation() }
                                    val delta = nextText() ?: break
                                    synchronized(stateLock) { checkCancellation() }
                                    require(delta.isNotEmpty() && ++deltas <= VoiceIncrementalStreamLimits.MAX_CUES) { "STREAM_INPUT_LIMIT" }
                                    // Publish byte boundaries before native synthesis can emit their cues.
                                    // appendText enforces the whole reply's input-byte ceiling without truncation.
                                    cues.appendText(delta)
                                    opened.pushText(delta)
                                }
                                synchronized(stateLock) { checkCancellation() }
                                inputFinished.set(true)
                                opened.finishInput()
                            } catch (failure: Throwable) {
                                inputFailure.compareAndSet(null, failure)
                                synchronized(stateLock) { runCatching { activeStream?.cancel() } }
                            }
                        }
                    }, "SpeechTextInput").apply { isDaemon = true }
                    synchronized(stateLock) {
                        inputOwner = feeder
                        checkCancellation()
                    }
                    feeder.start()
                }
                var frames = 0L
                var nonSilent = false
                var packets = 0
                var finalSeen = false
                while (true) {
                    synchronized(stateLock) { checkCancellation() }
                    inputFailure.get()?.let { throw it }
                    val chunk = opened.readNext() ?: break
                    synchronized(stateLock) { checkCancellation() }
                    inputFailure.get()?.let { throw it }
                    require(!finalSeen) { "Speech SDK returned audio after its final chunk." }
                    require(++packets <= packetLimit) { "Speech stream packet limit exceeded." }
                    require(chunk.sampleRateHz == VOICE_PCM_SAMPLE_RATE) { "Speech SDK must return 24 kHz mono PCM." }
                    require(frames + chunk.samples.size <= frameLimit) { "Speech stream frame limit exceeded." }
                    require(chunk.samples.all(Float::isFinite)) { "Speech SDK returned invalid PCM." }
                    nonSilent = nonSilent || chunk.samples.any { kotlin.math.abs(it) >= 1e-5f }
                    frames += chunk.samples.size
                    val readyCues = cues.accept(chunk.wordTimestamps, frames)
                    if (chunk.samples.isEmpty()) {
                        if (readyCues.isNotEmpty()) emitChecked(VoicePcmChunk(FloatArray(0), wordCues = readyCues), emit)
                    } else {
                        var offset = 0
                        while (offset < chunk.samples.size) {
                            val end = minOf(offset + VOICE_PCM_SAMPLE_RATE, chunk.samples.size)
                            // The native SDK owns its array. Queue slices get independent ownership.
                            emitChecked(
                                VoicePcmChunk(chunk.samples.copyOfRange(offset, end), wordCues = if (offset == 0) readyCues else emptyList()),
                                emit
                            )
                            offset = end
                        }
                    }
                    finalSeen = chunk.isFinal
                }
                synchronized(stateLock) { checkCancellation() }
                inputFailure.get()?.let { throw it }
                require(inputFinished.get()) { "Speech SDK ended before incremental text input finished." }
                require(frames > 0 && nonSilent) { "Speech SDK returned no audible PCM." }
                completed = true
            } catch (failure: Throwable) {
                synchronized(stateLock) {
                    runCatching { activeStream?.cancel() }
                    if (cancellationRequested && failure !is CancellationException) {
                        throw CancellationException("Speech stream cancelled.").also { it.initCause(failure) }
                    }
                }
                throw inputFailure.get()?.takeUnless { it is InterruptedException && failure is CancellationException } ?: failure
            } finally {
                try {
                    // Both pushText/finishInput and readNext must return before native destruction.
                    // A stuck input callback/native push quarantines the stream and model lease.
                    val worker = feeder
                    if (worker?.isAlive == true) {
                        if (!completed) {
                            worker.interrupt()
                            runCatching { stream?.cancel() }
                        }
                        retireInput(worker)
                        if (worker.isAlive && completed) {
                            worker.interrupt()
                            runCatching { stream?.cancel() }
                            retireInput(worker)
                        }
                    }
                    synchronized(stateLock) {
                        inputOwner = null
                        activeStream = null
                        opening = false
                        if (worker?.isAlive == true) {
                            quarantinedStream = stream
                            retirementFailed = true
                        }
                    }
                    check(worker?.isAlive != true) { "Native speech input retirement failed; restart the app before another preparation." }
                    try {
                        stream?.close()
                    } catch (failure: Throwable) {
                        synchronized(stateLock) { retirementFailed = true }
                        throw failure
                    }
                    if (completed) inputFailure.get()?.let { throw it }
                } finally {
                    synchronized(stateLock) { owner = null }
                }
            }
        }
    }

    private fun retireInput(worker: Thread) {
        val deadline = System.nanoTime() + INPUT_RETIREMENT_MS * 1_000_000
        var interrupted = false
        while (worker.isAlive) {
            val remaining = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
            if (remaining == 0L) break
            try {
                worker.join(remaining)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun emitChecked(chunk: VoicePcmChunk, emit: (VoicePcmChunk) -> Boolean) {
        synchronized(stateLock) { checkCancellation() }
        if (!emit(chunk)) {
            cancel()
            throw CancellationException("Speech playback cancelled.")
        }
        synchronized(stateLock) { checkCancellation() }
    }

    override fun resetCancellation() {
        synchronized(stateLock) {
            check(!closing && !closed && !retirementFailed && owner == null) { "Speech synthesis must retire before it is reset." }
            cancellationRequested = false
        }
    }

    override fun cancel() {
        synchronized(stateLock) {
            if (closed) return
            cancellationRequested = true
            inputOwner?.interrupt()
            cancelNative()
        }
    }

    override fun close() {
        synchronized(stateLock) {
            if (closed || closing) return
            cancellationRequested = true
            inputOwner?.interrupt()
            cancelNative()
            check(owner !== Thread.currentThread()) { "Speech owner must return before the engine is closed." }
            closing = true
        }
        // cancel() remains reachable while readNext blocks. Destruction and model unlinking
        // wait until the synthesis owner has closed its stream and retired.
        synchronized(operationLock) {
            synchronized(stateLock) {
                closed = true
                // The C API exposes separate stream/task destruction with no promise that
                // destroying a task retires an outstanding stream. Keep both mapped on failure.
                check(!retirementFailed) { "Native speech stream retirement failed; restart the app before removing its model." }
            }
            task.close()
            // If native destruction throws, retain the lease: unlinking a possibly mapped
            // model is less safe than leaving it installed until the process exits.
            modelLease.close()
        }
    }

    /** Only called with stateLock held, so cancellation cannot race native handle destruction. */
    private fun cancelNative() {
        activeStream?.cancel() ?: if (opening) task.cancel() else Unit
    }

    /** Only called with stateLock held. */
    private fun checkCancellation() {
        if (cancellationRequested || Thread.currentThread().isInterrupted) throw CancellationException("Speech stream cancelled.")
    }

    private companion object {
        const val MAX_NATIVE_PACKETS = 4_096
        const val MAX_INCREMENTAL_PACKETS = 32_768
        const val INPUT_RETIREMENT_MS = 1_000L
    }
}
