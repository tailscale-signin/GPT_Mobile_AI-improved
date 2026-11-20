package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationRepository
import dev.chungjungsoo.gptmobile.data.context.ReasoningPolicy
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GatewayAdmissionSetting
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringClient
import dev.chungjungsoo.gptmobile.data.github.GitHubAutonomousEngineeringPolicy
import dev.chungjungsoo.gptmobile.data.github.GitHubChatProfileOverride
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayConnectionSettingsChanged
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayControlPhase
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayControlRepository
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayControlState
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayPairingLink
import dev.chungjungsoo.gptmobile.data.network.gateway.gatewayAddressEndpoint
import dev.chungjungsoo.gptmobile.data.network.gateway.privateGatewayAddress
import dev.chungjungsoo.gptmobile.data.repository.ProfileModelCatalog
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@HiltViewModel
internal class GatewayControlViewModel @Inject constructor(private val settings: SettingRepository, private val repository: GatewayControlRepository, private val engineering: GatewayEngineeringClient, private val preparation: GatewayModelPreparationRepository) : ViewModel() {
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
                        val identity = engineering.connectionIdentity(config)
                        if (identity.principal == "network:shared") {
                            directStates.value -= key
                            lastUnavailable = key to System.nanoTime()
                        } else {
                            val admission = engineering.admissionSetting(config, null)
                            require(admission.principalId == identity.principal && admission.gatewayId == identity.gatewayId)
                            observeAccount(current, admission)
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

    private suspend fun observeAccount(profile: PlatformV2, admission: GatewayAdmissionSetting) {
        val key = profile.uid to profile.apiUrl
        if (admission.connectedSettings) {
            repository.connectAccount(profile, admission)
            directStates.value -= key
        } else {
            directStates.value = directStates.value + (key to admission)
        }
        if (admission.enabled) selectGateway(profile)
    }

    fun connectAddress(address: String, link: String? = null, onConnected: (String) -> Unit) = launchAction {
        val input = address.trim()
        val privateAddress = if ('#' in input) privateGatewayAddress(input, System.currentTimeMillis() / 1000) else null
        val uri = gatewayAddressEndpoint(input)
        val endpoint = uri.toString()
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
        val config = ProviderRequestConfig(endpoint, profile.token, profileUid = profile.uid)
        val admission = when {
            privateAddress != null -> {
                repository.connectPrivate(profile, privateAddress)
                null
            }
            link != null -> {
                require(GatewayPairingLink.parse(link, System.currentTimeMillis() / 1000).profileEndpoint == endpoint)
                repository.pair(profile, link)
                null
            }
            else -> {
                repository.refresh(profile)
                when (repository.states.value[profile.uid]?.phase) {
                    GatewayControlPhase.UNPAIRED, GatewayControlPhase.REVOKED -> {
                        val identity = engineering.connectionIdentity(config)
                        if (identity.principal == "network:shared") {
                            identity.phoneConnection?.let {
                                val offer = GatewayPairingLink.parse(it, System.currentTimeMillis() / 1000)
                                require(offer.installationId == identity.gatewayId && offer.profileEndpoint == endpoint && offer.code.startsWith("lan_"))
                                repository.connectPrivate(profile, offer)
                            }
                            null
                        } else {
                            engineering.admissionSetting(config, null).also {
                                require(it.principalId == identity.principal && it.gatewayId == identity.gatewayId)
                            }
                        }
                    }
                    GatewayControlPhase.LIVE, GatewayControlPhase.RECONCILING, GatewayControlPhase.CONFLICT -> null
                    else -> error("Saved Gateway unavailable")
                }
            }
        }

        if (matching.isEmpty()) {
            val models = try {
                withTimeoutOrNull(4_000) { ProfileModelCatalog().load(profile) }.orEmpty()
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
            observeAccount(saved, admission)
        }
        onConnected(saved.uid)
    }

    fun prepareModel(profile: PlatformV2, model: String, onMessage: (String) -> Unit) = run(profile) { current ->
        val snapshot = requireNotNull(repository.states.value[current.uid]?.takeIf { it.writable }?.snapshot)
        check(snapshot.desired["backend"] == snapshot.effective["backend"])
        val selected = (snapshot.desired["model"] as? JsonPrimitive)?.content.orEmpty().ifBlank { current.model }
        require(model == selected && model.isNotBlank())
        val mode = ReasoningPolicy.preference(current.reasoning, settings.getFeatureSettings().llamaReasoningModes[current.uid])
        preparation.prepareSelected(current.copy(model = model), mode, forceRefresh = true) { state ->
            onMessage(state?.message ?: "The Gateway could not confirm preparation for this model.")
        }
    }

    suspend fun readConnectionSettings(profile: PlatformV2) = repository.connectionSettings(profile)

    fun saveConnectionSettings(profile: PlatformV2, command: JsonObject, confirmed: (JsonObject) -> Unit) = run(profile) {
        confirmed(repository.connectionSettings(it, command))
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
            } catch (_: GatewayConnectionSettingsChanged) {
                mutableError.value = "Connection settings changed on the PC. Reload the connection settings and review your edits."
            } catch (_: Exception) {
                // Do not display remote response bodies, pairing codes, credentials or TLS details.
                mutableError.value = "Could not verify this operation. Check the Gateway address and connection. For Wi-Fi access, check the phone IP in Connect phone on the PC."
            } finally {
                mutableBusy.value = false
            }
        }
    }
}
