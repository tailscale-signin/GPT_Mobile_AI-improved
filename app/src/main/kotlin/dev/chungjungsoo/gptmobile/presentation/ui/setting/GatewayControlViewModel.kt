package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationRepository
import dev.chungjungsoo.gptmobile.data.context.ReasoningPolicy
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.github.GatewayAdmissionSetting
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringClient
import dev.chungjungsoo.gptmobile.data.github.GitHubApiOrigins
import dev.chungjungsoo.gptmobile.data.github.GitHubAutonomousEngineeringPolicy
import dev.chungjungsoo.gptmobile.data.github.selectVerifiedEngineeringGateway
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAddressAccountKey
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayConnectionSettingsChanged
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayControlPhase
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayControlRepository
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayControlState
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayPairingLink
import dev.chungjungsoo.gptmobile.data.network.gateway.gatewayAddressEndpoint
import dev.chungjungsoo.gptmobile.data.network.gateway.privateGatewayAddress
import dev.chungjungsoo.gptmobile.data.repository.ProfileModelCatalog
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
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
internal class GatewayControlViewModel @Inject constructor(private val settings: SettingRepository, private val repository: GatewayControlRepository, private val engineering: GatewayEngineeringClient, private val preparation: GatewayModelPreparationRepository, private val connections: ToolConnectionRepository, private val vault: SecretVault) : ViewModel() {
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
    private val networkSettings = MutableStateFlow<Map<Pair<String, String>, JsonObject>>(emptyMap())
    val networkOverview = networkSettings.asStateFlow()
    private var refreshJob: Job? = null
    private var lastUnavailable: Pair<Pair<String, String>, Long>? = null

    private val preparationStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val modelStatus = preparationStatus.asStateFlow()
    private val candidates = MutableStateFlow<Map<Pair<String, String>, List<String>>>(emptyMap())
    val modelChoices = candidates.asStateFlow()
    private var connectionPreparation: Job? = null

    fun refresh(profile: PlatformV2, forceNetwork: Boolean = false) {
        if (refreshJob?.isActive == true || action?.isActive == true) return
        refreshJob = viewModelScope.launch {
            try {
                val current = settings.fetchPlatformV2s().singleOrNull { it.uid == profile.uid && it.enabled && it.apiUrl == profile.apiUrl }
                if (current != null) {
                    if (forceNetwork) {
                        dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.checkAgain(current.apiUrl)
                        lastUnavailable = null
                    }
                    repository.refresh(current)
                    if (current.model.isBlank() && (forceNetwork || (current.uid to current.apiUrl) !in candidates.value)) resolveProfileModel(current)
                    if (repository.states.value[current.uid]?.phase == GatewayControlPhase.UNPAIRED) {
                        val config = ProviderRequestConfig(current.apiUrl, current.token, profileUid = current.uid)
                        val key = current.uid to current.apiUrl
                        if (!forceNetwork && lastUnavailable?.let { it.first == key && System.nanoTime() - it.second < 30_000_000_000L } == true) return@launch
                        val identity = engineering.connectionIdentity(config)
                        if (identity.principal == "network:shared") {
                            if (connectPrivateNetwork(current, identity)) {
                                selectConnectedGateway(current)
                            } else {
                                lastUnavailable = key to System.nanoTime()
                            }
                            directStates.value -= key
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
                showNetworkOverview(key, null)
                lastUnavailable = key to System.nanoTime()
                directStates.value -= key
                if (repository.states.value[profile.uid]?.phase != GatewayControlPhase.UNPAIRED) {
                    mutableError.value = "Could not refresh Gateway settings."
                }
            }
        }
    }

    private fun showNetworkOverview(key: Pair<String, String>, overview: JsonObject?) {
        networkSettings.value = if (overview == null) networkSettings.value - key else networkSettings.value + (key to overview)
    }

    private suspend fun connectPrivateNetwork(profile: PlatformV2, identity: GatewayEngineeringClient.ConnectionIdentity): Boolean {
        val key = profile.uid to profile.apiUrl
        showNetworkOverview(key, identity.networkSettings)
        fun link(raw: String) = GatewayPairingLink.parse(raw, System.currentTimeMillis() / 1000).also {
            require(it.installationId == identity.gatewayId && it.profileEndpoint == profile.apiUrl.trimEnd('/'))
        }
        identity.accountConnection?.let { descriptor ->
            val offered = link(descriptor)
            for (connection in connections.listConnections()) {
                if (connection.type != ToolConnectionType.GITHUB ||
                    connection.secretRef == null ||
                    runCatching { GitHubApiOrigins.fromEndpoint(connection.endpointUrl) }.getOrNull() != GitHubApiOrigins.GITHUB_COM
                ) {
                    continue
                }
                val secret = try {
                    vault.read(requireNotNull(connection.secretRef))
                } catch (_: dev.chungjungsoo.gptmobile.data.security.SecretVaultException) {
                    null
                } ?: continue
                val account = try {
                    GatewayAddressAccountKey.from(secret)
                } finally {
                    secret.fill(0)
                }
                account?.use {
                    if (it.matches(offered)) {
                        repository.connectAccountAddress(profile, offered, it)
                        showNetworkOverview(key, null)
                        return true
                    }
                }
            }
        }
        identity.phoneConnection?.let {
            val offered = link(it)
            require(offered.code.startsWith("lan_"))
            repository.connectPrivate(profile, offered)
            showNetworkOverview(key, null)
            return true
        }
        return false
    }

    private suspend fun observeAccount(profile: PlatformV2, admission: GatewayAdmissionSetting) {
        val key = profile.uid to profile.apiUrl
        if (admission.connectedSettings) {
            repository.connectAccount(profile, admission)
            directStates.value -= key
        } else {
            directStates.value = directStates.value + (key to admission)
        }
        if (admission.enabled) settings.selectVerifiedEngineeringGateway(profile)
    }

    fun connectAddress(address: String, link: String? = null, onConnected: (String) -> Unit) = launchAction {
        val input = address.trim()
        val privateAddress = if ('#' in input) privateGatewayAddress(input, System.currentTimeMillis() / 1000) else null
        val uri = gatewayAddressEndpoint(input)
        val endpoint = uri.toString()
        val profiles = settings.fetchPlatformV2s()
        val matching = profiles.filter { it.apiUrl.trimEnd('/') == endpoint }
        require(matching.size <= 1) { "Choose the saved profile for this address." }
        val profile = matching.singleOrNull()?.copy(enabled = true) ?: PlatformV2(
            uid = UUID.nameUUIDFromBytes("gateway:$endpoint".toByteArray()).toString(),
            name = "Gateway · ${uri.host}",
            compatibleType = ClientType.LLAMA,
            reasoning = true,
            maxToolCalls = Int.MAX_VALUE,
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
                            connectPrivateNetwork(profile, identity)
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
            settings.addPlatformV2(profile)
        } else if (!matching.single().enabled) {
            val existing = settings.fetchPlatformV2s().single { it.uid == profile.uid && it.apiUrl == profile.apiUrl }
            if (!existing.enabled) settings.updatePlatformV2(existing.copy(enabled = true))
        }
        resolveProfileModel(profile)
        val saved = settings.fetchPlatformV2s().single { it.uid == profile.uid && it.enabled && it.apiUrl == profile.apiUrl }
        if (admission == null) {
            selectConnectedGateway(saved)
        } else {
            observeAccount(saved, admission)
        }
        onConnected(saved.uid)
        val control = repository.states.value[saved.uid]
        if (saved.model.isNotBlank()) {
            val allowLoad = control?.writable == true &&
                control.engineeringBindingConfirmed &&
                control.snapshot?.desired?.get("backend") == control.snapshot?.effective?.get("backend") &&
                ((control.snapshot?.desired?.get("model") as? JsonPrimitive)?.content.orEmpty().ifBlank { saved.model } == saved.model)
            checkConnectedModel(saved, allowLoad)
        }
    }

    private fun checkConnectedModel(profile: PlatformV2, allowLoad: Boolean) {
        connectionPreparation?.cancel()
        connectionPreparation = viewModelScope.launch {
            try {
                val mode = ReasoningPolicy.preference(profile.reasoning, settings.getFeatureSettings().llamaReasoningModes[profile.uid])
                preparation.prepareSelectedWhile(profile, mode, forceRefresh = true, validateSelection = {
                    check(settings.fetchPlatformV2s().any { it.uid == profile.uid && it.enabled && it.apiUrl == profile.apiUrl && it.model == profile.model })
                }, allowLoad = allowLoad) { state ->
                    state?.let { preparationStatus.value += (profile.uid to it.message) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                preparationStatus.value += (profile.uid to "Could not confirm readiness for ${profile.model}. Refresh the connection to recheck.")
            }
        }
    }

    private suspend fun resolveProfileModel(profile: PlatformV2) {
        if (profile.model.isNotBlank()) return
        val key = profile.uid to profile.apiUrl
        val options = try {
            withTimeoutOrNull(4_000) { ProfileModelCatalog().load(profile) }.orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        val configured = (repository.states.value[profile.uid]?.snapshot?.effective?.get("model") as? JsonPrimitive)?.content.orEmpty()
        val selected = dev.chungjungsoo.gptmobile.data.repository.gatewayProfileModel(options, configured)
        candidates.value += (key to options.map { it.id })
        if (selected != null) saveModel(profile, selected)
    }

    private suspend fun saveModel(profile: PlatformV2, selected: String): PlatformV2 {
        require(selected in candidates.value[profile.uid to profile.apiUrl].orEmpty())
        val current = settings.fetchPlatformV2s().single { it.uid == profile.uid && it.enabled && it.apiUrl == profile.apiUrl }
        if (current.model.isNotBlank()) return current
        return current.copy(model = selected).also { settings.updatePlatformV2(it) }
    }

    fun chooseModel(profile: PlatformV2, selected: String) = run(profile) { current ->
        val saved = saveModel(current, selected)
        checkConnectedModel(saved, allowLoad = false)
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
        settings.selectVerifiedEngineeringGateway(profile)
    }

    fun toggleDirect(profile: PlatformV2) = run(profile) {
        val previous = requireNotNull(directStates.value[it.uid to it.apiUrl])
        require(previous.principalId.startsWith("tailnet:"))
        val config = ProviderRequestConfig(it.apiUrl, it.token, profileUid = it.uid)
        val confirmed = engineering.admissionSetting(config, previous)
        directStates.value = directStates.value + ((it.uid to it.apiUrl) to confirmed)
        if (confirmed.enabled) settings.selectVerifiedEngineeringGateway(it)
    }

    fun finishPairing(profile: PlatformV2) = run(profile) {
        repository.finishPairing(it)
        selectConnectedGateway(it)
    }
    fun apply(profile: PlatformV2, revision: Long, changes: JsonObject, confirmEngineering: Boolean = false) = run(profile) {
        repository.apply(it, revision, changes, confirmEngineering)
        selectConnectedGateway(it)
    }
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
                mutableError.value = "Could not verify this operation. Check the PC’s Gateway address and connection. Use the same Tailscale account, or the same saved GitHub personal access token on both devices for private Wi-Fi."
            } finally {
                mutableBusy.value = false
            }
        }
    }
}
