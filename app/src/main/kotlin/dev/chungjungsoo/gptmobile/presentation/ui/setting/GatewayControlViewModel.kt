package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GitHubAutonomousEngineeringPolicy
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayControlRepository
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayControlState
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
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

@HiltViewModel
internal class GatewayControlViewModel @Inject constructor(private val settings: SettingRepository, private val repository: GatewayControlRepository) : ViewModel() {
    val profiles = settings.observePlatformV2s().map { list -> list.filter(GitHubAutonomousEngineeringPolicy::supportsSavedJobs) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val states = repository.states
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private var action: Job? = null

    private var refreshJob: Job? = null

    fun refresh(profile: PlatformV2) {
        if (refreshJob?.isActive == true || action?.isActive == true) return
        refreshJob = viewModelScope.launch {
            try {
                val current = settings.fetchPlatformV2s().singleOrNull { it.uid == profile.uid && it.enabled && it.apiUrl == profile.apiUrl }
                if (current != null) repository.refresh(current)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableError.value = "Could not refresh Gateway settings."
            }
        }
    }

    fun updateGateway(profile: PlatformV2, target: String? = null) = run(profile) { repository.updateGateway(it, target) }
    fun pair(profile: PlatformV2, link: String) = run(profile) { repository.pair(it, link) }
    fun finishPairing(profile: PlatformV2) = run(profile) { repository.finishPairing(it) }
    fun apply(profile: PlatformV2, revision: Long, changes: JsonObject, confirmEngineering: Boolean = false) = run(profile) { repository.apply(it, revision, changes, confirmEngineering) }
    fun revoke(profile: PlatformV2) = run(profile) { repository.revoke(it) }
    fun retryPending(profile: PlatformV2, reviewed: GatewayControlState) = run(profile) {
        repository.retryPending(it, requireNotNull(reviewed.pendingCommand), requireNotNull(reviewed.snapshot).principalId, requireNotNull(reviewed.targetEndpoint))
    }
    fun clearError() {
        mutableError.value = null
    }

    private fun run(expected: PlatformV2, operation: suspend (PlatformV2) -> Unit) {
        if (action?.isActive == true) return
        action = viewModelScope.launch {
            mutableBusy.value = true
            mutableError.value = null
            try {
                val current = settings.fetchPlatformV2s().singleOrNull { it.uid == expected.uid && it.enabled && it.apiUrl == expected.apiUrl }
                    ?: error("Selected Gateway connection changed.")
                operation(current)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Do not display remote response bodies, pairing codes, credentials or TLS details.
                mutableError.value = "Could not verify this operation. Check the pairing code, PC approval, clock, connection and certificate; refresh to reconcile a saved change."
            } finally {
                mutableBusy.value = false
            }
        }
    }
}
