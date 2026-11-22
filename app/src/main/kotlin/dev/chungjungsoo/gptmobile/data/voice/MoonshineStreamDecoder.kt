package dev.chungjungsoo.gptmobile.data.voice

import ai.moonshine.voice.JNI
import ai.moonshine.voice.TranscriberOption
import ai.moonshine.voice.Transcript
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The 0.1.5 JNI API permits a loaded model with independent stream handles. Using
 * it directly avoids Transcriber's completed-line map retaining IDs across turns.
 * Every call belongs to MoonshineVoiceRecognizer's serial worker/native gate.
 */
internal class MoonshineStreamDecoder(private val native: MoonshineNativeApi = JniMoonshineNativeApi) : LocalSpeechDecoder {
    private var model = -1
    private var stream = -1
    private var style = VoiceListeningStyle.NATURAL
    private var modelDirectory: String? = null
    private var events: ((LocalSpeechEvent) -> Unit)? = null
    private val lines = linkedMapOf<Long, String>()
    private val completed = mutableSetOf<Long>()
    private var cadence = VoiceRecognitionCadence(style.updateIntervalMillis)
    private var audioSeconds = 0.0

    override val supportsReuse: Boolean get() = true
    override fun configure(style: VoiceListeningStyle) {
        check(model < 0 || this.style == style) { "Listening style changes require a fresh native model" }
        this.style = style
    }

    override fun start(directory: File, events: (LocalSpeechEvent) -> Unit) {
        check(stream < 0)
        if (model < 0) {
            model = native.load(directory, style)
            check(model >= 0) { "Moonshine model initialization failed" }
            modelDirectory = directory.absolutePath
        }
        check(modelDirectory == directory.absolutePath)
        lines.clear()
        completed.clear()
        cadence = VoiceRecognitionCadence(style.updateIntervalMillis)
        audioSeconds = 0.0
        this.events = events
        stream = native.createStream(model)
        check(stream >= 0) { "Moonshine stream creation failed" }
        checked(native.start(model, stream), "start")
    }

    override fun accept(samples: FloatArray) {
        check(stream >= 0)
        checked(native.addAudio(model, stream, samples), "audio")
        audioSeconds += samples.size / 16_000.0
        if (!cadence.added(samples.size)) return
        val started = System.nanoTime()
        val transcript = native.transcribe(model, stream, false)
        cadence.completed(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
        publish(checkNotNull(transcript) { "Moonshine transcription failed" })
    }

    override fun finish() {
        if (stream < 0) return
        checked(native.stop(model, stream), "stop")
        publish(checkNotNull(native.transcribe(model, stream, true)) { "Moonshine final transcription failed" })
    }

    private fun publish(transcript: Transcript) {
        for (line in transcript.lines) {
            if (line.isNew) events?.invoke(LocalSpeechEvent.Speech)
            if (line.hasTextChanged || line.isComplete) {
                lines[line.id] = line.text.orEmpty()
                val text = lines.values.filter(String::isNotBlank).joinToString(" ").take(8000)
                if (line.isComplete && completed.add(line.id)) {
                    val ended = line.startTime.toDouble() + line.duration.toDouble()
                    if (ended.isFinite() && ended >= 0 && ended <= audioSeconds) {
                        events?.invoke(LocalSpeechEvent.SpeechEnd(((audioSeconds - ended) * 1000).toLong()))
                    }
                    events?.invoke(LocalSpeechEvent.Final(text))
                } else if (!line.isComplete && line.hasTextChanged) {
                    events?.invoke(LocalSpeechEvent.Partial(text))
                }
            }
        }
    }

    override fun releaseStream() {
        val previous = stream
        stream = -1
        events = null
        lines.clear()
        completed.clear()
        if (previous >= 0) checked(native.freeStream(model, previous), "release stream")
    }

    override fun close() {
        try {
            releaseStream()
        } finally {
            val previous = model
            model = -1
            modelDirectory = null
            if (previous >= 0) native.freeModel(previous)
        }
    }

    private fun checked(code: Int, operation: String) {
        check(code == JNI.MOONSHINE_ERROR_NONE) { "Moonshine $operation failed (native code $code)" }
    }
}

/** Injectable native boundary keeps lifecycle and repeated line-ID regressions executable on a host. */
internal interface MoonshineNativeApi {
    fun load(directory: File, style: VoiceListeningStyle): Int
    fun createStream(model: Int): Int
    fun start(model: Int, stream: Int): Int
    fun addAudio(model: Int, stream: Int, samples: FloatArray): Int
    fun transcribe(model: Int, stream: Int, force: Boolean): Transcript?
    fun stop(model: Int, stream: Int): Int
    fun freeStream(model: Int, stream: Int): Int
    fun freeModel(model: Int)
}

private object JniMoonshineNativeApi : MoonshineNativeApi {
    override fun load(directory: File, style: VoiceListeningStyle): Int {
        JNI.ensureLibraryLoaded()
        val options = arrayOf(
            TranscriberOption("return_audio_data", "false"),
            TranscriberOption("log_output_text", "false"),
            TranscriberOption("identify_speakers", "false"),
            TranscriberOption("word_timestamps", "false"),
            TranscriberOption("transcription_interval", (style.updateIntervalMillis / 1000.0).toString()),
            TranscriberOption("vad_max_segment_duration", "30"),
            TranscriberOption("vad_window_duration", style.vadWindowSeconds.toString())
        )
        return JNI.moonshineLoadTranscriberFromFiles(directory.absolutePath, JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING, options)
    }
    override fun createStream(model: Int) = JNI.moonshineCreateStream(model, 0)
    override fun start(model: Int, stream: Int) = JNI.moonshineStartStream(model, stream)
    override fun addAudio(model: Int, stream: Int, samples: FloatArray) = JNI.moonshineAddAudioToStream(model, stream, samples, 16_000, 0)
    override fun transcribe(model: Int, stream: Int, force: Boolean): Transcript? =
        JNI.moonshineTranscribeStream(model, stream, if (force) JNI.MOONSHINE_FLAG_FORCE_UPDATE else 0)
    override fun stop(model: Int, stream: Int) = JNI.moonshineStopStream(model, stream)
    override fun freeStream(model: Int, stream: Int) = JNI.moonshineFreeStream(model, stream)
    override fun freeModel(model: Int) = JNI.moonshineFreeTranscriber(model)
}
