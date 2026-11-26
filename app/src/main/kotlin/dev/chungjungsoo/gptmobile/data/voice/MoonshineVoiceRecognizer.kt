package dev.chungjungsoo.gptmobile.data.voice

import android.Manifest
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Embedded ASR. No SpeechRecognizer, provider quota, model downloader, or network inference.
 * Native calls, including close, run on one serial worker. In particular, stop/close never
 * free an in-flight decoder (upstream Moonshine issue #223). Capture has a bounded PCM queue.
 */
internal class MoonshineVoiceRecognizer internal constructor(
    private val modelsReady: () -> Boolean,
    private val modelDirectory: () -> File,
    private val releaseModel: () -> Unit,
    private val hasPermission: () -> Boolean,
    private val post: (() -> Unit) -> Unit,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val decoderFactory: () -> LocalSpeechDecoder,
    private val captureFactory: () -> LocalVoiceCapture,
    private val worker: ExecutorService
) : VoiceRecognitionInput,
    VoiceInterruptionInput {
    constructor(context: Context, modelStore: LocalVoiceModelStore) : this(
        modelsReady = modelStore::acquireUse,
        modelDirectory = modelStore::modelDirectory,
        releaseModel = modelStore::releaseUse,
        hasPermission = { ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
        post = { action -> main.post { action() } },
        schedule = { delay, action ->
            val task = Runnable { action() }
            main.postDelayed(task, delay)
            val cancel: () -> Unit = { main.removeCallbacks(task) }
            cancel
        },
        decoderFactory = ::MoonshineStreamDecoder,
        captureFactory = { AndroidVoiceCapture(context.applicationContext) },
        worker = Executors.newSingleThreadExecutor { task -> Thread(task, "LocalVoiceDecoder").apply { isDaemon = true } }
    ) {
        observeMemoryPressure(context.applicationContext)
        interruptionMonitor = VoiceInterruptionMonitor(context.applicationContext)
    }

    @Volatile private var closed = false

    @Volatile private var active: CaptureRun? = null
    private var retainedDecoder: LocalSpeechDecoder? = null
    private var retainedStyle: VoiceListeningStyle? = null
    private val modelEpoch = AtomicLong()
    private var memoryContext: Context? = null
    private var memoryCallbacks: ComponentCallbacks2? = null
    private var interruptionMonitor: VoiceInterruptionMonitor? = null
    private val unavailableInterruption = MutableStateFlow(VoiceInterruptionStatus.UNAVAILABLE)
    private val pendingPreRoll = AtomicReference<List<VoicePreRoll>>(emptyList())
    override val interruptionState: StateFlow<VoiceInterruptionStatus>
        get() = interruptionMonitor?.state ?: unavailableInterruption

    @Volatile var listeningStyle: VoiceListeningStyle = VoiceListeningStyle.NATURAL
        set(value) {
            if (field == value) return
            field = value
            releaseWarmModel()
        }

    @Volatile var captureBatchMs: Int = 40
        set(value) {
            require(value in setOf(20, 40, 80, 100))
            field = value
        }
    private var diagnosticProbe: VoiceDiagnosticTrace.Probe? = null
    override fun bindDiagnostics(probe: VoiceDiagnosticTrace.Probe?) {
        diagnosticProbe = probe
    }
    override var languageTag: String = Locale.getDefault().toLanguageTag()
        set(value) {
            if (field != value) stop()
            field = value
        }

    private data class RecognitionFailure(val code: String, val message: String, val cause: Throwable? = null) {
        val priority: Int get() = when (code) {
            "NO_SPEECH" -> 0
            "MICROPHONE_PERMISSION_CHANGED", "NATIVE_RUNTIME_LINKAGE", "NATIVE_MEMORY_PRESSURE" -> 2
            else -> 1
        }
    }

    private data class CapturedAudio(val samples: FloatArray, val capturedNanos: Long, val recycle: (FloatArray) -> Unit)
    private class CaptureRun(
        val listener: VoiceAudio.Listener,
        val diagnostics: VoiceDiagnosticTrace.Probe?,
        val feedback: (() -> Boolean)?,
        val style: VoiceListeningStyle,
        val batchMs: Int,
        val modelEpoch: Long,
        val preRoll: List<VoicePreRoll>
    ) {
        val lease = "local-voice:${UUID.randomUUID()}"
        val startedNanos = System.nanoTime()
        val cancelled = AtomicBoolean()
        val ending = AtomicBoolean()
        val speechDetected = AtomicBoolean()
        val partialDetected = AtomicBoolean()
        val acceptedChunks = AtomicInteger()
        val capturedChunks = AtomicInteger()
        val captureAttempts = AtomicInteger()
        val failure = AtomicReference<RecognitionFailure?>()
        val audio = ArrayBlockingQueue<CapturedAudio>(4000 / batchMs)
        val audioOrEnd = Semaphore(0)
        val captureEnding = CountDownLatch(1)
        val finalQueued = AtomicBoolean()

        @Volatile var lastAcceptedAudioNanos = 0L

        @Volatile var capture: LocalVoiceCapture? = null

        @Volatile var captureThread: Thread? = null

        @Volatile var cancelSilenceDeadline: (() -> Unit)? = null

        @Volatile var cancelTurnDeadline: (() -> Unit)? = null

        @Volatile var transcript = ""

        fun offerAudio(chunk: CapturedAudio): Boolean {
            if (!audio.offer(chunk)) return false
            audioOrEnd.release()
            return true
        }
    }

    private fun recordFailure(run: CaptureRun, code: String, message: String, cause: Throwable? = null) {
        val failure = RecognitionFailure(code, message, cause)
        // A silence deadline can race an in-flight native/capture call. Preserve the
        // actual failure atomically so a late fatal error cannot become an automatic retry.
        run.failure.updateAndGet { previous ->
            if (previous == null || failure.priority > previous.priority) failure else previous
        }
    }

    override fun listen(listener: VoiceAudio.Listener) {
        if (closed) return
        val feedback = if (manualFeedback.getAndSet(false)) onManualCaptureReady else null
        val diagnostics = diagnosticProbe
        diagnostics?.captureRequested()
        diagnostics?.engine(VoiceDiagnosticTrace.Engine.LOCAL)
        if (!LocalVoiceModelStore.supportsLanguage(languageTag)) {
            listener.failure(
                "The installed local speech model supports English. Choose your region in Voice settings.",
                "VOICE_RECOGNIZER_LANGUAGE_UNAVAILABLE",
                recoveryAction = VoiceRecoveryAction.LOCAL_MODEL
            )
            return
        }
        val permitted = hasPermission()
        diagnostics?.permission(permitted)
        if (!permitted) {
            listener.failure("Microphone permission is needed for voice chat.", "VOICE_MICROPHONE_PERMISSION_REQUIRED", recoveryAction = VoiceRecoveryAction.MICROPHONE_PERMISSION)
            return
        }
        if (active != null) return
        val preRoll = pendingPreRoll.getAndSet(emptyList()).takeIf { samples ->
            samples.lastOrNull()?.let { elapsedMillis(it.capturedNanos) < 2000 } == true
        }.orEmpty()
        val run = CaptureRun(listener, diagnostics, feedback, listeningStyle, captureBatchMs, modelEpoch.get(), preRoll)
        if (!VoiceMicrophoneLease.acquire(run.lease)) {
            listener.failure("The previous voice session is releasing the microphone. Try again shortly.", "VOICE_MICROPHONE_BUSY", retryMillis = 500, retryable = true)
            return
        }
        active = run
        engineEvent(run, "queued")
        try {
            worker.execute { recognize(run) }
        } catch (failure: java.util.concurrent.RejectedExecutionException) {
            active = null
            VoiceMicrophoneLease.release(run.lease)
            listener.failure("Local speech has closed. Reopen Voice to continue.", "VOICE_RECOGNIZER_UNAVAILABLE", cause = failure)
        }
    }

    private fun deliver(run: CaptureRun, action: () -> Unit) = post {
        if (!closed && active === run && !run.cancelled.get()) action()
    }

    private fun requestEnd(run: CaptureRun) {
        if (run.ending.compareAndSet(false, true)) {
            run.captureEnding.countDown()
            run.audioOrEnd.release()
            run.capture?.stop()
            deliver(run) { run.listener.ending() }
        }
    }

    private fun recognize(run: CaptureRun) {
        engineEvent(run, "native_gate_wait")
        val started = System.nanoTime()
        VoiceNativeEngineGate.exclusive {
            run.diagnostics?.preparation("GATE_WAIT", elapsedMillis(started))
            recognizeExclusive(run)
        }
    }

    private fun recognizeExclusive(run: CaptureRun) {
        var decoder: LocalSpeechDecoder? = null
        var missingModel = false
        var modelAcquired = false
        try {
            if (run.cancelled.get() || run.ending.get()) return
            val verificationStarted = System.nanoTime()
            val ready = modelsReady()
            run.diagnostics?.preparation("VERIFICATION", elapsedMillis(verificationStarted))
            if (!ready) {
                releaseRetainedDecoder()
                missingModel = true
                return
            }
            modelAcquired = true
            if (run.cancelled.get() || run.ending.get()) return
            engineEvent(run, "native_gate_acquired")
            if (retainedStyle != run.style) releaseRetainedDecoder()
            val warm = retainedDecoder != null
            decoder = retainedDecoder ?: decoderFactory()
            // A retained decoder already owns an installation lease; this acquisition
            // validates the unchanged generation and is released after this turn.
            if (!warm) engineEvent(run, "decoder_create")
            decoder.configure(run.style)
            engineEvent(run, "model_load_start", mapOf("warm" to warm))
            val loadStarted = System.nanoTime()
            run.diagnostics?.warmupStarted()
            decoder.start(modelDirectory()) { event ->
                if (!run.cancelled.get()) {
                    when (event) {
                        LocalSpeechEvent.Speech -> {
                            if (run.speechDetected.compareAndSet(false, true)) engineEvent(run, "speech_detected")
                            run.cancelSilenceDeadline?.invoke()
                        }
                        is LocalSpeechEvent.Partial -> {
                            run.transcript = event.text.take(8000)
                            if (run.partialDetected.compareAndSet(false, true)) engineEvent(run, "first_partial")
                            deliver(run) { run.listener.partial(event.text.take(8000)) }
                        }
                        is LocalSpeechEvent.Final -> {
                            run.transcript = event.text.take(8000)
                            engineEvent(run, "final_transcript")
                            requestEnd(run)
                        }
                        is LocalSpeechEvent.SpeechEnd -> {
                            val queueAge = if (run.lastAcceptedAudioNanos > 0) elapsedMillis(run.lastAcceptedAudioNanos) else 0
                            run.diagnostics?.acousticSpeechEnded(event.elapsedAgoMs + queueAge)
                        }
                        is LocalSpeechEvent.Failure -> {
                            recordFailure(run, event.code, "Local speech stopped unexpectedly. Your captured text is retained; tap to try again.")
                            engineEvent(run, "decoder_callback_failure")
                            requestEnd(run)
                        }
                    }
                }
            }
            engineEvent(run, "model_load_complete")
            run.diagnostics?.preparation("MODEL_LOAD", elapsedMillis(loadStarted))
            run.diagnostics?.engineReady(warm = warm)
            if (run.cancelled.get()) return
            val recording = Thread({ record(run) }, "LocalVoiceCapture").apply { isDaemon = true }
            run.captureThread = recording
            recording.start()
            for (chunk in run.preRoll) {
                if (run.cancelled.get() || run.ending.get()) break
                acceptAudio(run, decoder, CapturedAudio(chunk.samples, chunk.capturedNanos) {})
            }
            while (!run.cancelled.get() && run.failure.get() == null && (!run.ending.get() || run.audio.isNotEmpty())) {
                run.audioOrEnd.acquire()
                if (run.cancelled.get() || run.failure.get() != null) break
                val chunk = run.audio.poll() ?: continue
                acceptAudio(run, decoder, chunk)
            }
            requestEnd(run)
            // stop is capture-only. It never calls native stop/free from this thread's callers.
            joinCapture(run)
            val interruptedInput = run.failure.get()?.code == "MICROPHONE_CAPTURE_SILENCED" &&
                (run.acceptedChunks.get() > 0 || run.audio.isNotEmpty())
            if (!run.cancelled.get() && (run.failure.get() == null || interruptedInput)) {
                // Done speaking can race a partially filled recorder batch. Capture
                // flushes that last batch before closing, so consume it before finalize.
                while (true) {
                    val remainder = run.audio.poll() ?: break
                    acceptAudio(run, decoder, remainder)
                }
                engineEvent(run, "decoder_finish_start")
                decoder.finish()
                engineEvent(run, "decoder_finish_complete")
                // Only finalized text may start the AI request. Microphone ownership
                // ends first; native stream cleanup remains serialized behind this gate.
                if (run.transcript.isNotBlank() && run.failure.get() == null) {
                    VoiceMicrophoneLease.release(run.lease)
                    run.finalQueued.set(true)
                    deliver(run) {
                        active = null
                        run.listener.final(run.transcript)
                    }
                }
            }
        } catch (failure: SecurityException) {
            recordFailure(run, "MICROPHONE_PERMISSION_CHANGED", "Microphone permission changed. Allow microphone access in Android settings and try again.", failure)
        } catch (failure: LinkageError) {
            recordFailure(run, "NATIVE_RUNTIME_LINKAGE", "The local speech runtime could not load on this device. Select the Android speech service or update the app.", failure)
        } catch (failure: OutOfMemoryError) {
            recordFailure(run, "NATIVE_MEMORY_PRESSURE", "Local speech could not load because memory is low. Close other heavy apps or select the Android speech service.", failure)
        } catch (failure: Exception) {
            recordFailure(
                run,
                if (failure is LocalVoiceCaptureException) "MICROPHONE_CAPTURE_FAILED" else "LOCAL_DECODER_FAILED",
                failure.message?.takeIf { failure is LocalVoiceCaptureException } ?: "Local speech stopped unexpectedly. Your captured text is retained; tap to try again.",
                failure
            )
        } finally {
            run.ending.set(true)
            run.captureEnding.countDown()
            run.capture?.stop()
            joinCapture(run)
            run.cancelSilenceDeadline?.invoke()
            run.cancelTurnDeadline?.invoke()
            // Model reuse never overlaps native inference or stream release.
            val keepWarm = decoder?.supportsReuse == true && !closed && run.failure.get() == null && run.modelEpoch == modelEpoch.get()
            try {
                if (decoder != null && keepWarm) {
                    decoder.releaseStream()
                    if (retainedDecoder == null) {
                        retainedDecoder = decoder
                        retainedStyle = run.style
                        modelAcquired = false // transfer this installation lease to the resident model
                    }
                    engineEvent(run, "model_retained")
                } else if (decoder != null) {
                    engineEvent(run, "decoder_close_start")
                    if (retainedDecoder === decoder) releaseRetainedDecoder() else decoder.close()
                    engineEvent(run, "decoder_close_complete")
                }
            } catch (failure: Exception) {
                recordFailure(run, "DECODER_CLOSE_FAILED", "Local speech could not finish releasing its model. Reopen Voice or select the Android speech service.", failure)
                if (retainedDecoder === decoder) runCatching { releaseRetainedDecoder() } else runCatching { decoder?.close() }
            }
            if (modelAcquired) releaseModel()
            if (run.failure.get() != null) engineEvent(run, "failure")
            if (decoder != null) engineEvent(run, if (keepWarm) "stream_released" else "released")
            while (true) {
                val queued = run.audio.poll() ?: break
                queued.recycle(queued.samples)
            }
            VoiceMicrophoneLease.release(run.lease)
            if (!run.finalQueued.get()) {
                deliver(run) {
                    active = null
                    when {
                        missingModel -> run.listener.failure("Install the English local speech model in Voice settings to use offline voice chat.", "VOICE_MODEL_REQUIRED", recoveryAction = VoiceRecoveryAction.LOCAL_MODEL)
                        run.failure.get() != null -> {
                            if (run.failure.get()?.code == "MICROPHONE_CAPTURE_SILENCED" && run.transcript.isNotBlank()) {
                                run.listener.partial(run.transcript)
                            }
                            deliverFailure(run)
                        }
                        run.transcript.isBlank() -> run.listener.failure("No speech was detected. Tap the microphone and try again.", "VOICE_NO_SPEECH", retryMillis = 500, retryable = true)
                        else -> run.listener.final(run.transcript)
                    }
                }
            }
        }
    }

    private fun acceptAudio(run: CaptureRun, decoder: LocalSpeechDecoder, chunk: CapturedAudio) {
        run.lastAcceptedAudioNanos = chunk.capturedNanos
        run.diagnostics?.captureQueue(elapsedMillis(chunk.capturedNanos))
        try {
            decoder.accept(chunk.samples)
            run.acceptedChunks.incrementAndGet()
        } finally {
            chunk.recycle(chunk.samples)
        }
    }

    private fun deliverFailure(run: CaptureRun) {
        val failure = requireNotNull(run.failure.get())
        val failureCode = failure.code
        val captureFailure = failure.cause as? LocalVoiceCaptureException
        // A focus handoff can settle after playback stops. Retry at the session's
        // bounded backoff, never by immediately recreating the same denied request.
        val retryInput = failureCode in setOf("MICROPHONE_FOCUS_DENIED", "MICROPHONE_CAPTURE_SILENCED") &&
            captureFailure?.retryable == true &&
            captureFailure.capturedSamples == 0L &&
            run.capturedChunks.get() == 0
        val repair = when (failureCode) {
            "MICROPHONE_PERMISSION_CHANGED" -> VoiceRecoveryAction.MICROPHONE_PERMISSION
            "NATIVE_RUNTIME_LINKAGE", "NATIVE_MEMORY_PRESSURE" -> VoiceRecoveryAction.LOCAL_MODEL
            else -> null
        }
        val code = when (failureCode) {
            "MICROPHONE_PERMISSION_CHANGED" -> "VOICE_MICROPHONE_PERMISSION_REQUIRED"
            "NATIVE_RUNTIME_LINKAGE" -> "VOICE_RECOGNIZER_UNAVAILABLE"
            "NATIVE_MEMORY_PRESSURE" -> "VOICE_RECOGNIZER_MEMORY_PRESSURE"
            "NO_SPEECH" -> "VOICE_NO_SPEECH"
            "AUDIO_QUEUE_OVERFLOW" -> "VOICE_AUDIO_QUEUE_OVERFLOW"
            "MICROPHONE_CAPTURE_SILENCED" -> "VOICE_MICROPHONE_BUSY"
            else -> if (failure.cause is LocalVoiceCaptureException) "VOICE_${failure.code}" else "VOICE_RECOGNIZER_FAILED"
        }
        run.listener.failure(
            failure.message,
            code,
            retryMillis = if (retryInput) {
                1_000
            } else if (failureCode == "NO_SPEECH") {
                500
            } else {
                0
            },
            retryable = retryInput || failureCode == "NO_SPEECH",
            recoveryAction = repair,
            cause = failure.cause
        )
    }

    private fun engineEvent(run: CaptureRun, stage: String, extra: Map<String, Any?> = emptyMap()) {
        val durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - run.startedNanos)
        val failure = run.failure.get()
        val fields = mapOf(
            "stage" to stage, "nativeOperationId" to run.lease, "engineId" to run.lease,
            "incidentId" to run.lease, "durationMs" to durationMillis, "cancelled" to run.cancelled.get(),
            "ending" to run.ending.get(), "acceptedChunks" to run.acceptedChunks.get(),
            "capturedChunks" to run.capturedChunks.get(), "captureAttempts" to run.captureAttempts.get(),
            "queuedChunks" to run.audio.size, "transcriptChars" to run.transcript.length,
            "failureCode" to (failure?.code ?: "none"), "permissionGranted" to hasPermission()
        ) + (failure?.cause as? LocalVoiceCaptureException)?.diagnosticFields().orEmpty() + extra
        if (stage in nativeBoundaryStages) {
            // Persist before entering the native call; a SIGABRT cannot drain the async queue.
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.checkpoint(
                "VoiceEngine",
                dev.chungjungsoo.gptmobile.data.diagnostics.structuredDiagnosticEventMessage("moonshine_$stage", fields)
            )
        } else {
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.recordEvent("VoiceEngine", "moonshine_$stage", fields)
        }
    }

    private fun record(run: CaptureRun) {
        while (!run.cancelled.get() && !run.ending.get()) {
            var capture: LocalVoiceCapture? = null
            try {
                val permitted = hasPermission()
                run.diagnostics?.permission(permitted)
                if (!permitted) throw SecurityException("Microphone permission was revoked before capture started.")
                run.captureAttempts.incrementAndGet()
                run.diagnostics?.captureAttempt()
                capture = captureFactory()
                capture.bindDiagnostics(run.diagnostics)
                capture.configure(run.batchMs)
                run.capture = capture
                captureAudio(run, capture)
                return
            } catch (failure: LocalVoiceCaptureException) {
                if (!run.cancelled.get() &&
                    !run.ending.get() &&
                    mayRecreateVoiceCapture(failure, run.captureAttempts.get(), run.capturedChunks.get())
                ) {
                    engineEvent(run, "capture_recreate", failure.diagnosticFields())
                    // Release ownership before giving Android time to settle the input route.
                    if (failure.code == "MICROPHONE_CAPTURE_SILENCED") {
                        if (run.capture === capture) run.capture = null
                        runCatching { capture?.close() }
                        capture = null
                        try {
                            if (run.captureEnding.await(if (run.captureAttempts.get() == 1) 200L else 800L, TimeUnit.MILLISECONDS)) return
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return
                        }
                    }
                    continue
                }
                if (!run.cancelled.get() && !run.ending.get()) {
                    recordFailure(run, failure.code, requireNotNull(failure.message), failure)
                    requestEnd(run)
                }
                return
            } catch (failure: SecurityException) {
                run.diagnostics?.permission(hasPermission())
                recordFailure(run, "MICROPHONE_PERMISSION_CHANGED", "Microphone permission changed. Allow microphone access and try again.", failure)
                requestEnd(run)
                return
            } catch (failure: OutOfMemoryError) {
                recordFailure(run, "NATIVE_MEMORY_PRESSURE", "Local speech could not record because memory is low. Close other heavy apps or select the Android speech service.", failure)
                requestEnd(run)
                return
            } catch (failure: Exception) {
                if (!run.cancelled.get() && !run.ending.get()) {
                    recordFailure(run, "MICROPHONE_READ_FAILED", "The microphone could not record audio. Check your audio route and try again.", failure)
                    requestEnd(run)
                }
                return
            } finally {
                run.cancelSilenceDeadline?.invoke()
                run.cancelTurnDeadline?.invoke()
                if (run.ending.get() && !run.cancelled.get() && (run.failure.get() == null || run.failure.get()?.code == "MICROPHONE_CAPTURE_SILENCED")) {
                    val remainder = capture?.drainPending() ?: FloatArray(0)
                    if (remainder.isNotEmpty() && !run.offerAudio(CapturedAudio(remainder, System.nanoTime()) {})) {
                        recordFailure(run, "AUDIO_QUEUE_OVERFLOW", "Local speech could not keep up with the microphone. Your captured text is retained; close other heavy apps and try again.")
                    }
                }
                if (run.capture === capture) run.capture = null
                runCatching { capture?.close() }
            }
        }
    }

    @Volatile var onManualCaptureReady: (() -> Boolean)? = null
    private val manualFeedback = java.util.concurrent.atomic.AtomicBoolean()

    fun armManualCaptureFeedback() {
        manualFeedback.set(true)
    }

    fun clearManualCaptureFeedback() {
        manualFeedback.set(false)
    }

    private fun captureAudio(run: CaptureRun, capture: LocalVoiceCapture) {
        if (run.cancelled.get() || run.ending.get()) return
        capture.start()
        if (run.cancelled.get() || run.ending.get()) return
        engineEvent(run, "capture_ready")
        run.diagnostics?.microphoneReady()
        val attempt = run.captureAttempts.get()
        run.cancelSilenceDeadline = schedule(12_000) {
            if (run.capture === capture && run.captureAttempts.get() == attempt && !run.cancelled.get() && !run.ending.get() && !run.speechDetected.get()) {
                recordFailure(run, "NO_SPEECH", "No speech was detected. Tap the microphone and try again.")
                requestEnd(run)
            }
        }
        run.cancelTurnDeadline = schedule(30_000) {
            if (run.capture === capture && run.captureAttempts.get() == attempt && !run.cancelled.get()) requestEnd(run)
        }
        val cuePlayed = run.feedback?.invoke() == true
        val cueEnd = if (cuePlayed) System.nanoTime() + 150_000_000 else 0
        deliver(run) { run.listener.ready() }
        while (!run.cancelled.get() && !run.ending.get()) {
            val samples = capture.read()
            if (samples.isEmpty()) continue
            if (System.nanoTime() < cueEnd) {
                capture.recycle(samples)
                continue
            }
            if (run.capturedChunks.incrementAndGet() == 1) engineEvent(run, "first_pcm")
            if (!run.offerAudio(CapturedAudio(samples, System.nanoTime(), capture::recycle))) {
                capture.recycle(samples)
                recordFailure(run, "AUDIO_QUEUE_OVERFLOW", "Local speech could not keep up with the microphone. Your captured text is retained; close other heavy apps and try again.")
                requestEnd(run)
                break
            }
            val rms = sqrt(samples.sumOf { (it * it).toDouble() } / samples.size).toFloat()
            deliver(run) { run.listener.level((rms * 4).coerceIn(0f, 1f)) }
        }
    }

    private fun joinCapture(run: CaptureRun) {
        val recording = run.captureThread ?: return
        var interrupted = false
        while (recording.isAlive) {
            try {
                recording.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    override fun stop() {
        val run = active ?: return
        run.cancelled.set(true)
        run.captureEnding.countDown()
        run.audioOrEnd.release()
        active = null
        run.capture?.stop()
        run.cancelSilenceDeadline?.invoke()
        run.cancelTurnDeadline?.invoke()
        // The lease remains held until the worker finishes native cleanup; no overlapping mic starts.
    }

    fun finishListening() {
        val run = active ?: return
        if (!run.cancelled.get()) requestEnd(run)
    }

    fun releaseWarmModel(completed: (() -> Unit)? = null) {
        modelEpoch.incrementAndGet()
        try {
            if (!worker.isShutdown) {
                worker.execute {
                    try {
                        VoiceNativeEngineGate.exclusive { releaseRetainedDecoder() }
                    } finally {
                        completed?.let { post(it) }
                    }
                }
            } else if (worker.isTerminated) {
                completed?.let { post(it) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // A concurrent close already schedules release before shutting the worker.
        }
    }

    override fun startInterruptionMonitoring(outputLevel: () -> Float?, interrupted: () -> Unit) {
        if (closed) return
        interruptionMonitor?.start(outputLevel) { samples ->
            pendingPreRoll.set(samples)
            interrupted()
        }
    }

    override fun stopInterruptionMonitoring() {
        interruptionMonitor?.stop()
    }

    override fun afterInterruptionStopped(released: () -> Unit) {
        if (closed) return
        val monitor = interruptionMonitor
        if (monitor == null) released() else monitor.stop(released)
    }

    private fun releaseRetainedDecoder() {
        val previous = retainedDecoder ?: return
        retainedDecoder = null
        retainedStyle = null
        try {
            previous.close()
        } finally {
            releaseModel()
        }
    }

    private fun observeMemoryPressure(context: Context) {
        val callbacks = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() = releaseWarmModel()
            override fun onTrimMemory(level: Int) {
                if (level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) releaseWarmModel()
            }
        }
        memoryContext = context
        memoryCallbacks = callbacks
        context.registerComponentCallbacks(callbacks)
    }

    override fun close() {
        if (closed) return
        closed = true
        interruptionMonitor?.close()
        pendingPreRoll.set(emptyList())
        stop()
        memoryCallbacks?.let { memoryContext?.unregisterComponentCallbacks(it) }
        releaseWarmModel()
        worker.shutdown()
    }

    private fun elapsedMillis(started: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).coerceAtLeast(0)

    companion object {
        private val main by lazy { Handler(Looper.getMainLooper()) }
        private val nativeBoundaryStages = setOf("model_load_start", "decoder_finish_start", "decoder_close_start")
    }
}

internal sealed interface LocalSpeechEvent {
    data object Speech : LocalSpeechEvent
    data class Partial(val text: String) : LocalSpeechEvent
    data class Final(val text: String) : LocalSpeechEvent
    data class SpeechEnd(val elapsedAgoMs: Long) : LocalSpeechEvent
    data class Failure(val code: String) : LocalSpeechEvent
}

internal interface LocalSpeechDecoder {
    val supportsReuse: Boolean get() = false
    fun configure(style: VoiceListeningStyle) = Unit
    fun releaseStream() = Unit
    fun start(directory: File, events: (LocalSpeechEvent) -> Unit)
    fun accept(samples: FloatArray)
    fun finish()
    fun close()
}

internal interface LocalVoiceCapture {
    fun configure(batchMs: Int) = Unit
    fun recycle(samples: FloatArray) = Unit
    fun drainPending(): FloatArray = FloatArray(0)
    fun bindDiagnostics(probe: VoiceDiagnosticTrace.Probe?) = Unit
    fun start()
    fun read(): FloatArray
    fun stop()
    fun close()
}

/** Capture release is owned by the recording thread; stop only unblocks capture. No PCM is persisted. */
internal class AndroidVoiceCapture(
    private val context: Context,
    private val manager: AudioManager? = context.getSystemService(AudioManager::class.java),
    private val minimumBufferSize: () -> Int = { AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) },
    private val allowPlayback: Boolean = false,
    private val createRecorder: (Int) -> AudioRecord = { bufferSize -> createAudioRecorder(context, bufferSize, allowPlayback) }
) : LocalVoiceCapture {
    private val lock = Any()
    private var diagnosticProbe: VoiceDiagnosticTrace.Probe? = null
    override fun bindDiagnostics(probe: VoiceDiagnosticTrace.Probe?) {
        diagnosticProbe = probe
    }
    private var recorder: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    @Volatile private var focus: AudioFocusRequest? = null
    private var focusResult: Int? = null
    private var buffer = ShortArray(1600)
    private var pool = VoiceCaptureBufferPool(buffer.size)
    override fun configure(batchMs: Int) {
        require(batchMs in setOf(20, 40, 80, 100))
        check(recorder == null)
        buffer = ShortArray(batchMs * 16)
        pool = VoiceCaptureBufferPool(buffer.size)
    }
    override fun recycle(samples: FloatArray) = pool.release(samples)
    private var filled = 0
    private var capturedSamples = 0L
    private var waitedForInputRoute = false

    @Volatile private var stopped = false

    @Volatile private var focusLost = false

    override fun start() = synchronized(lock) {
        if (stopped) return@synchronized
        diagnosticProbe?.captureReadsAvailable()
        // Model loading can outlive the earlier UI permission check.
        val permitted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        diagnosticProbe?.permission(permitted)
        if (!permitted) {
            throw SecurityException("Microphone permission was revoked before capture started.")
        }
        if (!allowPlayback) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener { change ->
                    if (change != AudioManager.AUDIOFOCUS_GAIN && focus != null && !stopped) {
                        focusLost = true
                        stop()
                    }
                }
                .build()
            // Output focus reduces interruptions; Android admits microphone input separately
            // through RECORD_AUDIO, foreground access and the AudioRecord capture policy.
            focus = request
            focusResult = try {
                manager?.requestAudioFocus(request)
            } catch (_: RuntimeException) {
                null
            }
            diagnosticProbe?.focus(
                when (focusResult) {
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> VoiceDiagnosticTrace.Focus.GRANTED
                    null -> VoiceDiagnosticTrace.Focus.ERROR
                    else -> VoiceDiagnosticTrace.Focus.DENIED
                }
            )
            if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                focus = null
                runCatching { manager?.abandonAudioFocusRequest(request) }
                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.recordEvent(
                    "VoiceEngine",
                    "microphone_output_focus_unavailable",
                    mapOf("audioFocusResult" to focusResult)
                )
            }
        }
        if (focusLost) throw focusLostFailure()
        if (stopped) return@synchronized
        val minimum = minimumBufferSize()
        if (minimum <= 0) throw LocalVoiceCaptureException("This microphone route does not support local speech. Select another audio route.", "MICROPHONE_FORMAT_UNSUPPORTED", "capture_configuration", platformCode = minimum)
        val record = try {
            createRecorder(maxOf(minimum, 16_000))
        } catch (failure: SecurityException) {
            diagnosticProbe?.permission(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            throw failure
        }
        recorder = record
        if (stopped) return@synchronized
        if (record.state != AudioRecord.STATE_INITIALIZED) throw captureFailure(record, "MICROPHONE_UNINITIALIZED", "capture_initialization", "The microphone could not initialize. Check your audio route.", retryable = true)
        if (allowPlayback) {
            echoCanceler = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId) else null
            val echo = echoCanceler
            if (echo == null || !echo.hasControl() || echo.setEnabled(true) != 0 || !echo.enabled) {
                throw captureFailure(record, "MICROPHONE_ECHO_UNAVAILABLE", "echo_configuration", "Hands-free interruption needs echo cancellation on this audio route. Tap the microphone to interrupt.")
            }
            noiseSuppressor = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.also { it.setEnabled(true) } else null
        }
        try {
            record.startRecording()
        } catch (failure: SecurityException) {
            diagnosticProbe?.permission(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            throw failure
        } catch (failure: IllegalStateException) {
            throw captureFailure(record, "MICROPHONE_START_FAILED", "capture_start", "The microphone did not start recording. Check microphone access.", retryable = true, cause = failure)
        }
        if (stopped) return@synchronized
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw captureFailure(record, "MICROPHONE_NOT_RECORDING", "capture_start", "The microphone did not start recording. Check microphone access.", retryable = true)
        requireCaptureNotSilenced(record, "capture_start", beforeRead = true)
    }

    override fun read(): FloatArray {
        if (focusLost) throw focusLostFailure()
        if (stopped) return FloatArray(0)
        val record = synchronized(lock) { recorder } ?: return FloatArray(0)
        requireCaptureNotSilenced(record, "capture_read", beforeRead = true)
        if (stopped) return FloatArray(0)
        val count = record.read(buffer, filled, buffer.size - filled, AudioRecord.READ_NON_BLOCKING)
        val observedCount = count.coerceIn(0, buffer.size - filled)
        diagnosticProbe?.read(observedCount, (filled until filled + observedCount).count { buffer[it] != 0.toShort() })
        if (count < 0 && !stopped) {
            val code = when (count) {
                AudioRecord.ERROR_DEAD_OBJECT -> "MICROPHONE_DEAD_OBJECT"
                AudioRecord.ERROR_BAD_VALUE -> "MICROPHONE_BAD_VALUE"
                AudioRecord.ERROR_INVALID_OPERATION -> "MICROPHONE_INVALID_OPERATION"
                else -> "MICROPHONE_READ_FAILED"
            }
            throw captureFailure(record, code, "capture_read", "Microphone capture stopped. Reconnect your audio device and try again.", count, retryable = count == AudioRecord.ERROR_DEAD_OBJECT)
        }
        if (count <= 0) {
            Thread.sleep(10)
            return FloatArray(0)
        }
        requireCaptureNotSilenced(record, "capture_read")
        capturedSamples += count
        filled += count
        if (stopped) return FloatArray(0)
        if (filled < buffer.size) return FloatArray(0)
        filled = 0
        return pool.acquire().also { samples ->
            for (index in buffer.indices) samples[index] = buffer[index] / 32768f
        }
    }

    override fun drainPending(): FloatArray {
        // Called only by the capture thread after its read loop exits.
        val count = filled
        filled = 0
        return FloatArray(count) { index -> buffer[index] / 32768f }
    }

    private fun requireCaptureNotSilenced(record: AudioRecord, phase: String, beforeRead: Boolean = false) {
        // A missing configuration is unknown, not evidence of permission or silencing.
        val silenced = record.activeRecordingConfiguration?.isClientSilenced
        if (!stopped && silenced != null) diagnosticProbe?.inputSilenced(silenced)
        if (silenced != true || stopped) return
        // Android can briefly silence a newly opened input while it transfers the normal
        // recording route. Keep the same recorder and wait once, before reading any PCM.
        if (beforeRead && !waitedForInputRoute && capturedSamples == 0L &&
            manager?.mode == AudioManager.MODE_NORMAL && !manager.isMicrophoneMute
        ) {
            waitedForInputRoute = true
            repeat(8) {
                if (stopped) return
                Thread.sleep(25)
                if (stopped) return
                if (record.activeRecordingConfiguration?.isClientSilenced == false) {
                    diagnosticProbe?.inputSilenced(false)
                    return
                }
            }
        }
        diagnosticProbe?.captureSilenced(
            phase.uppercase(java.util.Locale.ROOT),
            runCatching { manager?.isMicrophoneMute }.getOrNull(),
            VoiceDiagnosticTrace.PlaybackAudioMode.observed(runCatching { manager?.mode }.getOrNull())
        )
        val failure = captureFailure(
            record,
            "MICROPHONE_CAPTURE_SILENCED",
            phase,
            "Android is silencing microphone input. Keep Voice open in the foreground, finish any active call or other recording, and try again.",
            retryable = capturedSamples == 0L
        )
        stop()
        throw failure
    }

    private fun focusLostFailure() = LocalVoiceCaptureException(
        "Voice paused because another app needs audio. Tap to resume when it is available.",
        "MICROPHONE_FOCUS_LOST",
        "audio_focus",
        capturedSamples = capturedSamples,
        audioFocusResult = focusResult
    )

    private fun captureFailure(
        record: AudioRecord,
        code: String,
        phase: String,
        message: String,
        platformCode: Int? = null,
        retryable: Boolean = false,
        cause: Throwable? = null
    ) = LocalVoiceCaptureException(
        message, code, phase, platformCode, record.state, record.recordingState, retryable, cause, capturedSamples, focusResult
    )

    override fun stop() {
        // Publish cancellation before waiting for a platform call held by start().
        stopped = true
        synchronized(lock) { runCatching { recorder?.stop() } }
    }

    override fun close() = synchronized(lock) {
        stop()
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        echoCanceler = null
        noiseSuppressor = null
        runCatching { recorder?.release() }
        recorder = null
        focus?.let { manager?.abandonAudioFocusRequest(it) }
        focus = null
    }
}

private fun createAudioRecorder(context: Context, bufferSize: Int, echoCancellation: Boolean = false): AudioRecord {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        throw SecurityException("Microphone permission was revoked before recorder creation.")
    }
    return AudioRecord.Builder()
        .setAudioSource(if (echoCancellation) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION)
        .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(bufferSize)
        .build()
}
