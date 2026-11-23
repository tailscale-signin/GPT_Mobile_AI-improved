package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CancellationException

/** A single SDK task with serialized synthesis and explicit native stream cancellation. */
internal class MediaPipeVoicePhraseEngine(
    private val task: MediaPipeSpeechTask,
    private val modelLease: AutoCloseable,
    backend: VoiceSpeechBackend,
    private val speakingRate: Float,
    private val voiceId: String
) : VoicePhraseEngine {
    private val operationLock = Any()
    private val stateLock = Any()
    private var activeStream: MediaPipeSpeechStream? = null
    private var owner: Thread? = null
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

    override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
        require(speed.toBits() == speakingRate.toBits()) { "Speech rate changed; prepare a new SDK engine." }
        require(text.isNotBlank()) { "Speech text is empty." }
        synchronized(operationLock) {
            synchronized(stateLock) {
                check(!closing && !closed && !retirementFailed) { "Speech engine is closed or could not retire a native stream." }
                checkCancellation()
                owner = Thread.currentThread()
                opening = true
            }
            var stream: MediaPipeSpeechStream? = null
            try {
                val cues = MediaPipeWordCueMapper(text)
                stream = task.openStream(text, voiceId)
                synchronized(stateLock) {
                    activeStream = stream
                    opening = false
                    if (cancellationRequested) stream.cancel()
                    checkCancellation()
                }
                var frames = 0L
                var nonSilent = false
                var packets = 0
                var finalSeen = false
                while (true) {
                    synchronized(stateLock) { checkCancellation() }
                    val chunk = stream.readNext() ?: break
                    synchronized(stateLock) { checkCancellation() }
                    require(!finalSeen) { "Speech SDK returned audio after its final chunk." }
                    require(++packets <= MAX_NATIVE_PACKETS) { "Speech stream packet limit exceeded." }
                    require(chunk.sampleRateHz == VOICE_PCM_SAMPLE_RATE) { "Speech SDK must return 24 kHz mono PCM." }
                    require(frames + chunk.samples.size <= VoiceStreamFrames.MAX_STREAM_FRAMES) { "Speech stream frame limit exceeded." }
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
                            // The native SDK owns its array. Each handoff gets an independent copy
                            // which remains stable while the bounded playback queue retains it.
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
                require(frames > 0 && nonSilent) { "Speech SDK returned no audible PCM." }
            } catch (failure: Throwable) {
                synchronized(stateLock) {
                    runCatching { activeStream?.cancel() }
                    if (cancellationRequested && failure !is CancellationException) {
                        throw CancellationException("Speech stream cancelled.").also { it.initCause(failure) }
                    }
                }
                throw failure
            } finally {
                synchronized(stateLock) {
                    // Remove every cancellable reference before native destruction. A UI
                    // cancellation during retirement must not wait for close() or touch it.
                    activeStream = null
                    opening = false
                }
                try {
                    stream?.close()
                } catch (failure: Throwable) {
                    synchronized(stateLock) { retirementFailed = true }
                    throw failure
                } finally {
                    synchronized(stateLock) { owner = null }
                }
            }
        }
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
            cancelNative()
        }
    }

    override fun close() {
        synchronized(stateLock) {
            if (closed || closing) return
            cancellationRequested = true
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
    }
}
