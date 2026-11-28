package dev.chungjungsoo.gptmobile.data.voice

import ai.moonshine.voice.JNI
import ai.moonshine.voice.TranscriberOption
import ai.moonshine.voice.Transcript
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The 0.1.5 JNI API permits a loaded model with independent stream handles. Using
 * it directly avoids Transcriber's completed-line map retaining IDs across turns.
 * Every call belongs to MoonshineVoiceRecognizer's serial worker/native gate.
 */
internal class MoonshineStreamDecoder(private val native: MoonshineNativeApi = JniMoonshineNativeApi()) : LocalSpeechDecoder {
    private var model = -1
    private var stream = -1
    private var style = VoiceListeningStyle.NATURAL
    private var modelDirectory: String? = null
    private var events: ((LocalSpeechEvent) -> Unit)? = null
    private val lines = linkedMapOf<Long, String>()
    private val words = linkedMapOf<Long, List<VoiceRecognitionWord>>()
    private val completed = mutableSetOf<Long>()
    private var cadence = VoiceRecognitionCadence(style.updateIntervalMillis)
    private var audioSeconds = 0.0
    private var audioFrames = 0L
    private var streamIdentity = 0L
    private var revision = 0L
    private var vocabulary: List<String> = emptyList()
    private var decoded = false

    override val supportsReuse: Boolean get() = true
    override val runtimeEvidence: String get() = "Moonshine 0.1.6 · CPU"
    override fun executionEvidence(): Map<String, String> = mapOf(
        "recognitionRequestedProvider" to "CPU",
        "recognitionActualProvider" to "cpu",
        "recognitionExecutionVerified" to decoded.toString(),
        "recognitionRuntime" to "moonshine-voice-0.1.6",
        "recognitionAcceptedAudioFrames" to audioFrames.toString(),
        "recognitionContextualBias" to vocabulary.isNotEmpty().toString()
    )
    override fun configure(style: VoiceListeningStyle) {
        check(model < 0 || this.style == style) { "Listening style changes require a fresh native model" }
        this.style = style
    }

    override fun configureRecognition(languageTag: String, vocabulary: List<String>) {
        require(LocalVoiceModelStore.supportsLanguage(languageTag)) { "This Moonshine model supports English." }
        // The native C API accepts comma-separated phrases, without an escaping syntax.
        this.vocabulary = vocabulary.filter { ',' !in it && it.none(Char::isISOControl) }.take(128)
    }

    override fun start(directory: File, events: (LocalSpeechEvent) -> Unit) {
        check(stream < 0)
        if (model < 0) {
            model = native.load(directory, style)
            check(model >= 0) { "Moonshine model initialization failed" }
            modelDirectory = directory.absolutePath
        }
        check(modelDirectory == directory.absolutePath)
        checked(native.setKeyterms(model, vocabulary.joinToString(",")), "vocabulary")
        lines.clear()
        words.clear()
        completed.clear()
        cadence = VoiceRecognitionCadence(style.updateIntervalMillis)
        audioSeconds = 0.0
        audioFrames = 0L
        streamIdentity = identities.incrementAndGet()
        revision = 0L
        decoded = false
        this.events = events
        stream = native.createStream(model)
        check(stream >= 0) { "Moonshine stream creation failed" }
        checked(native.start(model, stream), "start")
    }

    override fun accept(samples: FloatArray) {
        check(stream >= 0)
        checked(native.addAudio(model, stream, samples), "audio")
        audioSeconds += samples.size / 16_000.0
        audioFrames += samples.size
        if (!cadence.added(samples.size)) return
        val started = System.nanoTime()
        val transcript = native.transcribe(model, stream, false)
        cadence.completed(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
        publish(checkNotNull(transcript) { "Moonshine transcription failed" })
        decoded = audioFrames > 0
    }

    override fun finish() {
        if (stream < 0) return
        checked(native.stop(model, stream), "stop")
        publish(checkNotNull(native.transcribe(model, stream, true)) { "Moonshine final transcription failed" }, terminal = true)
        decoded = audioFrames > 0
    }

    private fun publish(transcript: Transcript, terminal: Boolean = false) {
        for (line in transcript.lines) {
            if (line.isNew) events?.invoke(LocalSpeechEvent.Speech)
            if (line.hasTextChanged || line.isComplete) {
                val replacement = line.text.orEmpty()
                // A blank final flush must not erase this line while older completed lines
                // make the combined transcript nonempty and evade the capture-level guard.
                if (replacement.isNotBlank() || lines[line.id].isNullOrBlank()) {
                    lines[line.id] = replacement
                    // SDK 0.1.6 word times are absolute in this stream, not relative to line.startTime.
                    words[line.id] = line.words.orEmpty().map { VoiceRecognitionWord(it.word.orEmpty(), it.start.toDouble(), it.end.toDouble()) }
                }
                val text = lines.values.filter(String::isNotBlank).joinToString(" ").take(8000)
                val timed = VoiceRecognitionTranscript.fromWords(
                    text,
                    words.values.flatten(),
                    0.0,
                    streamIdentity,
                    ++revision,
                    false,
                    16_000,
                    audioFrames
                )
                if (line.isComplete && completed.add(line.id)) {
                    val ended = line.startTime.toDouble() + line.duration.toDouble()
                    if (ended.isFinite() && ended >= 0 && ended <= audioSeconds) {
                        events?.invoke(LocalSpeechEvent.SpeechEnd(((audioSeconds - ended) * 1000).toLong()))
                    }
                    if (!terminal) events?.invoke(LocalSpeechEvent.Final(text, timed))
                } else if (!terminal && !line.isComplete && line.hasTextChanged) {
                    events?.invoke(LocalSpeechEvent.Partial(text, timed))
                }
            }
        }
        if (terminal) {
            // Forced decoding may refine word timing for an already completed line ID.
            // Its endpoint notification is not the terminal result after capture drains.
            val text = lines.values.filter(String::isNotBlank).joinToString(" ").take(8000)
            val timed = VoiceRecognitionTranscript.fromWords(text, words.values.flatten(), 0.0, streamIdentity, ++revision, true, 16_000, audioFrames)
            events?.invoke(LocalSpeechEvent.Final(text, timed))
        }
    }

    override fun releaseStream() {
        val previous = stream
        stream = -1
        events = null
        lines.clear()
        words.clear()
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

    companion object {
        private val identities = AtomicLong()

        fun forModel(
            model: VoiceRecognitionModel,
            nativeFactory: (Int, Boolean) -> MoonshineNativeApi = { architecture, timestamps -> JniMoonshineNativeApi(architecture, timestamps) }
        ): MoonshineStreamDecoder {
            require(model.engine == VoiceRecognitionEngine.MOONSHINE)
            return MoonshineStreamDecoder(nativeFactory(requireNotNull(model.nativeArchitecture), model.timingCapability == VoiceRecognitionTimingCapability.WORD))
        }
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
    fun setKeyterms(model: Int, terms: String): Int = JNI.MOONSHINE_ERROR_NONE
}

private class JniMoonshineNativeApi(
    private val architecture: Int = JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING,
    private val wordTimings: Boolean = false
) : MoonshineNativeApi {
    override fun load(directory: File, style: VoiceListeningStyle): Int {
        JNI.ensureLibraryLoaded()
        val options = mutableListOf(
            TranscriberOption("return_audio_data", "false"),
            TranscriberOption("log_output_text", "false"),
            TranscriberOption("identify_speakers", "false"),
            TranscriberOption("word_timestamps", wordTimings.toString()),
            TranscriberOption("transcription_interval", (style.updateIntervalMillis / 1000.0).toString()),
            TranscriberOption("vad_max_segment_duration", "30"),
            TranscriberOption("vad_window_duration", style.vadWindowSeconds.toString())
        )
        // The attention decoder required for timestamps cannot use 0.1.6 speculative verification.
        if (wordTimings) options += TranscriberOption("use_speculative_decoding", "false")
        return JNI.moonshineLoadTranscriberFromFiles(directory.absolutePath, architecture, options.toTypedArray())
    }
    override fun createStream(model: Int) = JNI.moonshineCreateStream(model, 0)
    override fun start(model: Int, stream: Int) = JNI.moonshineStartStream(model, stream)
    override fun addAudio(model: Int, stream: Int, samples: FloatArray) = JNI.moonshineAddAudioToStream(model, stream, samples, 16_000, 0)
    override fun transcribe(model: Int, stream: Int, force: Boolean): Transcript? =
        JNI.moonshineTranscribeStream(model, stream, if (force) JNI.MOONSHINE_FLAG_FORCE_UPDATE else 0)
    override fun stop(model: Int, stream: Int) = JNI.moonshineStopStream(model, stream)
    override fun freeStream(model: Int, stream: Int) = JNI.moonshineFreeStream(model, stream)
    override fun freeModel(model: Int) = JNI.moonshineFreeTranscriber(model)
    override fun setKeyterms(model: Int, terms: String) = JNI.moonshineTranscriberSetKeyterms(model, terms)
}
