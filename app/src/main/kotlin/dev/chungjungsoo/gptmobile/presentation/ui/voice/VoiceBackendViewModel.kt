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
import dev.chungjungsoo.gptmobile.data.voice.QualcommVoicePhraseEngineFactory
import dev.chungjungsoo.gptmobile.data.voice.TimedKokoroPhraseEngineFactory
import dev.chungjungsoo.gptmobile.data.voice.UnavailableVoicePhraseEngineFactory
import dev.chungjungsoo.gptmobile.data.voice.VoicePhraseEngineFactory
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBackend
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class VoiceBackendSettings(
    val backend: VoiceSpeechBackend = VoiceSpeechBackend.CPU,
    val gatewayUid: String = "",
    val gateways: List<ProviderConnection> = emptyList(),
    val status: String = "Loading speech settings…",
    val factory: VoicePhraseEngineFactory = TimedKokoroPhraseEngineFactory,
    val loaded: Boolean = false
)

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
    private var configuration: Pair<VoiceBackendPreference, ProviderConnection?>? = null

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

    fun recheckBackend() {
        val state = mutable.value
        if (state.loaded && state.backend != VoiceSpeechBackend.GATEWAY_CUDA) {
            rebuild(VoiceBackendPreference(state.backend, state.gatewayUid), retest = true)
        }
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
        val next = preference to connection
        if (!force && configuration == next) {
            publishStatus()
            return
        }
        configuration = next
        rebuild(preference, retest = false)
    }

    private fun rebuild(preference: VoiceBackendPreference, retest: Boolean) {
        val epoch = ++generation
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
                    prepare(preference, report, retest)
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

    private suspend fun prepare(value: VoiceBackendPreference, report: (String) -> Unit, retest: Boolean): Prepared = when (value.backend) {
        VoiceSpeechBackend.CPU -> Prepared(QualcommVoicePhraseEngineFactory(context, value.backend, report), "Speech runs on this phone's CPU.")
        VoiceSpeechBackend.AUTO -> Prepared(
            QualcommVoicePhraseEngineFactory(context, value.backend, report, calibrateAll = retest),
            if (retest) {
                "The next reply compares CPU, GPU and NPU with three speech samples. Playback starts after the comparison."
            } else {
                "Auto uses the fastest verified measurement on this phone, or CPU until you compare engines."
            }
        )
        VoiceSpeechBackend.QNN_GPU, VoiceSpeechBackend.QNN_HTP -> Prepared(
            QualcommVoicePhraseEngineFactory(context, value.backend, report),
            "Hardware execution and aligned audio are checked when speech starts. The result is saved for this model, runtime and phone."
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
    }

    private fun publishStatus() {
        mutable.value = mutable.value.copy(status = listOfNotNull(saved?.error, catalogError, backendStatus).joinToString(" "))
    }
}
