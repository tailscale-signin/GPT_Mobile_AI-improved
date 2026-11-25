package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.database.entity.ProviderConnection
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.voice.GatewayVoicePhraseEngineFactory
import dev.chungjungsoo.gptmobile.data.voice.KokoroModelVariant
import dev.chungjungsoo.gptmobile.data.voice.KokoroVoice
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelState
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeKokoroBucket
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechCatalog
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechEngine
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechModelStore
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeVoicePhraseEngineFactory
import dev.chungjungsoo.gptmobile.data.voice.QualcommVoicePhraseEngineFactory
import dev.chungjungsoo.gptmobile.data.voice.UnavailableVoicePhraseEngineFactory
import dev.chungjungsoo.gptmobile.data.voice.VoicePhraseEngineFactory
import dev.chungjungsoo.gptmobile.data.voice.VoicePluginInstall
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBackend
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class VoiceBackendSettings(
    val backend: VoiceSpeechBackend = VoiceSpeechBackend.DEFAULT,
    val gatewayUid: String = "",
    val gateways: List<ProviderConnection> = emptyList(),
    val status: String = "Loading speech settings…",
    val factory: VoicePhraseEngineFactory = UnavailableVoicePhraseEngineFactory("Loading speech settings…"),
    val loaded: Boolean = false,
    val streamingModels: Map<MediaPipeSpeechEngine, LocalVoiceModelState> = emptyMap()
)

internal fun VoiceSpeechBackend.streamingEngine(): MediaPipeSpeechEngine? = when (this) {
    VoiceSpeechBackend.LITERT_KOKORO_CPU, VoiceSpeechBackend.LITERT_KOKORO_GPU, VoiceSpeechBackend.LITERT_KOKORO_NPU -> MediaPipeSpeechEngine.KOKORO
    VoiceSpeechBackend.PHONON_CPU -> MediaPipeSpeechEngine.PHONON
    VoiceSpeechBackend.POCKET_CPU -> MediaPipeSpeechEngine.POCKET
    else -> null
}

internal fun KokoroVoice.streamingVoiceId(): String = when (this) {
    KokoroVoice.HEART -> "af_heart"
    KokoroVoice.ADAM -> "am_adam"
    KokoroVoice.EMMA -> "bf_emma"
}

/** Main-thread orchestration; only credential and preference I/O leaves the owning dispatcher. */
@HiltViewModel
internal class VoiceBackendViewModel internal constructor(
    private val context: Context,
    private val settings: SettingRepository,
    private val preferences: VoiceBackendPreferenceStore,
    private val io: CoroutineDispatcher
) : ViewModel() {
    @Inject constructor(
        @ApplicationContext context: Context,
        settings: SettingRepository,
        preferences: VoiceBackendPreferenceStore
    ) : this(context, settings, preferences, Dispatchers.IO)

    private val mutable = MutableStateFlow(VoiceBackendSettings())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var update: Job? = null
    private var catalog: Job? = null
    private var saved: VoiceBackendPreferenceState? = null
    private var catalogLoaded = false
    private var catalogError: String? = null
    private var backendStatus = "Loading speech settings…"
    private data class Configuration(val preference: VoiceBackendPreference, val connection: ProviderConnection?, val voice: KokoroVoice, val rate: Float, val bucket: MediaPipeKokoroBucket, val incremental: Boolean, val nativeModelVariant: KokoroModelVariant)
    private var configuration: Configuration? = null
    private var voice = KokoroVoice.HEART
    private var speechRate = STANDARD_SPEECH_RATE
    private var kokoroBucket = MediaPipeKokoroBucket.AUTO
    private var incrementalSpeech = true
    private var nativeModelVariant = KokoroModelVariant.INT8
    private val streamingStoreLock = Mutex()
    private var streamingStore: MediaPipeSpeechModelStore? = null
    private val streamingInstalls = mutableMapOf<MediaPipeSpeechEngine, Job>()
    private val refreshedStreamingModels = ConcurrentHashMap.newKeySet<MediaPipeSpeechEngine>()

    init {
        viewModelScope.launch {
            preferences.state.filterNotNull().collect { value ->
                saved = value
                reconcile()
            }
        }
        observeConnections()
    }

    private fun observeConnections() {
        catalog?.cancel()
        catalog = viewModelScope.launch {
            try {
                settings.observeProviderConnections().collect { connections ->
                    catalogLoaded = true
                    catalogError = null
                    val eligible = connections.filter { it.compatibleType in setOf(ClientType.LLAMA, ClientType.OLLAMA) }
                    mutable.value = mutable.value.copy(gateways = eligible)
                    reconcile()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                catalogLoaded = true
                catalogError = "Gateway connections could not be loaded. Select a backend to retry."
                mutable.value = mutable.value.copy(gateways = emptyList())
                reconcile()
            }
        }
    }

    fun selectBackend(backend: VoiceSpeechBackend) {
        if (!mutable.value.loaded) return
        val value = VoiceBackendPreference(backend, mutable.value.gatewayUid)
        preferences.save(value)
        saved = preferences.state.value
        if (catalogError != null) observeConnections()
        reconcile(force = true)
    }

    fun configureSpeech(voice: KokoroVoice?, rate: Float, bucket: MediaPipeKokoroBucket = kokoroBucket, incremental: Boolean = incrementalSpeech, modelVariant: KokoroModelVariant = nativeModelVariant) {
        voice ?: return
        val bounded = rate.takeIf(Float::isFinite)?.coerceIn(.5f, 2f) ?: STANDARD_SPEECH_RATE
        if (this.voice == voice && speechRate == bounded && kokoroBucket == bucket && incrementalSpeech == incremental && nativeModelVariant == modelVariant) return
        this.voice = voice
        speechRate = bounded
        kokoroBucket = bucket
        incrementalSpeech = incremental
        nativeModelVariant = modelVariant
        reconcile()
    }

    fun recheckBackend() {
        val state = mutable.value
        if (state.loaded && state.backend != VoiceSpeechBackend.GATEWAY_CUDA && !state.backend.isStreamingLocal) {
            rebuild(VoiceBackendPreference(state.backend, state.gatewayUid), retest = true)
        }
    }

    private suspend fun streamingModels(): MediaPipeSpeechModelStore = streamingStoreLock.withLock {
        streamingStore ?: withContext(io) {
            MediaPipeSpeechModelStore(context, viewModelScope)
        }.also { store ->
            streamingStore = store
            viewModelScope.launch {
                store.state.collect { models -> mutable.value = mutable.value.copy(streamingModels = models) }
            }
        }
    }

    fun installStreamingModel() {
        val engine = mutable.value.backend.streamingEngine() ?: return
        if (streamingInstalls[engine]?.isActive == true) return
        streamingInstalls[engine] = viewModelScope.launch { streamingModels().install(engine) }
    }

    fun cancelStreamingInstall() {
        val engine = mutable.value.backend.streamingEngine() ?: return
        if (engine == MediaPipeSpeechEngine.KOKORO) VoicePluginInstall.cancel(context)
        streamingStore?.cancelInstall(engine)
        streamingInstalls[engine]?.cancel()
    }

    /** The caller retires playback first; the model store also holds a native-use lease. */
    fun removeStreamingModel() {
        val engine = mutable.value.backend.streamingEngine() ?: return
        if (engine == MediaPipeSpeechEngine.KOKORO) VoicePluginInstall.cancel(context)
        viewModelScope.launch { streamingModels().remove(engine) }
    }

    fun selectGateway(uid: String) {
        if (!mutable.value.loaded || mutable.value.gateways.none { it.uid == uid }) return
        preferences.save(VoiceBackendPreference(mutable.value.backend, uid))
        saved = preferences.state.value
        reconcile(force = true)
    }

    private fun reconcile(force: Boolean = false) {
        val preference = saved?.value ?: return
        mutable.value = mutable.value.copy(backend = preference.backend, gatewayUid = preference.gatewayUid)
        if (preference.backend == VoiceSpeechBackend.GATEWAY_CUDA && !catalogLoaded) {
            backendStatus = "Loading Gateway connections…"
            publishStatus()
            return
        }
        val connection = mutable.value.gateways.firstOrNull { it.uid == preference.gatewayUid }
            .takeIf { preference.backend == VoiceSpeechBackend.GATEWAY_CUDA }
        val next = Configuration(preference, connection, voice, speechRate, kokoroBucket, incrementalSpeech, nativeModelVariant)
        if (!force && configuration == next) {
            publishStatus()
            return
        }
        configuration = next
        rebuild(preference, retest = false)
    }

    private fun rebuild(preference: VoiceBackendPreference, retest: Boolean) {
        val epoch = ++generation
        val capturedVoice = voice
        val capturedRate = speechRate
        val capturedBucket = kokoroBucket
        val capturedIncremental = incrementalSpeech
        val capturedVariant = nativeModelVariant
        update?.cancel()
        backendStatus = "Preparing speech settings…"
        mutable.value = mutable.value.copy(loaded = false)
        publishStatus()
        val report: (String) -> Unit = { message ->
            viewModelScope.launch {
                if (generation == epoch) {
                    backendStatus = message
                    publishStatus()
                }
            }
        }
        update = viewModelScope.launch {
            try {
                val prepared = withContext(io) {
                    if (retest) QualcommVoicePhraseEngineFactory.requestRetest(context, preference.backend)
                    prepare(preference, report, retest, capturedVoice, capturedRate, capturedBucket, capturedIncremental, capturedVariant)
                }
                if (generation == epoch) {
                    backendStatus = prepared.status
                    mutable.value = mutable.value.copy(factory = prepared.factory, loaded = true)
                    publishStatus()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == epoch) {
                    backendStatus = "Speech connection could not be loaded. Select a backend to retry."
                    mutable.value = mutable.value.copy(factory = UnavailableVoicePhraseEngineFactory("Speech connection unavailable."), loaded = true)
                    publishStatus()
                }
            }
        }
    }

    private data class Prepared(val factory: VoicePhraseEngineFactory, val status: String)

    private suspend fun prepare(value: VoiceBackendPreference, report: (String) -> Unit, retest: Boolean, voice: KokoroVoice, rate: Float, bucket: MediaPipeKokoroBucket, incremental: Boolean, modelVariant: KokoroModelVariant): Prepared = when (value.backend) {
        VoiceSpeechBackend.CPU -> Prepared(QualcommVoicePhraseEngineFactory(context, value.backend, report, voice = voice, speechRate = rate, modelVariant = modelVariant), "Speech runs on this phone's CPU.")
        VoiceSpeechBackend.AUTO -> Prepared(
            QualcommVoicePhraseEngineFactory(context, value.backend, report, calibrateAll = retest, voice = voice, speechRate = rate, modelVariant = modelVariant),
            if (retest) {
                "The next reply compares CPU, GPU and NPU with the selected voice and rate across varied phrase lengths. This may take several minutes; playback starts after the comparison. You can cancel while it runs."
            } else {
                "Auto uses the fastest verified measurement on this phone, or CPU until you compare engines."
            }
        )
        VoiceSpeechBackend.QNN_GPU, VoiceSpeechBackend.QNN_HTP -> Prepared(
            QualcommVoicePhraseEngineFactory(context, value.backend, report, calibrateAll = retest, voice = voice, speechRate = rate, modelVariant = modelVariant),
            if (retest) {
                "The next reply compares this accelerator with CPU using the selected voice and rate. This may take several minutes; you can cancel while it runs."
            } else {
                "Speech uses this accelerator only after its audio passes a CPU-reference comparison for this voice, rate, model, runtime and phone. Recheck qualifies it; CPU is used until then."
            }
        )
        VoiceSpeechBackend.GATEWAY_CUDA -> {
            val connection = settings.getProviderConnection(value.gatewayUid)
            if (connection == null || connection.compatibleType !in setOf(ClientType.LLAMA, ClientType.OLLAMA)) {
                Prepared(UnavailableVoicePhraseEngineFactory("Choose a Gateway connection for speech."), "Choose the existing connection for your Gateway PC. Enable CUDA speech on that PC first.")
            } else {
                Prepared(
                    GatewayVoicePhraseEngineFactory(connection.apiUrl, settings.getProviderCredentials(connection.uid), report),
                    "Reply pronunciation tokens will be sent to ${connection.name}. CUDA readiness is checked before playback."
                )
            }
        }
        VoiceSpeechBackend.LITERT_KOKORO_CPU, VoiceSpeechBackend.LITERT_KOKORO_GPU, VoiceSpeechBackend.LITERT_KOKORO_NPU,
        VoiceSpeechBackend.PHONON_CPU, VoiceSpeechBackend.POCKET_CPU -> {
            val engine = requireNotNull(value.backend.streamingEngine())
            val descriptor = MediaPipeSpeechCatalog.model(engine)
            val models = streamingModels()
            // Only inspect the selected pack. Voice/rate changes reuse the verified generation
            // instead of hashing every installed model or starting unrelated downloads.
            if (refreshedStreamingModels.add(engine)) viewModelScope.launch { models.refresh(engine, forceVerification = false) }
            Prepared(
                MediaPipeVoicePhraseEngineFactory(
                    models,
                    engine,
                    value.backend,
                    rate,
                    if (engine == MediaPipeSpeechEngine.KOKORO) voice.streamingVoiceId() else descriptor.defaultVoice,
                    kokoroBucket = bucket,
                    incrementalInput = incremental,
                    onCpuFallback = { requested ->
                        report("${requested.label} could not initialize. Kokoro LiteRT is using CPU with the same voice and rate.")
                    }
                ),
                if (value.backend in setOf(VoiceSpeechBackend.LITERT_KOKORO_GPU, VoiceSpeechBackend.LITERT_KOKORO_NPU)) {
                    "Kokoro requests the selected accelerator for its vocoder. The runtime does not report which processor ran it. If initialization fails, playback opens the same model on CPU."
                } else {
                    "Audio streams on this phone using ${descriptor.title}. It works offline once its voice pack is installed."
                }
            )
        }
    }

    private fun publishStatus() {
        mutable.value = mutable.value.copy(status = listOfNotNull(saved?.error, catalogError, backendStatus).joinToString(" "))
    }
}
