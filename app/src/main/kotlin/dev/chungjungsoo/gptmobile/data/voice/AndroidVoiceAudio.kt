package dev.chungjungsoo.gptmobile.data.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import java.util.Locale

/** Created only in an enabled, visible voice session. Audio is never recorded to an app file. */
class AndroidVoiceAudio internal constructor(
    private val context: Context,
    private val speechWork: java.util.concurrent.Executor,
    private val speechFactory: (Context, TextToSpeech.OnInitListener) -> TextToSpeech
) : VoiceAudio {
    internal constructor(context: Context, speechFactory: (Context, TextToSpeech.OnInitListener) -> TextToSpeech) :
        this(context, java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task, "VoiceSpeech").apply { isDaemon = true } }, speechFactory)
    constructor(context: Context) : this(context, { owner, listener -> TextToSpeech(owner, listener) })

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var speechReady = false
    private var speechGeneration = 0L

    @Volatile private var closed = false

    @Volatile private var generation = 0L
    private var pendingSpeech: (() -> Unit)? = null
    private var playbackError: ((String) -> Unit)? = null
    companion object {
        private val recognitionGate = RecognitionStartGate()
    }
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private val sessionId = java.util.UUID.randomUUID().toString()
    private var routeMode: Int? = null
    var routeChanged: (() -> Unit)? = null
    private var knownDevices: Set<Int>? = null
    private val routeCallback = object : AudioDeviceCallback() {
        private fun changed() {
            val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS).orEmpty().map { it.id }.toSet()
            val previous = knownDevices
            knownDevices = devices
            if (previous != null && devices != previous && !closed) {
                stop()
                routeChanged?.invoke()
            }
        }
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = changed()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = changed()
    }
    init {
        audioManager?.registerAudioDeviceCallback(routeCallback, main)
    }
    private data class SpeechConfiguration(val language: String, val voice: String?, val online: Boolean, val rate: Float, val communication: Boolean)

    // Only the serial speech worker touches the configuration cache or calls TTS Binder APIs.
    private var configuredEngine: TextToSpeech? = null
    private var configuredSpeech: SpeechConfiguration? = null

    @Volatile private var installedVoices: Set<android.speech.tts.Voice> = emptySet()
    private var operation: Pair<String, Long>? = null
    private fun started(kind: String, ticket: Long) {
        operation = kind to ticket
        event("${kind}_start", ticket)
    }
    private fun terminal(reason: String) {
        operation?.let { (kind, ticket) -> event("${kind}_terminal · outcome=$reason", ticket) }
        operation = null
    }
    private val mutableVoices = kotlinx.coroutines.flow.MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val voiceChoices: kotlinx.coroutines.flow.StateFlow<List<Pair<String, String>>> = mutableVoices
    private fun eligibleVoices(voices: Collection<android.speech.tts.Voice>, language: String = languageTag, online: Boolean = allowOnlineSpeech) = voices
        .filter { (online || !it.isNetworkConnectionRequired) && it.locale.language == Locale.forLanguageTag(language).language && "notInstalled" !in it.features.orEmpty() }
        .sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenByDescending { it.locale.toLanguageTag() == language }.thenBy { it.latency }.thenBy { it.name })
    fun voices(): List<Pair<String, String>> = eligibleVoices(installedVoices).take(12).mapIndexed { index, voice ->
        voice.name to "Voice ${index + 1} · ${voice.locale.displayName} · ${if (voice.quality >= android.speech.tts.Voice.QUALITY_HIGH) "High quality" else "Standard quality"}${if (voice.isNetworkConnectionRequired) " · Online" else " · On device"}"
    }

    fun prepareVoices() {
        if (closed) return
        if (tts == null) initializeSpeech()
        val engine = tts?.takeIf { speechReady } ?: return
        val version = speechGeneration
        speechWork.execute {
            val catalog = runCatching { engine.voices.orEmpty().toSet() }.getOrDefault(emptySet())
            main.post {
                if (!closed && version == speechGeneration) {
                    installedVoices = catalog
                    mutableVoices.value = voices()
                }
            }
        }
    }
    private fun event(name: String, ticket: Long = generation) = dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("VoiceTiming", "$name · session=$sessionId · attempt=$ticket · elapsedMs=${android.os.SystemClock.elapsedRealtime()}")
    private var watchdog: Runnable? = null
    var languageTag: String = Locale.getDefault().toLanguageTag()
    var allowOnlineSpeech: Boolean = false
    var speechRate: Float = 1f
    var selectedVoice: String? = null
    val processingLocation: String get() = if (allowOnlineSpeech) "System speech service may use the network" else "On device speech only"
    fun routeName(): String = runCatching { audioManager?.communicationDevice?.productName?.toString() }.getOrNull() ?: "System audio route"
    fun routes(): List<Pair<Int, String>> = audioManager?.availableCommunicationDevices.orEmpty().map { it.id to it.productName.toString() }
    fun selectRoute(id: Int): Boolean = runCatching {
        stop()
        val device = audioManager?.availableCommunicationDevices?.firstOrNull { it.id == id } ?: return@runCatching false
        if (routeMode == null) routeMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.setCommunicationDevice(device)
    }.getOrDefault(false)
    override fun retryAfterMillis(): Long = recognitionGate.remainingMillis()
    private fun armWatchdog(timeout: Long, fail: () -> Unit) {
        watchdog?.let(main::removeCallbacks)
        watchdog = Runnable { fail() }.also { main.postDelayed(it, timeout) }
    }

    override fun listen(listener: VoiceAudio.Listener) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed || recognizer != null) return
        stop()
        if (!recognitionGate.acquire(sessionId)) {
            listener.failure("Speech service is busy. Your chat is still available.", recognitionGate.remainingMillis())
            return
        }
        if (closed) {
            recognitionGate.release(sessionId)
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            recognitionGate.release(sessionId)
            listener.error("Microphone permission is needed for voice chat.")
            return
        }
        val ticket = generation
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context) && !SpeechRecognizer.isRecognitionAvailable(context)) {
            recognitionGate.release(sessionId)
            listener.error("Speech recognition is unavailable. Install or enable a speech service in Android settings.")
            return
        }
        try {
            val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(context) && !allowOnlineSpeech
            if (!onDevice && !allowOnlineSpeech) {
                recognitionGate.release(sessionId)
                listener.error("On device speech is unavailable. Choose a compatible speech service, or explicitly allow online speech in Voice settings.")
                return
            }
            val engine = if (onDevice) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            recognizer = engine
            fun active() = !closed && ticket == generation
            engine.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (active()) {
                        watchdog?.let(main::removeCallbacks)
                        event("recognition_ready")
                        listener.ready()
                    }
                }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) {
                    if (active()) listener.level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    if (active()) {
                        listener.ending()
                        armWatchdog(8000) {
                            if (active()) {
                                terminal("timeout_final")
                                stop()
                                listener.error("Speech did not finish. Retry microphone or continue in chat.")
                            }
                        }
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onPartialResults(partialResults: Bundle?) {
                    if (active()) listener.partial(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                }
                override fun onResults(results: Bundle?) {
                    if (!active()) return
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) recognitionGate.succeeded(sessionId)
                    event("recognition_final", ticket)
                    terminal("completed")
                    stop()
                    listener.final(text)
                }
                override fun onError(error: Int) {
                    if (!active()) return
                    event("recognition_error_$error", ticket)
                    terminal("error_$error")
                    recognitionGate.failed(error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS, sessionId)
                    stop()
                    dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("VoiceRecognition", "recognition_error=$error · locale=$languageTag · onDevice=$onDevice", "W")
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        listener.final("")
                        return
                    }
                    val failure: (String, Long) -> Unit = if (error in setOf(SpeechRecognizer.ERROR_TOO_MANY_REQUESTS, SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT)) listener::retryableFailure else listener::failure
                    failure(
                        when (error) {
                            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Speech service is busy. Your chat is still available."
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech service is busy. Wait a moment before trying again."
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn’t catch that. Retry microphone when you are ready."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed for voice chat."
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The speech service could not connect. Tap to try again."
                            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "This speech language is unavailable. Review your Android speech settings."
                            else -> "The speech service stopped. Retry microphone when you are ready."
                        },
                        recognitionGate.remainingMillis()
                    )
                }
            })
            armWatchdog(15_000) {
                if (active()) {
                    terminal("timeout_ready")
                    stop()
                    listener.error("Microphone did not become ready. Retry or check the speech service.")
                }
            }
            started("recognition", ticket)
            engine.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
            )
        } catch (failure: Exception) {
            event("recognition_start_failed_${failure.javaClass.simpleName}", ticket)
            terminal("start_failed")
            recognitionGate.failed(false, sessionId)
            stop()
            listener.error("Could not open the microphone. Check Android permissions and your speech service.")
        }
    }

    override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) {
        stop()
        if (closed) return
        val ticket = generation
        playbackError = error
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({ change ->
                if (change < 0 && !closed && ticket == generation) {
                    stop()
                    error("Audio paused because another app needs the speaker.")
                }
            }, main).build()
        if (runCatching { audioManager?.requestAudioFocus(request) }.getOrNull() != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            error("Audio output is unavailable. Your answer is available in captions.")
            return
        }
        focus = request
        started("tts", ticket)
        fun fail(message: String) {
            main.post {
                if (!closed && ticket == generation) {
                    terminal("failed")
                    stop()
                    error(message)
                }
            }
        }
        val config = SpeechConfiguration(languageTag, selectedVoice, allowOnlineSpeech, speechRate.coerceIn(.5f, 2f), routeMode != null)
        val play: () -> Unit = play@{
            val engine = tts ?: return@play
            armWatchdog(15_000) {
                if (!closed && ticket == generation) {
                    terminal("timeout_start")
                    stop()
                    error("Speech playback did not start. Your answer remains in chat.")
                }
            }
            speechWork.execute work@{
                try {
                    if (closed || ticket != generation) return@work
                    if (configuredEngine !== engine || configuredSpeech != config) {
                        configuredSpeech = null
                        engine.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(if (config.communication) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                        )
                        val locale = Locale.forLanguageTag(config.language)
                        if (engine.setLanguage(locale) < 0) {
                            fail("A voice for this language is not installed. Captions are still available.")
                            return@work
                        }
                        val voices = engine.voices.orEmpty()
                        installedVoices = voices.toSet()
                        val eligible = eligibleVoices(voices, config.language, config.online)
                        val voice = eligible.firstOrNull { it.name == config.voice } ?: eligible.firstOrNull()
                        if (!config.online && voice == null) {
                            fail("No offline voice is installed for this language. Your answer remains in chat.")
                            return@work
                        }
                        if (voice != null && engine.setVoice(voice) == TextToSpeech.ERROR) {
                            fail("The selected voice is unavailable. Choose an installed voice; captions remain available.")
                            return@work
                        }
                        if (engine.setSpeechRate(config.rate) == TextToSpeech.ERROR) {
                            fail("The speech engine could not apply this rate. Captions remain available.")
                            return@work
                        }
                        configuredEngine = engine
                        configuredSpeech = config
                    }
                    // Cancellation/configuration changes during a blocking Binder call must not start old audio.
                    if (closed || ticket != generation) return@work
                    val spoken = if (text.length > 12000) text.take(12000) + " The rest of this response is available in the chat." else text
                    val chunks = speechChunks(spoken, minOf(3000, TextToSpeech.getMaxSpeechInputLength()))
                    val finalId = "$ticket:${chunks.lastIndex}"
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            main.post {
                                if (!closed && ticket == generation) {
                                    event("tts_audio_started · utterance=$utteranceId", ticket)
                                    val index = utteranceId?.substringAfterLast(':')?.toIntOrNull()
                                    val length = index?.let(chunks::getOrNull)?.length ?: 3000
                                    armWatchdog((length * 150L + 15_000).coerceIn(30_000, 480_000)) {
                                        if (!closed && ticket == generation) {
                                            terminal("timeout_playback")
                                            stop()
                                            error("Speech playback stopped responding. Your answer remains in chat.")
                                        }
                                    }
                                }
                            }
                        }
                        override fun onDone(utteranceId: String?) {
                            main.post {
                                if (!closed && ticket == generation) {
                                    event("tts_utterance_completed · utterance=$utteranceId", ticket)
                                    if (utteranceId == finalId) {
                                        terminal("completed")
                                        stop()
                                        complete()
                                    }
                                }
                            }
                        }

                        @Deprecated("Platform compatibility callback")
                        override fun onError(utteranceId: String?) = fail("Could not play this response. Turn off the speaker to continue with captions.")
                    })
                    for ((index, chunk) in chunks.withIndex()) {
                        if (closed || ticket != generation) return@work
                        if (engine.speak(chunk, TextToSpeech.QUEUE_ADD, null, "$ticket:$index") == TextToSpeech.ERROR) {
                            fail("Could not play this response. Captions are still available.")
                            break
                        }
                    }
                } catch (_: Exception) {
                    fail("The speech engine stopped. Turn off the speaker or try again.")
                }
            }
        }
        if (speechReady) {
            play()
            return
        }
        pendingSpeech = play
        armWatchdog(10_000) {
            if (!closed && ticket == generation && !speechReady) {
                terminal("timeout_initialization")
                stop()
                releaseSpeech()
                error("The speech engine did not initialize. Your answer remains in chat.")
            }
        }
        initializeSpeech()
    }

    private fun initializeSpeech() {
        if (tts != null || closed) return
        val initialization = ++speechGeneration
        try {
            tts = speechFactory(context) { status ->
                main.post {
                    if (closed || initialization != speechGeneration) return@post
                    speechReady = status == TextToSpeech.SUCCESS
                    val pending = pendingSpeech
                    pendingSpeech = null
                    if (speechReady) {
                        prepareVoices()
                        if (pending != null) {
                            watchdog?.let(main::removeCallbacks)
                            watchdog = null
                            pending.invoke()
                        }
                    } else {
                        val failed = playbackError
                        playbackError = null
                        releaseSpeech()
                        if (pending != null) {
                            terminal("initialization_failed")
                            stop()
                            failed?.invoke("Text-to-speech is unavailable. Install a voice or continue with captions.")
                        }
                    }
                }
            }
        } catch (_: Exception) {
            val failed = playbackError
            pendingSpeech = null
            playbackError = null
            releaseSpeech()
            failed?.invoke("Could not start text-to-speech. Install a voice or continue with captions.")
        }
    }

    private fun releaseSpeech() {
        speechGeneration++
        speechReady = false
        val previous = tts
        tts = null
        installedVoices = emptySet()
        mutableVoices.value = emptyList()
        if (previous != null) {
            speechWork.execute {
                if (configuredEngine === previous) {
                    configuredEngine = null
                    configuredSpeech = null
                }
                runCatching { previous.shutdown() }
            }
        }
    }

    override fun stop() {
        if (closed) return
        terminal("interrupted")
        generation++
        watchdog?.let(main::removeCallbacks)
        watchdog = null
        focus?.let { runCatching { audioManager?.abandonAudioFocusRequest(it) } }
        focus = null
        pendingSpeech = null
        playbackError = null
        val previous = recognizer
        recognizer = null
        runCatching { previous?.cancel() }
        runCatching { previous?.destroy() }
        recognitionGate.release(sessionId)
        val speech = tts
        if (speech != null) speechWork.execute { runCatching { speech.stop() } }
    }

    override fun close() {
        if (closed) return
        terminal("disposed")
        stop()
        closed = true
        audioManager?.unregisterAudioDeviceCallback(routeCallback)
        if (routeMode != null) {
            audioManager?.clearCommunicationDevice()
            routeMode?.let { audioManager?.mode = it }
        }
        releaseSpeech()
        (speechWork as? java.util.concurrent.ExecutorService)?.shutdown()
        main.removeCallbacksAndMessages(null)
    }
}
