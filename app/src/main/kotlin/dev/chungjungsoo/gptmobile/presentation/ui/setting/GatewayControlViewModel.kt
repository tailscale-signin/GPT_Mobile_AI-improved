package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GatewayAdmissionSetting
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringClient
import dev.chungjungsoo.gptmobile.data.github.GitHubAutonomousEngineeringPolicy
import dev.chungjungsoo.gptmobile.data.github.GitHubChatProfileOverride
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayControlPhase
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayControlRepository
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayControlState
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayPairingLink
import dev.chungjungsoo.gptmobile.data.repository.ProfileModelCatalog
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import java.net.URI
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@HiltViewModel
internal class GatewayControlViewModel @Inject constructor(private val settings: SettingRepository, private val repository: GatewayControlRepository, private val engineering: GatewayEngineeringClient) : ViewModel() {
    val profiles = settings.observePlatformV2s().map { list -> list.filter(GitHubAutonomousEngineeringPolicy::supportsSavedJobs) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val states = repository.states
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private var action: Job? = null

    private val directStates = MutableStateFlow<Map<Pair<String, String>, GatewayAdmissionSetting>>(emptyMap())
    val direct = directStates.asStateFlow()
    private var refreshJob: Job? = null
    private var lastUnavailable: Pair<Pair<String, String>, Long>? = null

    fun refresh(profile: PlatformV2) {
        if (refreshJob?.isActive == true || action?.isActive == true) return
        refreshJob = viewModelScope.launch {
            try {
                val current = settings.fetchPlatformV2s().singleOrNull { it.uid == profile.uid && it.enabled && it.apiUrl == profile.apiUrl }
                if (current != null) {
                    repository.refresh(current)
                    if (repository.states.value[current.uid]?.phase == GatewayControlPhase.UNPAIRED) {
                        val config = ProviderRequestConfig(current.apiUrl, current.token, profileUid = current.uid)
                        val key = current.uid to current.apiUrl
                        if (lastUnavailable?.let { it.first == key && System.nanoTime() - it.second < 30_000_000_000L } == true) return@launch
                        val admission = engineering.admissionSetting(config, null)
                        if (admission.principalId.startsWith("tailnet:") || admission.principalId == "local:single-user") {
                            directStates.value = directStates.value + (key to admission)
                            if (admission.enabled) selectGateway(current)
                        } else {
                            directStates.value -= (current.uid to current.apiUrl)
                        }
                    } else {
                        directStates.value -= (current.uid to current.apiUrl)
                        selectConnectedGateway(current)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val key = profile.uid to profile.apiUrl
                lastUnavailable = key to System.nanoTime()
                directStates.value -= key
                if (repository.states.value[profile.uid]?.phase != GatewayControlPhase.UNPAIRED) {
                    mutableError.value = "Could not refresh Gateway settings."
                }
            }
        }
    }

    fun connectAddress(address: String, link: String? = null, onConnected: (String) -> Unit) = launchAction {
        val uri = URI(address.trim().trimEnd('/'))
        require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.fragment == null)
        require(uri.rawPath.orEmpty() in setOf("", "/v1") && (uri.port == -1 || uri.port in 1..65535))
        val endpoint = "${uri.scheme}://${uri.rawAuthority}/v1"
        val profiles = settings.fetchPlatformV2s()
        val matching = profiles.filter { it.apiUrl.trimEnd('/') == endpoint }
        require(matching.size <= 1) { "Choose the saved profile for this address." }
        val profile = matching.singleOrNull() ?: PlatformV2(
            uid = UUID.nameUUIDFromBytes("gateway:$endpoint".toByteArray()).toString(),
            name = "Gateway · ${uri.host}",
            compatibleType = ClientType.LLAMA,
            apiUrl = endpoint
        )
        require(GitHubAutonomousEngineeringPolicy.supportsSavedJobs(profile))
        val admission = if (link == null) {
            engineering.admissionSetting(ProviderRequestConfig(endpoint, null, profileUid = profile.uid), null).also {
                require(it.principalId.startsWith("tailnet:") || it.principalId == "local:single-user")
            }
        } else {
            require(GatewayPairingLink.parse(link, System.currentTimeMillis() / 1000).profileEndpoint == endpoint)
            repository.pair(profile, link)
            require(repository.states.value[profile.uid]?.engineeringBindingConfirmed == true)
            null
        }
        if (matching.isEmpty()) {
            val models = try {
                ProfileModelCatalog().load(profile)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            settings.addPlatformV2(profile.copy(model = models.singleOrNull()?.id.orEmpty()))
        }
        val saved = settings.fetchPlatformV2s().single { it.uid == profile.uid && it.enabled && it.apiUrl == profile.apiUrl }
        if (admission == null) {
            selectConnectedGateway(saved)
        } else {
            directStates.value = directStates.value + ((saved.uid to saved.apiUrl) to admission)
            if (admission.enabled) selectGateway(saved)
        }
        onConnected(saved.uid)
    }

    fun updateGateway(profile: PlatformV2, target: String? = null) = run(profile) { repository.updateGateway(it, target) }
    fun pair(profile: PlatformV2, link: String) = run(profile) {
        repository.pair(it, link)
        selectConnectedGateway(it)
    }

    private suspend fun selectConnectedGateway(profile: PlatformV2) {
        val state = repository.states.value[profile.uid] ?: return
        if (!state.writable || !state.engineeringBindingConfirmed || state.snapshot?.desired?.get("engineering_enabled") != JsonPrimitive(true)) return
        selectGateway(profile)
    }

    private suspend fun selectGateway(profile: PlatformV2) {
        if (settings.getFeatureSettings().githubProfileOverrides[profile.uid]?.engineeringGatewayDefaultApplied == true) return
        settings.mutateFeatureSettings { current ->
            val saved = current.githubProfileOverrides[profile.uid] ?: GitHubChatProfileOverride()
            if (saved.engineeringGatewayDefaultApplied || saved.engineeringGatewayProfileUid != null) {
                current
            } else {
                current.copy(
                    githubProfileOverrides = current.githubProfileOverrides + (profile.uid to saved.copy(engineeringGatewayProfileUid = profile.uid, engineeringGatewayDefaultApplied = true))
                )
            }
        }
    }
    fun toggleDirect(profile: PlatformV2) = run(profile) {
        val previous = requireNotNull(directStates.value[it.uid to it.apiUrl])
        require(previous.principalId.startsWith("tailnet:"))
        val config = ProviderRequestConfig(it.apiUrl, it.token, profileUid = it.uid)
        val confirmed = engineering.admissionSetting(config, previous)
        directStates.value = directStates.value + ((it.uid to it.apiUrl) to confirmed)
        if (confirmed.enabled) selectGateway(it)
    }

    fun finishPairing(profile: PlatformV2) = run(profile) { repository.finishPairing(it) }
    fun apply(profile: PlatformV2, revision: Long, changes: JsonObject, confirmEngineering: Boolean = false) = run(profile) { repository.apply(it, revision, changes, confirmEngineering) }
    fun revoke(profile: PlatformV2) = run(profile) { repository.revoke(it) }
    fun retryPending(profile: PlatformV2, reviewed: GatewayControlState) = run(profile) {
        repository.retryPending(it, requireNotNull(reviewed.pendingCommand), requireNotNull(reviewed.snapshot).principalId, requireNotNull(reviewed.targetEndpoint))
    }
    fun clearError() {
        mutableError.value = null
    }

    private fun run(expected: PlatformV2, operation: suspend (PlatformV2) -> Unit) = launchAction {
        val current = settings.fetchPlatformV2s().singleOrNull { it.uid == expected.uid && it.enabled && it.apiUrl == expected.apiUrl }
            ?: error("Selected Gateway connection changed.")
        operation(current)
    }

    private fun launchAction(operation: suspend () -> Unit) {
        if (action?.isActive == true) return
        action = viewModelScope.launch {
            mutableBusy.value = true
            mutableError.value = null
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Do not display remote response bodies, pairing codes, credentials or TLS details.
                mutableError.value = "Could not verify this operation. Refresh the connection or open a new link from the Gateway PC."
            } finally {
                mutableBusy.value = false
            }
        }
    }
}
