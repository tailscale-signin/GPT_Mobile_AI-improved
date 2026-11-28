package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Compiled Pocket captions wait for final recognition of one short phrase's actual post-tempo
 * audio. The original samples are then played with validated source-matching inferred cues.
 * Captions off or a missing pack bypass both buffering and the extra inference completely.
 */
internal class PocketCaptionVoicePhraseEngine internal constructor(
    private val delegate: VoicePhraseEngine,
    private val openAligner: (() -> Unit) -> PocketCaptionAlignmentEngine?,
    private val onNativeCleanupFailed: (Any) -> Unit = {},
    private val nanoTime: () -> Long = System::nanoTime
) : VoicePhraseEngine by delegate {
    constructor(delegate: VoicePhraseEngine, modelStore: PocketCaptionModelStore, onNativeCleanupFailed: (Any) -> Unit = {}) : this(
        delegate,
        { checkActive -> PocketCaptionAligner.open(modelStore, checkActive, onNativeCleanupFailed) },
        onNativeCleanupFailed
    )

    private val owner = Any()
    private val cancelled = AtomicBoolean()
    private val attempts = AtomicLong()
    private var enabled = false
    private var closed = false
    private var aligner: PocketCaptionAlignmentEngine? = null
    private var cleanupFailure: Throwable? = null

    @Volatile private var status = PocketCaptionStatus.DISABLED

    @Volatile private var alignedWords = 0

    @Volatile private var alignmentMs = 0L

    private data class Progress(val attempt: Long, val started: Long, val phaseStarted: Long, val sequence: Long, val stage: VoiceSpeechStage, val active: Boolean)

    @Volatile private var progress: Progress? = null

    internal val captionStatus get() = status

    override val executionProgress: VoiceSpeechProgress?
        get() = progress?.let { current ->
            val now = nanoTime()
            VoiceSpeechProgress(current.stage, current.attempt, current.sequence, (now - current.phaseStarted).coerceAtLeast(0) / 1_000_000, (now - current.started).coerceAtLeast(0) / 1_000_000, cancelled.get(), current.active)
        } ?: delegate.executionProgress

    override val executionEvidence: VoiceSpeechExecutionEvidence?
        get() = delegate.executionEvidence?.let { evidence ->
            val receipt = "caption_status=${status.name};caption_words=$alignedWords;caption_ms=$alignmentMs"
            // The underlying compiled receipt and these scalar fields fit the existing
            // 512-character allowlist. No recognized words, input text or PCM are logged.
            evidence.copy(discoverySummary = listOfNotNull(evidence.discoverySummary, receipt).joinToString(";"))
        }

    override fun setCaptionAlignmentEnabled(enabled: Boolean) = synchronized(owner) {
        check(!closed)
        this.enabled = enabled
        status = if (enabled) PocketCaptionStatus.BUFFERING else PocketCaptionStatus.DISABLED
    }

    override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) = synchronized(owner) {
        check(!closed && cleanupFailure == null)
        checkActive()
        val began = nanoTime()
        progress = Progress(attempts.incrementAndGet(), began, began, 1, VoiceSpeechStage.SYNTHESIS, true)
        try {
            alignedWords = 0
            alignmentMs = 0
            status = if (enabled) PocketCaptionStatus.BUFFERING else PocketCaptionStatus.DISABLED
            fun forward(chunk: VoicePcmChunk): Boolean {
                checkActive()
                if (!emit(chunk)) {
                    cancel()
                    throw CancellationException("Pocket caption playback cancelled")
                }
                return true
            }
            if (!enabled) {
                delegate.stream(text, speaker, speed, ::forward)
                checkActive()
                return@synchronized
            }
            val parts = PocketCaptionPolicy.parts(text)
            if (parts == null) {
                status = PocketCaptionStatus.TEXT_LIMIT
                delegate.stream(text, speaker, speed, ::forward)
                checkActive()
                return@synchronized
            }
            phase(VoiceSpeechStage.PREPARING)
            val ready = try {
                aligner ?: openAligner(::checkActive).also { aligner = it }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: PocketCaptionCleanupFailure) {
                quarantine(failure)
            } catch (_: LinkageError) {
                status = PocketCaptionStatus.NATIVE_ERROR
                null
            } catch (_: Exception) {
                status = PocketCaptionStatus.NATIVE_ERROR
                null
            }
            if (ready == null) {
                if (status != PocketCaptionStatus.NATIVE_ERROR) status = PocketCaptionStatus.PACK_UNAVAILABLE
                phase(VoiceSpeechStage.SYNTHESIS)
                delegate.stream(text, speaker, speed, ::forward)
                checkActive()
                return@synchronized
            }
            val held = FloatArray(PocketCaptionPolicy.MAX_PHRASE_FRAMES)
            var streamFrames = 0L
            var nativeAvailable = true
            fun note(next: PocketCaptionStatus) {
                if (status == PocketCaptionStatus.BUFFERING || status == PocketCaptionStatus.ALIGNED) status = next
            }
            fun flush(count: Int, cues: List<VoiceWordCue>) {
                var offset = 0
                while (offset < count) {
                    val end = minOf(count, offset + PocketCaptionPolicy.OUTPUT_PACKET_FRAMES)
                    forward(VoicePcmChunk(held.copyOfRange(offset, end), wordCues = if (offset == 0) cues else emptyList()))
                    streamFrames += end - offset
                    offset = end
                }
            }
            try {
                for (part in parts) {
                    checkActive()
                    phase(VoiceSpeechStage.SYNTHESIS)
                    val partStart = streamFrames
                    if (!nativeAvailable) {
                        delegate.stream(part.text, speaker, speed) { chunk ->
                            forward(chunk.copy(wordCues = emptyList()))
                            streamFrames += chunk.samples.size
                            true
                        }
                        continue
                    }
                    var heldFrames = 0
                    var overflow = false
                    delegate.stream(part.text, speaker, speed) { chunk ->
                        checkActive()
                        require(chunk.sampleRate == VOICE_PCM_SAMPLE_RATE) { "POCKET_CAPTION_SAMPLE_RATE" }
                        if (!overflow && heldFrames.toLong() + chunk.samples.size > held.size) {
                            overflow = true
                            note(PocketCaptionStatus.AUDIO_LIMIT)
                            flush(heldFrames, emptyList())
                            heldFrames = 0
                        }
                        if (overflow) {
                            forward(chunk.copy(wordCues = emptyList()))
                            streamFrames += chunk.samples.size
                        } else {
                            chunk.samples.copyInto(held, heldFrames)
                            heldFrames += chunk.samples.size
                        }
                        true
                    }
                    checkActive()
                    if (overflow) continue
                    val samples = held.copyOf(heldFrames)
                    val started = nanoTime()
                    phase(VoiceSpeechStage.VALIDATING)
                    val alignment = try {
                        if (nativeAvailable) ready.align(part.text, samples, ::checkActive) else PocketCaptionAlignment(PocketCaptionStatus.NATIVE_ERROR)
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: PocketCaptionCleanupFailure) {
                        quarantine(failure)
                    } catch (_: LinkageError) {
                        nativeAvailable = false
                        PocketCaptionAlignment(PocketCaptionStatus.NATIVE_ERROR)
                    } catch (_: Exception) {
                        nativeAvailable = false
                        PocketCaptionAlignment(PocketCaptionStatus.NATIVE_ERROR)
                    } finally {
                        alignmentMs += (nanoTime() - started).coerceAtLeast(0) / 1_000_000
                        samples.fill(0f)
                    }
                    checkActive()
                    if (!nativeAvailable) {
                        try {
                            ready.close()
                            aligner = null
                        } catch (failure: Throwable) {
                            quarantine(failure)
                        }
                    }
                    note(alignment.status)
                    val cues = alignment.cues.map { cue ->
                        cue.copy(startByte = cue.startByte + part.startByte, endByte = cue.endByte + part.startByte, startFrame = cue.startFrame + partStart, endFrame = cue.endFrame + partStart)
                    }
                    alignedWords += cues.size
                    phase(VoiceSpeechStage.READY)
                    flush(heldFrames, cues)
                }
            } finally {
                held.fill(0f)
            }
        } finally {
            progress = progress?.copy(active = false)
        }
    }

    override fun resetCancellation() = synchronized(owner) {
        check(!closed && cleanupFailure == null)
        cancelled.set(false)
        delegate.resetCancellation()
    }

    override fun cancel() {
        cancelled.set(true)
        delegate.cancel()
    }

    override fun close() {
        cancel()
        synchronized(owner) {
            cleanupFailure?.let { throw PocketCaptionCleanupFailure(this, it) }
            if (closed) return
            val failures = listOfNotNull(aligner, delegate).mapNotNull { runCatching { it.close() }.exceptionOrNull() }
            if (failures.isNotEmpty()) quarantine(failures.first())
            aligner = null
            closed = true
        }
    }

    private fun checkActive() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException("Pocket caption alignment cancelled")
    }

    private fun phase(stage: VoiceSpeechStage) {
        progress = progress?.let { it.copy(phaseStarted = nanoTime(), sequence = it.sequence + 1, stage = stage) }
    }

    private fun quarantine(failure: Throwable): Nothing {
        cleanupFailure = failure
        onNativeCleanupFailed(this)
        throw PocketCaptionCleanupFailure(this, failure)
    }
}
