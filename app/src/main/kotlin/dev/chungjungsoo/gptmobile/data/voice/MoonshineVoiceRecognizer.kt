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

    private class CaptureRun(val listener: VoiceAudio.Listener) {
        val lease = "local-voice:${UUID.randomUUID()}"
        val cancelled = AtomicBoolean()
        val ending = AtomicBoolean()
        val speechDetected = AtomicBoolean()
        val failure = AtomicReference<String?>()
        val audio = ArrayBlockingQueue<FloatArray>(40)

        @Volatile var capture: LocalVoiceCapture? = null

        @Volatile var captureThread: Thread? = null

        @Volatile var cancelSilenceDeadline: (() -> Unit)? = null

        @Volatile var cancelTurnDeadline: (() -> Unit)? = null
        var transcript = ""
    }

    override fun listen(listener: VoiceAudio.Listener) {
        if (closed) return
        if (!LocalVoiceModelStore.supportsLanguage(languageTag)) {
            listener.repairRequired(
                "The installed local speech model supports English. Choose English in Voice settings or select the Android speech service for this language.",
                0,
                VoiceRecoveryAction.LOCAL_MODEL
            )
            return
        }
        if (!hasPermission()) {
            listener.repairRequired("Microphone permission is needed for voice chat.", 0, VoiceRecoveryAction.MICROPHONE_PERMISSION)
            return
        }
        if (active != null) return
        val run = CaptureRun(listener)
        if (!VoiceMicrophoneLease.acquire(run.lease)) {
            listener.retryableFailure("The previous voice session is releasing the microphone. Try again shortly.", 500)
            return
        }
        active = run
        try {
            worker.execute { recognize(run) }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            active = null
            VoiceMicrophoneLease.release(run.lease)
            listener.error("Local speech has closed. Reopen Voice to continue.")
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
            decoder = decoderFactory()
            decoder.start(modelDirectory()) { event ->
                if (!run.cancelled.get()) {
                    when (event) {
                        LocalSpeechEvent.Speech -> {
                            run.speechDetected.set(true)
                            run.cancelSilenceDeadline?.invoke()
                        }
                        is LocalSpeechEvent.Partial -> {
                            run.transcript = event.text.take(8000)
                            deliver(run) { run.listener.partial(event.text.take(8000)) }
                        }
                        is LocalSpeechEvent.Final -> {
                            run.transcript = event.text.take(8000)
                            requestEnd(run)
                        }
                    }
                }
            }
            if (run.cancelled.get()) return
            val capture = captureFactory()
            run.capture = capture
            val recording = Thread({ record(run, capture) }, "LocalVoiceCapture").apply { isDaemon = true }
            run.captureThread = recording
            recording.start()
            while (!run.cancelled.get() && run.failure.get() == null && (!run.ending.get() || run.audio.isNotEmpty())) {
                val samples = run.audio.poll(100, TimeUnit.MILLISECONDS) ?: continue
                decoder.accept(samples)
            }
            requestEnd(run)
            // stop is capture-only. It never calls native stop/free from this thread's callers.
            joinCapture(run)
            if (!run.cancelled.get() && run.failure.get() == null) decoder.finish()
        } catch (_: SecurityException) {
            run.failure.compareAndSet(null, "Microphone permission changed. Allow microphone access in Android settings and try again.")
        } catch (_: LinkageError) {
            run.failure.compareAndSet(null, "The local speech runtime could not load on this device. Select the Android speech service or update the app.")
        } catch (failure: Exception) {
            run.failure.compareAndSet(null, if (failure is LocalVoiceCaptureException) failure.message else "Local speech stopped unexpectedly. Your captured text is retained; tap to try again.")
        } finally {
            run.ending.set(true)
            run.capture?.stop()
            joinCapture(run)
            run.cancelSilenceDeadline?.invoke()
            run.cancelTurnDeadline?.invoke()
            // All decoder calls, including release, are on this serial worker, after accept/finish returned.
            runCatching { decoder?.close() }
            if (modelAcquired) releaseModel()
            run.audio.clear()
            VoiceMicrophoneLease.release(run.lease)
            deliver(run) {
                active = null
                when {
                    missingModel -> run.listener.repairRequired("Install the English local speech model in Voice settings to use offline voice chat.", 0, VoiceRecoveryAction.LOCAL_MODEL)
                    run.failure.get() != null -> run.listener.error(requireNotNull(run.failure.get()))
                    run.transcript.isBlank() -> run.listener.retryableFailure("No speech was detected. Tap the microphone and try again.", 500)
                    else -> run.listener.final(run.transcript)
                }
            }
        }
    }

    private fun record(run: CaptureRun, capture: LocalVoiceCapture) {
        try {
            if (run.cancelled.get() || run.ending.get()) return
            capture.start()
            if (run.cancelled.get() || run.ending.get()) return
            run.cancelSilenceDeadline = schedule(12_000) {
                if (!run.speechDetected.get()) {
                    run.failure.compareAndSet(null, "No speech was detected. Tap the microphone and try again.")
                    requestEnd(run)
                }
            }
            run.cancelTurnDeadline = schedule(30_000) { requestEnd(run) }
            deliver(run) { run.listener.ready() }
            while (!run.cancelled.get() && !run.ending.get()) {
                val samples = capture.read()
                if (samples.isEmpty()) continue
                if (!run.audio.offer(samples)) {
                    run.failure.compareAndSet(null, "Local speech could not keep up with the microphone. Your captured text is retained; close other heavy apps and try again.")
                    requestEnd(run)
                    break
                }
                val rms = sqrt(samples.sumOf { (it * it).toDouble() } / samples.size).toFloat()
                deliver(run) { run.listener.level((rms * 4).coerceIn(0f, 1f)) }
            }
        } catch (_: SecurityException) {
            run.failure.compareAndSet(null, "Microphone permission changed. Allow microphone access and try again.")
            requestEnd(run)
        } catch (failure: Exception) {
            if (!run.cancelled.get() && !run.ending.get()) {
                run.failure.compareAndSet(null, failure.message?.takeIf { failure is LocalVoiceCaptureException } ?: "The microphone could not record audio. Check your audio route and try again.")
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
    }
}

internal sealed interface LocalSpeechEvent {
    data object Speech : LocalSpeechEvent
    data class Partial(val text: String) : LocalSpeechEvent
    data class Final(val text: String) : LocalSpeechEvent
}

internal interface LocalSpeechDecoder {
    fun start(directory: File, events: (LocalSpeechEvent) -> Unit)
    fun accept(samples: FloatArray)
    fun finish()
    fun close()
}

/** Uses only the file-based API verified in Maven 0.1.5. A fresh stream cannot inherit prior line IDs. */
private class MoonshineDecoder : LocalSpeechDecoder {
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
        fun text(): String = lines.values.filter(String::isNotBlank).joinToString(" ").take(8000)
        transcriber.addListener { event ->
            when (event) {
                is TranscriptEvent.LineStarted -> events(LocalSpeechEvent.Speech)
                is TranscriptEvent.LineTextChanged -> {
                    lines[event.line.id] = event.line.text.orEmpty()
                    events(LocalSpeechEvent.Partial(text()))
                }
                is TranscriptEvent.LineCompleted -> {
                    lines[event.line.id] = event.line.text.orEmpty()
                    events(LocalSpeechEvent.Final(text()))
                }
                is TranscriptEvent.Error -> throw IOException("Local decoder failed.", event.cause)
            }
        }
        transcriber.loadFromFiles(directory.absolutePath, JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING)
        transcriber.start()
    }

    override fun accept(samples: FloatArray) = transcriber.addAudio(samples, 16_000)
    override fun finish() = transcriber.stop()
    override fun close() = transcriber.close()
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
