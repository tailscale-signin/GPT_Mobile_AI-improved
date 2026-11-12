package dev.chungjungsoo.gptmobile.data.voice

import ai.moonshine.voice.JNI
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriberOption
import ai.moonshine.voice.TranscriptEvent
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

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
) : VoiceRecognitionInput {
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
        decoderFactory = ::MoonshineDecoder,
        captureFactory = { AndroidVoiceCapture(context.applicationContext) },
        worker = Executors.newSingleThreadExecutor { task -> Thread(task, "LocalVoiceDecoder").apply { isDaemon = true } }
    )

    @Volatile private var closed = false

    @Volatile private var active: CaptureRun? = null
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

    private class CaptureRun(val listener: VoiceAudio.Listener) {
        val lease = "local-voice:${UUID.randomUUID()}"
        val startedNanos = System.nanoTime()
        val cancelled = AtomicBoolean()
        val ending = AtomicBoolean()
        val speechDetected = AtomicBoolean()
        val partialDetected = AtomicBoolean()
        val acceptedChunks = AtomicInteger()
        val failure = AtomicReference<RecognitionFailure?>()
        val audio = ArrayBlockingQueue<FloatArray>(40)

        @Volatile var capture: LocalVoiceCapture? = null

        @Volatile var captureThread: Thread? = null

        @Volatile var cancelSilenceDeadline: (() -> Unit)? = null

        @Volatile var cancelTurnDeadline: (() -> Unit)? = null

        @Volatile var transcript = ""
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
        if (!LocalVoiceModelStore.supportsLanguage(languageTag)) {
            listener.failure(
                "The installed local speech model supports English. Choose English in Voice settings or select the Android speech service for this language.",
                "VOICE_RECOGNIZER_LANGUAGE_UNAVAILABLE",
                recoveryAction = VoiceRecoveryAction.LOCAL_MODEL
            )
            return
        }
        if (!hasPermission()) {
            listener.failure("Microphone permission is needed for voice chat.", "VOICE_MICROPHONE_PERMISSION_REQUIRED", recoveryAction = VoiceRecoveryAction.MICROPHONE_PERMISSION)
            return
        }
        if (active != null) return
        val run = CaptureRun(listener)
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
            run.capture?.stop()
            deliver(run) { run.listener.ending() }
        }
    }

    private fun recognize(run: CaptureRun) {
        engineEvent(run, "native_gate_wait")
        VoiceNativeEngineGate.exclusive { recognizeExclusive(run) }
    }

    private fun recognizeExclusive(run: CaptureRun) {
        var decoder: LocalSpeechDecoder? = null
        var missingModel = false
        var modelAcquired = false
        try {
            if (run.cancelled.get()) return
            if (!modelsReady()) {
                missingModel = true
                return
            }
            modelAcquired = true
            if (run.cancelled.get()) return
            engineEvent(run, "native_gate_acquired")
            engineEvent(run, "decoder_create")
            decoder = decoderFactory()
            engineEvent(run, "model_load_start")
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
                        is LocalSpeechEvent.Failure -> {
                            recordFailure(run, event.code, "Local speech stopped unexpectedly. Your captured text is retained; tap to try again.")
                            engineEvent(run, "decoder_callback_failure")
                            requestEnd(run)
                        }
                    }
                }
            }
            engineEvent(run, "model_load_complete")
            if (run.cancelled.get()) return
            val capture = captureFactory()
            run.capture = capture
            val recording = Thread({ record(run, capture) }, "LocalVoiceCapture").apply { isDaemon = true }
            run.captureThread = recording
            recording.start()
            while (!run.cancelled.get() && run.failure.get() == null && (!run.ending.get() || run.audio.isNotEmpty())) {
                val samples = run.audio.poll(100, TimeUnit.MILLISECONDS) ?: continue
                decoder.accept(samples)
                run.acceptedChunks.incrementAndGet()
            }
            requestEnd(run)
            // stop is capture-only. It never calls native stop/free from this thread's callers.
            joinCapture(run)
            if (!run.cancelled.get() && run.failure.get() == null) {
                engineEvent(run, "decoder_finish_start")
                decoder.finish()
                engineEvent(run, "decoder_finish_complete")
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
            run.capture?.stop()
            joinCapture(run)
            run.cancelSilenceDeadline?.invoke()
            run.cancelTurnDeadline?.invoke()
            // All decoder calls, including release, are on this serial worker, after accept/finish returned.
            if (decoder != null) engineEvent(run, "decoder_close_start")
            runCatching { decoder?.close() }
                .onFailure { failure ->
                    recordFailure(run, "DECODER_CLOSE_FAILED", "Local speech could not finish releasing its model. Reopen Voice or select the Android speech service.", failure)
                }
            if (decoder != null) engineEvent(run, "decoder_close_complete")
            if (modelAcquired) releaseModel()
            if (run.failure.get() != null) engineEvent(run, "failure")
            if (decoder != null) engineEvent(run, "released")
            run.audio.clear()
            VoiceMicrophoneLease.release(run.lease)
            deliver(run) {
                active = null
                when {
                    missingModel -> run.listener.failure("Install the English local speech model in Voice settings to use offline voice chat.", "VOICE_MODEL_REQUIRED", recoveryAction = VoiceRecoveryAction.LOCAL_MODEL)
                    run.failure.get() != null -> deliverFailure(run)
                    run.transcript.isBlank() -> run.listener.failure("No speech was detected. Tap the microphone and try again.", "VOICE_NO_SPEECH", retryMillis = 500, retryable = true)
                    else -> run.listener.final(run.transcript)
                }
            }
        }
    }

    private fun deliverFailure(run: CaptureRun) {
        val failure = requireNotNull(run.failure.get())
        val failureCode = failure.code
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
            else -> "VOICE_RECOGNIZER_FAILED"
        }
        run.listener.failure(
            failure.message,
            code,
            retryMillis = if (failureCode == "NO_SPEECH") 500 else 0,
            retryable = failureCode == "NO_SPEECH",
            recoveryAction = repair,
            cause = failure.cause
        )
    }

    private fun engineEvent(run: CaptureRun, stage: String) {
        val durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - run.startedNanos)
        val message = "moonshine_$stage · stage=$stage · nativeOperationId=${run.lease} · engineId=${run.lease} · durationMs=$durationMillis · cancelled=${run.cancelled.get()} · ending=${run.ending.get()} · acceptedChunks=${run.acceptedChunks.get()} · queuedChunks=${run.audio.size} · transcriptChars=${run.transcript.length} · failureCode=${run.failure.get()?.code ?: "none"}"
        if (stage in nativeBoundaryStages) {
            // Persist before entering the native call; a SIGABRT cannot drain the async queue.
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.checkpoint("VoiceEngine", message)
        } else {
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("VoiceEngine", message)
        }
    }

    private fun record(run: CaptureRun, capture: LocalVoiceCapture) {
        try {
            if (run.cancelled.get() || run.ending.get()) return
            capture.start()
            if (run.cancelled.get() || run.ending.get()) return
            run.cancelSilenceDeadline = schedule(12_000) {
                if (!run.speechDetected.get()) {
                    recordFailure(run, "NO_SPEECH", "No speech was detected. Tap the microphone and try again.")
                    requestEnd(run)
                }
            }
            run.cancelTurnDeadline = schedule(30_000) { requestEnd(run) }
            deliver(run) { run.listener.ready() }
            while (!run.cancelled.get() && !run.ending.get()) {
                val samples = capture.read()
                if (samples.isEmpty()) continue
                if (!run.audio.offer(samples)) {
                    recordFailure(run, "AUDIO_QUEUE_OVERFLOW", "Local speech could not keep up with the microphone. Your captured text is retained; close other heavy apps and try again.")
                    requestEnd(run)
                    break
                }
                val rms = sqrt(samples.sumOf { (it * it).toDouble() } / samples.size).toFloat()
                deliver(run) { run.listener.level((rms * 4).coerceIn(0f, 1f)) }
            }
        } catch (failure: SecurityException) {
            recordFailure(run, "MICROPHONE_PERMISSION_CHANGED", "Microphone permission changed. Allow microphone access and try again.", failure)
            requestEnd(run)
        } catch (failure: OutOfMemoryError) {
            recordFailure(run, "NATIVE_MEMORY_PRESSURE", "Local speech could not record because memory is low. Close other heavy apps or select the Android speech service.", failure)
            requestEnd(run)
        } catch (failure: Exception) {
            if (!run.cancelled.get() && !run.ending.get()) {
                recordFailure(
                    run,
                    if (failure is LocalVoiceCaptureException) "MICROPHONE_CAPTURE_FAILED" else "MICROPHONE_READ_FAILED",
                    failure.message?.takeIf { failure is LocalVoiceCaptureException } ?: "The microphone could not record audio. Check your audio route and try again.",
                    failure
                )
                requestEnd(run)
            }
        } finally {
            runCatching { capture.close() }
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
        active = null
        run.capture?.stop()
        run.cancelSilenceDeadline?.invoke()
        run.cancelTurnDeadline?.invoke()
        // The lease remains held until the worker finishes native cleanup; no overlapping mic starts.
    }

    override fun close() {
        if (closed) return
        closed = true
        stop()
        worker.shutdown()
    }

    companion object {
        private val main by lazy { Handler(Looper.getMainLooper()) }
        private val nativeBoundaryStages = setOf("model_load_start", "decoder_finish_start", "decoder_close_start")
    }
}

internal sealed interface LocalSpeechEvent {
    data object Speech : LocalSpeechEvent
    data class Partial(val text: String) : LocalSpeechEvent
    data class Final(val text: String) : LocalSpeechEvent
    data class Failure(val code: String) : LocalSpeechEvent
}

internal interface LocalSpeechDecoder {
    fun start(directory: File, events: (LocalSpeechEvent) -> Unit)
    fun accept(samples: FloatArray)
    fun finish()
    fun close()
}

/** Uses only the file-based API verified in Maven 0.1.5. A fresh stream cannot inherit prior line IDs. */
private class MoonshineDecoder : LocalSpeechDecoder {
    private val open = AtomicBoolean(true)
    private val transcriber = Transcriber(
        listOf(
            TranscriberOption("return_audio_data", "false"),
            TranscriberOption("log_output_text", "false"),
            TranscriberOption("identify_speakers", "false"),
            TranscriberOption("word_timestamps", "false"),
            TranscriberOption("vad_max_segment_duration", "30")
        )
    )

    override fun start(directory: File, events: (LocalSpeechEvent) -> Unit) {
        val lines = linkedMapOf<Long, String>()
        val lineLock = Any()
        fun emit(event: LocalSpeechEvent) {
            try {
                events(event)
            } catch (_: Exception) {
                // No application exception may escape a Moonshine/JNI callback thread.
                if (event !is LocalSpeechEvent.Failure) runCatching { events(LocalSpeechEvent.Failure("MOONSHINE_LISTENER_ERROR")) }
            }
        }
        fun update(id: Long, value: String): String = synchronized(lineLock) {
            lines[id] = value
            lines.values.filter(String::isNotBlank).joinToString(" ").take(8000)
        }
        transcriber.addListener { event ->
            if (!open.get()) return@addListener
            when (event) {
                is TranscriptEvent.LineStarted -> emit(LocalSpeechEvent.Speech)
                is TranscriptEvent.LineTextChanged -> emit(LocalSpeechEvent.Partial(update(event.line.id, event.line.text.orEmpty())))
                is TranscriptEvent.LineCompleted -> emit(LocalSpeechEvent.Final(update(event.line.id, event.line.text.orEmpty())))
                // A listener can run on Moonshine's native callback thread. Never throw across
                // that JNI boundary: signal the serial owner so it can stop and release safely.
                is TranscriptEvent.Error -> emit(LocalSpeechEvent.Failure("MOONSHINE_CALLBACK_ERROR"))
            }
        }
        transcriber.loadFromFiles(directory.absolutePath, JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING)
        transcriber.start()
    }

    override fun accept(samples: FloatArray) = transcriber.addAudio(samples, 16_000)
    override fun finish() = transcriber.stop()
    override fun close() {
        if (open.compareAndSet(true, false)) transcriber.close()
    }
}

internal interface LocalVoiceCapture {
    fun start()
    fun read(): FloatArray
    fun stop()
    fun close()
}

private class LocalVoiceCaptureException(message: String) : IOException(message)

/** Capture release is owned by the recording thread; stop only unblocks capture. No PCM is persisted. */
private class AndroidVoiceCapture(private val context: Context) : LocalVoiceCapture {
    private val lock = Any()
    private val manager = context.getSystemService(AudioManager::class.java)
    private var recorder: AudioRecord? = null
    private var focus: AudioFocusRequest? = null
    private val buffer = ShortArray(1600)
    private var filled = 0

    @Volatile private var stopped = false

    @Volatile private var focusLost = false

    override fun start() = synchronized(lock) {
        if (stopped) return@synchronized
        // Model loading can outlive the earlier UI permission check.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Microphone permission was revoked before capture started.")
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change ->
                if (change != AudioManager.AUDIOFOCUS_GAIN) {
                    focusLost = true
                    stop()
                }
            }
            .build()
        if (manager?.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) throw LocalVoiceCaptureException("Microphone audio is busy. Finish the other audio session and try again.")
        focus = request
        val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw LocalVoiceCaptureException("This microphone route does not support local speech. Select another audio route.")
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum, 16_000))
            .build()
        recorder = record
        if (record.state != AudioRecord.STATE_INITIALIZED) throw LocalVoiceCaptureException("The microphone could not initialize. Check your audio route.")
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw LocalVoiceCaptureException("The microphone did not start recording. Check microphone access.")
    }

    override fun read(): FloatArray {
        if (focusLost) throw LocalVoiceCaptureException("Voice paused because another app needs audio. Tap to resume when it is available.")
        if (stopped) return FloatArray(0)
        val record = synchronized(lock) { recorder } ?: return FloatArray(0)
        val count = record.read(buffer, filled, buffer.size - filled, AudioRecord.READ_NON_BLOCKING)
        if (count < 0 && !stopped) throw LocalVoiceCaptureException("Microphone capture stopped. Reconnect your audio device and try again.")
        if (count <= 0) {
            Thread.sleep(10)
            return FloatArray(0)
        }
        filled += count
        if (filled < buffer.size) return FloatArray(0)
        filled = 0
        return FloatArray(buffer.size) { buffer[it] / 32768f }
    }

    override fun stop() = synchronized(lock) {
        stopped = true
        runCatching { recorder?.stop() }
        Unit
    }

    override fun close() = synchronized(lock) {
        stop()
        runCatching { recorder?.release() }
        recorder = null
        focus?.let { manager?.abandonAudioFocusRequest(it) }
        focus = null
    }
}
