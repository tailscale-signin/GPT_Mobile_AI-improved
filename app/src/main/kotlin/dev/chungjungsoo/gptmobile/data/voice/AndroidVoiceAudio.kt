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
    private val speechFactory: (Context, TextToSpeech.OnInitListener) -> TextToSpeech,
    private val recognitionService: RecognitionService,
    private val recognitionGate: RecognitionStartGate,
    private val diagnostics: (String, String, String) -> Unit = { tag, message, level -> dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(tag, message, level) }
) : VoiceAudio {
    internal constructor(context: Context, speechWork: java.util.concurrent.Executor, speechFactory: (Context, TextToSpeech.OnInitListener) -> TextToSpeech) :
        this(context, speechWork, speechFactory, AndroidRecognitionService(context), sharedRecognitionGate)
    internal constructor(context: Context, speechFactory: (Context, TextToSpeech.OnInitListener) -> TextToSpeech) :
        this(context, java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task, "VoiceSpeech").apply { isDaemon = true } }, speechFactory)
    constructor(context: Context) : this(context, { owner, listener -> TextToSpeech(owner, listener) })

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var recognizerInstance: Long? = null
    private var recognizerOnDevice: Boolean? = null
    private var recognitionActive = false
    private var cachedRecognitionSupport: Pair<String, RecognitionQualification>? = null

    @Volatile private var tts: TextToSpeech? = null

    private val speechStateLock = Any()

    @Volatile private var speechState = SpeechEngineState.UNINITIALIZED

    @Volatile private var speechGeneration = 0L
    private val speechReady: Boolean get() = speechState == SpeechEngineState.READY || speechState == SpeechEngineState.SPEAKING

    @Volatile private var closed = false

    @Volatile private var generation = 0L
    private var pendingSpeech: (() -> Unit)? = null
    private var playbackError: ((String) -> Unit)? = null
    private var speechInitializationTimeout: Runnable? = null
    private class SpeechInitialization(val generation: Long) {
        val result = java.util.concurrent.atomic.AtomicReference<Int?>()
        val handled = java.util.concurrent.atomic.AtomicBoolean()
        val shutdown = java.util.concurrent.atomic.AtomicBoolean()

        @Volatile var engine: TextToSpeech? = null

        @Volatile var retireAfterInitialization = false
    }
    private var speechInitialization: SpeechInitialization? = null
    companion object {
        private val sharedRecognitionGate = RecognitionStartGate()
        private val nextRecognizerInstance = java.util.concurrent.atomic.AtomicLong()
        private val activeRecognizerOwners = java.util.concurrent.atomic.AtomicInteger()
        private val activeSpeechOwners = java.util.concurrent.atomic.AtomicInteger()
        private const val SPEECH_LIMIT_MESSAGE = "Android's speech service reached a request or connection limit. Automatic retries are paused. Use local voice, retry later, or continue typing in chat."
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
    private val mutableVoiceCatalog = kotlinx.coroutines.flow.MutableStateFlow(SpeechVoiceCatalogState.UNINITIALIZED)
    internal val voiceCatalogState: kotlinx.coroutines.flow.StateFlow<SpeechVoiceCatalogState> = mutableVoiceCatalog
    private var voiceQueryInFlight: Long? = null
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
        if (voiceQueryInFlight == version) return
        voiceQueryInFlight = version
        mutableVoiceCatalog.value = SpeechVoiceCatalogState.CHECKING
        speechWork.execute {
            if (closed || version != speechGeneration || tts !== engine || !speechReady) return@execute
            val catalog = runCatching { engine.voices?.toSet() }.getOrNull()
            main.post {
                if (!closed && version == speechGeneration && tts === engine) {
                    voiceQueryInFlight = null
                    if (catalog == null) {
                        unavailableSpeechCatalog(engine, version)
                        return@post
                    }
                    installedVoices = catalog
                    mutableVoices.value = voices()
                    mutableVoiceCatalog.value = if (catalog.isEmpty()) SpeechVoiceCatalogState.EMPTY else SpeechVoiceCatalogState.AVAILABLE
                }
            }
        }
    }

    private fun unavailableSpeechCatalog(engine: TextToSpeech, version: Long) {
        if (closed || version != speechGeneration || tts !== engine) return
        val failed = playbackError
        val wasPlaying = operation?.first == "tts"
        event("tts_catalog_unavailable")
        // A null/throwing voice query is Android's observable signal that this
        // initialized binder has already disconnected. Calling shutdown() again
        // only performs another failed binder transaction and logs "not bound".
        releaseSpeech(SpeechVoiceCatalogState.UNAVAILABLE, knownUnbound = true)
        if (wasPlaying) {
            terminal("catalog_unavailable")
            stop()
            failed?.invoke("The speech engine disconnected. Review Android speech settings, then recheck spoken replies. Captions remain available.")
        }
    }
    private fun event(name: String, ticket: Long = generation) = diagnostics("VoiceTiming", "$name · incidentId=voice:$sessionId:$ticket · session=$sessionId · attempt=$ticket · elapsedMs=${android.os.SystemClock.elapsedRealtime()}", "I")
    private fun recognitionFailure(code: String, stage: String, dispatched: Boolean, ticket: Long = generation, error: Int? = null) {
        val locale = languageTag.takeIf { it.matches(Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8}){0,4}")) } ?: "und"
        diagnostics(
            "VoiceRecognition",
            "recognition_error=${error ?: "not_reported"} · errorCode=$code · failedStage=$stage · dispatched=$dispatched · incidentId=voice:$sessionId:$ticket · retryAfterMs=${recognitionGate.remainingMillis()} · locale=$locale · onDevice=${!allowOnlineSpeech}",
            "W"
        )
    }
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
        if (closed || recognitionActive) return
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
            recognitionFailure("SPEECH_PERMISSION_REQUIRED", "permission_precheck", false, error = SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            listener.repairRequired("Microphone permission is needed for voice chat.", 0, VoiceRecoveryAction.MICROPHONE_PERMISSION)
            return
        }
        if (!VoiceMicrophoneLease.acquire(sessionId)) {
            recognitionGate.release(sessionId)
            listener.failure("Another voice session is using the microphone. Pause it before trying again.", 500)
            return
        }
        recognitionActive = true
        val ticket = generation
        var failedStage = "service_availability"
        var dispatched = false
        fun failedStart(failure: Exception) {
            event("recognition_start_failed_${failure.javaClass.simpleName}", ticket)
            terminal("start_failed")
            recognitionGate.bindingFailed(sessionId)
            recognitionFailure("SPEECH_SERVICE_START_FAILED", failedStage, dispatched, ticket)
            stop()
            listener.repairRequired("The Android speech service could not start. Review its settings or continue typing in chat.", recognitionGate.remainingMillis(), VoiceRecoveryAction.SPEECH_SERVICE)
        }
        try {
            val onDevice = !allowOnlineSpeech
            if (!recognitionService.available(onDevice)) {
                recognitionGate.bindingFailed(sessionId)
                recognitionFailure("SPEECH_SERVICE_UNAVAILABLE", failedStage, false, ticket)
                stop()
                listener.repairRequired(
                    if (onDevice) {
                        "On device speech is unavailable. Choose a compatible speech service, or explicitly allow online speech in Voice settings. You can continue typing in chat."
                    } else {
                        "Speech recognition is unavailable. Install or enable a speech service in Android settings, or continue typing in chat."
                    },
                    recognitionGate.remainingMillis(),
                    VoiceRecoveryAction.SPEECH_SERVICE
                )
                return
            }
            failedStage = "service_creation"
            if (recognizerOnDevice != onDevice) retireRecognizer()
            val engine = recognizer ?: recognitionService.create(onDevice).also {
                recognizer = it
                recognizerOnDevice = onDevice
                recognizerInstance = nextRecognizerInstance.incrementAndGet()
                event("recognizer_created · engineInstance=$recognizerInstance · activeOwnedEngines=${activeRecognizerOwners.incrementAndGet()} · engineMode=${if (onDevice) "on_device" else "system"}", ticket)
            }
            failedStage = "listener_configuration"
            var becameReady = false
            fun active() = !closed && recognitionActive && ticket == generation && recognizer === engine
            engine.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (active()) {
                        becameReady = true
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
                                recognitionFailure("SPEECH_FINALIZATION_TIMEOUT", "finalization", true, ticket)
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
                    // A terminal result releases capture but keeps this qualified service
                    // for the next turn. VoiceSession and TTS also call stop() here.
                    recognitionActive = false
                    stop()
                    listener.final(text)
                }
                override fun onError(error: Int) {
                    if (!active()) return
                    event("recognition_error_$error", ticket)
                    terminal("error_$error")
                    // Error 10 can denote request throttling or connection capacity.
                    // It always requires deliberate recovery, even after onReady.
                    val bindingFailure = !becameReady && error in setOf(SpeechRecognizer.ERROR_TOO_MANY_REQUESTS, SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
                    if (error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS) {
                        recognitionGate.failed(true, sessionId)
                    } else if (bindingFailure) {
                        recognitionGate.bindingFailed(sessionId)
                    } else {
                        recognitionGate.failed(false, sessionId)
                    }
                    stop()
                    val code = when {
                        error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "SPEECH_RATE_LIMITED"
                        bindingFailure -> "SPEECH_SERVICE_START_FAILED"
                        error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "SPEECH_PERMISSION_REQUIRED"
                        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "SPEECH_SERVICE_BUSY"
                        error == SpeechRecognizer.ERROR_NETWORK -> "SPEECH_NETWORK_FAILED"
                        error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "SPEECH_NETWORK_TIMEOUT"
                        error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_NO_RESULT"
                        error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "SPEECH_LANGUAGE_UNAVAILABLE"
                        else -> "SPEECH_RECOGNITION_FAILED"
                    }
                    recognitionFailure(code, if (becameReady) "recognition" else failedStage, dispatched, ticket, error)
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        listener.final("")
                        return
                    }
                    val repair = when {
                        error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS || bindingFailure -> VoiceRecoveryAction.SPEECH_SERVICE
                        error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceRecoveryAction.MICROPHONE_PERMISSION
                        error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> VoiceRecoveryAction.SPEECH_LANGUAGE
                        else -> null
                    }
                    val failure: (String, Long) -> Unit = when {
                        repair != null -> { message, retry -> listener.repairRequired(message, retry, repair) }
                        error in setOf(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT) -> listener::retryableFailure
                        else -> listener::failure
                    }
                    failure(
                        if (error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS) {
                            SPEECH_LIMIT_MESSAGE
                        } else if (bindingFailure) {
                            "The Android speech service could not start. Check your speech service in Android settings, or continue typing in chat."
                        } else {
                            when (error) {
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech service is busy. Wait a moment before trying again."
                                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn’t catch that. Retry microphone when you are ready."
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed for voice chat."
                                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The speech service could not connect. Tap to try again."
                                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "This speech language is unavailable. Review your Android speech settings."
                                else -> "The speech service stopped. Retry microphone when you are ready."
                            }
                        },
                        recognitionGate.remainingMillis()
                    )
                }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDevice)
            }
            started("recognition", ticket)
            failedStage = "support_check"
            var qualified = false
            armWatchdog(10_000) {
                if (active()) {
                    terminal("timeout_support")
                    recognitionGate.bindingFailed(sessionId)
                    recognitionFailure("SPEECH_SUPPORT_TIMEOUT", "support_check", false, ticket)
                    stop()
                    listener.repairRequired("The speech service did not confirm support for this language. Review Android speech settings or continue typing.", recognitionGate.remainingMillis(), VoiceRecoveryAction.SPEECH_SERVICE)
                }
            }
            val handleSupport: (RecognitionQualification) -> Unit = supportResult@{ support ->
                if (!active() || qualified) return@supportResult
                qualified = true
                event("recognition_support_${support.status.name.lowercase()}", ticket)
                if (support.status !in setOf(RecognitionSupportStatus.SUPPORTED, RecognitionSupportStatus.UNVERIFIED)) {
                    terminal("support_unavailable")
                    val limited = support.errorCode == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS
                    if (limited) recognitionGate.failed(true, sessionId) else recognitionGate.bindingFailed(sessionId)
                    recognitionFailure(if (limited) "SPEECH_RATE_LIMITED" else "SPEECH_SUPPORT_${support.status.name}", "support_check", false, ticket, support.errorCode)
                    stop()
                    val repair = when {
                        support.errorCode == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceRecoveryAction.MICROPHONE_PERMISSION
                        support.status in setOf(RecognitionSupportStatus.LANGUAGE_UNAVAILABLE, RecognitionSupportStatus.UNSUPPORTED) || support.errorCode in setOf(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) -> VoiceRecoveryAction.SPEECH_LANGUAGE
                        else -> VoiceRecoveryAction.SPEECH_SERVICE
                    }
                    listener.repairRequired(
                        when (repair) {
                            VoiceRecoveryAction.MICROPHONE_PERMISSION -> "Microphone permission is needed for voice chat."
                            VoiceRecoveryAction.SPEECH_LANGUAGE -> "The selected speech language is not ready for this service. Install or select a supported language in Android speech settings."
                            VoiceRecoveryAction.SPEECH_SERVICE -> if (limited) SPEECH_LIMIT_MESSAGE else "The Android speech service could not confirm this recognition configuration. Review its settings or continue typing."
                            VoiceRecoveryAction.LOCAL_MODEL -> "Install a local speech model in Voice settings, or select Android speech."
                        },
                        recognitionGate.remainingMillis(),
                        repair
                    )
                    return@supportResult
                }
                cachedRecognitionSupport = intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE).orEmpty() to support
                try {
                    armWatchdog(15_000) {
                        if (active()) {
                            terminal("timeout_ready")
                            recognitionGate.bindingFailed(sessionId)
                            recognitionFailure("SPEECH_SERVICE_START_TIMEOUT", "service_start", true, ticket)
                            stop()
                            listener.repairRequired("Microphone did not become ready. Check the Android speech service, or continue typing in chat.", recognitionGate.remainingMillis(), VoiceRecoveryAction.SPEECH_SERVICE)
                        }
                    }
                    failedStage = "service_start"
                    dispatched = true
                    engine.startListening(intent)
                } catch (failure: Exception) {
                    failedStart(failure)
                }
            }
            val cached = cachedRecognitionSupport?.takeIf { it.first == intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE) }
            if (cached != null) {
                event("recognition_support_reused · engineInstance=$recognizerInstance", ticket)
                handleSupport(cached.second)
            } else {
                recognitionService.checkSupport(engine, intent, onDevice, handleSupport)
            }
        } catch (failure: Exception) {
            failedStart(failure)
        }
    }

    internal fun playbackAttributes() = AudioAttributes.Builder()
        .setUsage(if (routeMode != null) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) =
        speak(text, allowOnlineSpeech, complete, error)

    internal fun speakOffline(text: String, complete: () -> Unit, error: (String) -> Unit) =
        speak(text, false, complete, error)

    private fun speak(text: String, online: Boolean, complete: () -> Unit, error: (String) -> Unit) {
        stop()
        if (closed) return
        val ticket = generation
        playbackError = error
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(playbackAttributes())
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
        fun fail(message: String, retireEngine: Boolean = false) {
            main.post {
                if (!closed && ticket == generation) {
                    terminal("failed")
                    // A rejected Binder operation means the old engine cannot be
                    // assumed bound. Retire it before stop() so another reply can
                    // initialize a fresh engine without repeated stop warnings.
                    if (retireEngine) releaseSpeech(SpeechVoiceCatalogState.UNAVAILABLE)
                    stop()
                    error(message)
                }
            }
        }
        val config = SpeechConfiguration(languageTag, selectedVoice, online, speechRate.coerceIn(.5f, 2f), routeMode != null)
        val play: () -> Unit = play@{
            val engine = tts?.takeIf { speechReady } ?: return@play
            val engineVersion = speechGeneration
            armWatchdog(15_000) {
                if (!closed && ticket == generation) {
                    terminal("timeout_start")
                    stop()
                    error("Speech playback did not start. Your answer remains in chat.")
                }
            }
            speechWork.execute work@{
                try {
                    if (closed || ticket != generation || engineVersion != speechGeneration || tts !== engine || !speechReady) return@work
                    if (configuredEngine !== engine || configuredSpeech != config) {
                        configuredSpeech = null
                        if (engine.setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(if (config.communication) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                            ) == TextToSpeech.ERROR
                        ) {
                            fail("The speech engine is unavailable. Captions remain available.", retireEngine = true)
                            return@work
                        }
                        val voices = runCatching { engine.voices?.toSet() }.getOrNull()
                        if (voices == null) {
                            main.post {
                                if (ticket == generation) unavailableSpeechCatalog(engine, engineVersion)
                            }
                            return@work
                        }
                        main.post {
                            if (!closed && engineVersion == speechGeneration && tts === engine) {
                                installedVoices = voices
                                mutableVoices.value = this@AndroidVoiceAudio.voices()
                                mutableVoiceCatalog.value = if (voices.isEmpty()) SpeechVoiceCatalogState.EMPTY else SpeechVoiceCatalogState.AVAILABLE
                            }
                        }
                        val locale = Locale.forLanguageTag(config.language)
                        if (engine.setLanguage(locale) < 0) {
                            fail("A voice for this language is not installed. Captions are still available.")
                            return@work
                        }
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
                    val utteranceIds = chunks.indices.mapTo(hashSetOf()) { "$ticket:$it" }
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            main.post {
                                if (!closed && ticket == generation && engineVersion == speechGeneration && tts === engine && utteranceId in utteranceIds) {
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
                                if (!closed && ticket == generation && engineVersion == speechGeneration && tts === engine && utteranceId in utteranceIds) {
                                    event("tts_utterance_completed · utterance=$utteranceId", ticket)
                                    if (utteranceId == finalId) {
                                        val finished = synchronized(speechStateLock) {
                                            if (closed || ticket != generation || engineVersion != speechGeneration || tts !== engine || speechState != SpeechEngineState.SPEAKING) {
                                                false
                                            } else {
                                                // Natural completion has already drained this engine's
                                                // queue. Cleanup must not issue an idle Binder stop.
                                                speechState = SpeechEngineState.READY
                                                true
                                            }
                                        }
                                        if (!finished) return@post
                                        terminal("completed")
                                        stop()
                                        complete()
                                    }
                                }
                            }
                        }

                        @Deprecated("Platform compatibility callback")
                        override fun onError(utteranceId: String?) {
                            if (engineVersion == speechGeneration && tts === engine && (utteranceId == null || utteranceId in utteranceIds)) {
                                fail("Could not play this response. Turn off the speaker to continue with captions.", retireEngine = true)
                            }
                        }
                    })
                    for ((index, chunk) in chunks.withIndex()) {
                        val admitted = synchronized(speechStateLock) {
                            if (closed || ticket != generation || engineVersion != speechGeneration || tts !== engine || !speechReady) {
                                false
                            } else {
                                speechState = SpeechEngineState.SPEAKING
                                true
                            }
                        }
                        if (!admitted) return@work
                        if (engine.speak(chunk, TextToSpeech.QUEUE_ADD, null, "$ticket:$index") == TextToSpeech.ERROR) {
                            fail("Could not play this response. Captions are still available.", retireEngine = true)
                            break
                        }
                    }
                } catch (_: Exception) {
                    fail("The speech engine stopped. Turn off the speaker or try again.", retireEngine = true)
                }
            }
        }
        if (speechReady) {
            play()
            return
        }
        pendingSpeech = play
        initializeSpeech()
    }

    private fun initializeSpeech() {
        if (tts != null || closed || speechState == SpeechEngineState.INITIALIZING) return
        speechState = SpeechEngineState.INITIALIZING
        mutableVoiceCatalog.value = SpeechVoiceCatalogState.CHECKING
        val initialization = ++speechGeneration
        val initializationLease = SpeechInitialization(initialization)
        speechInitialization = initializationLease
        // Voice-settings discovery also binds TTS without a pending utterance.
        // A missing initialization callback must not leave its catalogue checking forever.
        speechInitializationTimeout = Runnable {
            if (!closed && initialization == speechGeneration && speechState == SpeechEngineState.INITIALIZING) {
                val failed = playbackError
                val pending = pendingSpeech
                event("tts_initialization_timeout · engineInstance=$initialization")
                releaseSpeech(SpeechVoiceCatalogState.UNAVAILABLE)
                if (pending != null) {
                    terminal("timeout_initialization")
                    stop()
                    failed?.invoke("The speech engine did not initialize. Your answer remains in chat.")
                }
            }
        }.also { main.postDelayed(it, 10_000) }
        try {
            val engine = speechFactory(context) listener@{ status ->
                // Android may deliver duplicate or late callbacks. Only the first result owns
                // this engine; a late successful bind is shut down after it becomes bound.
                if (!initializationLease.result.compareAndSet(null, status)) return@listener
                dispatchSpeechInitialization(initializationLease, status)
            }
            initializationLease.engine = engine
            tts = engine
            event("tts_engine_created · engineInstance=$initialization · activeOwnedEngines=${activeSpeechOwners.incrementAndGet()} · state=INITIALIZING")
            // Test doubles and vendor engines may call onInit before their constructor returns.
            // Re-dispatch after publishing engine/tts; handled fences the normal async path.
            initializationLease.result.get()?.let { dispatchSpeechInitialization(initializationLease, it) }
        } catch (_: Exception) {
            speechInitialization = null
            val failed = playbackError
            val pending = pendingSpeech
            pendingSpeech = null
            playbackError = null
            releaseSpeech(SpeechVoiceCatalogState.UNAVAILABLE)
            if (pending != null) {
                // Constructor failure must retire the same watchdog and audio
                // focus as an asynchronous initialization failure before notifying.
                terminal("initialization_failed")
                stop()
                failed?.invoke("Could not start text-to-speech. Install a voice or continue with captions.")
            }
        }
    }

    private fun dispatchSpeechInitialization(initialization: SpeechInitialization, status: Int) {
        main.post {
            val initializedEngine = initialization.engine ?: return@post
            if (!initialization.handled.compareAndSet(false, true)) return@post
            if (initialization.retireAfterInitialization || closed || initialization.generation != speechGeneration || speechState != SpeechEngineState.INITIALIZING || tts !== initializedEngine) {
                if (status == TextToSpeech.SUCCESS) {
                    retireInitializedSpeech(initialization, "late_initialization")
                } else {
                    // No Binder connection exists, and no later callback can supersede the
                    // first terminal result. Break the listener/engine reference cycle.
                    initialization.engine = null
                }
                return@post
            }
            speechInitializationTimeout?.let(main::removeCallbacks)
            speechInitializationTimeout = null
            speechState = if (status == TextToSpeech.SUCCESS) SpeechEngineState.READY else SpeechEngineState.UNINITIALIZED
            event("tts_initialization_result · engineInstance=${initialization.generation} · state=${speechState.name}")
            val pending = pendingSpeech
            pendingSpeech = null
            if (speechReady) {
                if (pending != null) {
                    watchdog?.let(main::removeCallbacks)
                    watchdog = null
                    pending.invoke()
                } else {
                    prepareVoices()
                }
            } else {
                val failed = playbackError
                playbackError = null
                releaseSpeech(SpeechVoiceCatalogState.UNAVAILABLE, awaitLateInitialization = false)
                if (pending != null) {
                    terminal("initialization_failed")
                    stop()
                    failed?.invoke("Text-to-speech is unavailable. Install a voice or continue with captions.")
                }
            }
        }
    }

    private fun releaseSpeech(
        catalogState: SpeechVoiceCatalogState = SpeechVoiceCatalogState.UNINITIALIZED,
        awaitLateInitialization: Boolean = true,
        knownUnbound: Boolean = false
    ) {
        speechInitializationTimeout?.let(main::removeCallbacks)
        speechInitializationTimeout = null
        val previous = synchronized(speechStateLock) {
            val retired = tts?.let { engine ->
                Triple(engine, speechGeneration, speechInitialization?.takeIf { it.engine === engine })
            }
            speechGeneration++
            speechState = if (closed) SpeechEngineState.CLOSED else SpeechEngineState.UNINITIALIZED
            tts = null
            speechInitialization = null
            retired
        }
        installedVoices = emptySet()
        mutableVoices.value = emptyList()
        voiceQueryInFlight = null
        mutableVoiceCatalog.value = catalogState
        if (previous != null) {
            val (engine, version, initialization) = previous
            event("tts_engine_retired · engineInstance=$version · activeOwnedEngines=${activeSpeechOwners.decrementAndGet()} · state=${speechState.name}")
            if (knownUnbound) {
                initialization?.engine = null
                speechWork.execute {
                    if (configuredEngine === engine) {
                        configuredEngine = null
                        configuredSpeech = null
                    }
                }
                event("tts_shutdown_skipped · engineInstance=$version · reason=already_disconnected")
            } else {
                when (initialization?.result?.get()) {
                    TextToSpeech.SUCCESS -> retireInitializedSpeech(initialization, "retired")
                    null -> {
                        // shutdown() before onInit(SUCCESS) produces Android's misleading
                        // "not bound" warning. Defer until a late successful bind, if one arrives.
                        initialization?.retireAfterInitialization = awaitLateInitialization
                        event("tts_shutdown_deferred · engineInstance=$version · awaitingInitialization=$awaitLateInitialization")
                    }
                    else -> {
                        initialization.engine = null
                        event("tts_shutdown_skipped · engineInstance=$version · reason=initialization_failed")
                    }
                }
            }
        }
    }

    private fun retireInitializedSpeech(initialization: SpeechInitialization, reason: String) {
        val engine = initialization.engine ?: return
        if (!initialization.shutdown.compareAndSet(false, true)) return
        val cleanup = Runnable {
            if (configuredEngine === engine) {
                configuredEngine = null
                configuredSpeech = null
            }
            runCatching { engine.shutdown() }
        }
        try {
            speechWork.execute(cleanup)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // A close can retire the worker before a late platform callback. This one bounded
            // Binder cleanup is preferable to retaining an initialized engine for the process.
            cleanup.run()
        }
        event("tts_shutdown_dispatched · engineInstance=${initialization.generation} · reason=$reason")
    }

    override fun stop() {
        if (closed) return
        terminal("interrupted")
        val cancelledSpeech = synchronized(speechStateLock) {
            generation++
            tts?.takeIf { speechReady }?.let { it to speechGeneration }
        }
        watchdog?.let(main::removeCallbacks)
        watchdog = null
        focus?.let { runCatching { audioManager?.abandonAudioFocusRequest(it) } }
        focus = null
        pendingSpeech = null
        playbackError = null
        val interruptedRecognition = recognitionActive
        recognitionActive = false
        if (interruptedRecognition) retireRecognizer()
        recognitionGate.release(sessionId)
        VoiceMicrophoneLease.release(sessionId)
        // Queue cancellation behind configuration/admission on the same worker.
        // Inspect state there: a stop racing a final admission must still cancel
        // its queued audio, while INITIALIZING/idle engines never receive stop().
        cancelledSpeech?.let { (speech, engineVersion) ->
            speechWork.execute {
                val needsStop = synchronized(speechStateLock) {
                    if (tts === speech && engineVersion == speechGeneration && speechState == SpeechEngineState.SPEAKING) {
                        speechState = SpeechEngineState.READY
                        true
                    } else {
                        false
                    }
                }
                if (needsStop) {
                    val stopped = runCatching { speech.stop() }.getOrDefault(TextToSpeech.ERROR)
                    if (stopped == TextToSpeech.ERROR) {
                        main.post {
                            if (tts === speech && engineVersion == speechGeneration) releaseSpeech(SpeechVoiceCatalogState.UNAVAILABLE)
                        }
                    }
                }
            }
        }
    }

    /** Release an idle system service before changing backends or leaving the voice screen. */
    internal fun releaseRecognition() {
        stop()
        retireRecognizer()
    }

    private fun retireRecognizer() {
        val previous = recognizer
        val previousInstance = recognizerInstance
        recognizer = null
        recognizerInstance = null
        recognizerOnDevice = null
        cachedRecognitionSupport = null
        runCatching { previous?.cancel() }
        val destroyed = runCatching { previous?.destroy() }.isSuccess
        if (previous != null && previousInstance != null) {
            event("recognizer_retired · engineInstance=$previousInstance · activeOwnedEngines=${activeRecognizerOwners.decrementAndGet()} · destroyCompleted=$destroyed")
        }
    }

    override fun close() {
        if (closed) return
        terminal("disposed")
        releaseRecognition()
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
