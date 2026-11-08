package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailability
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityRepository
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityTarget
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayClientDiagnostics
import dev.chungjungsoo.gptmobile.data.network.gateway.gatewayMenuTargets
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import java.net.URI
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal data class GatewayMenuConnection(
    val profileUid: String,
    val name: String,
    val host: String,
    val routeIdentity: String,
    val availability: GatewayAvailability
)

@OptIn(ExperimentalCoroutinesApi::class)
internal fun gatewayMenuConnections(settings: SettingRepository, repository: GatewayAvailabilityRepository): Flow<List<GatewayMenuConnection>> =
    combine(settings.observePlatformV2s(), settings.observeFeatureSettings(), ::gatewayMenuTargets)
        .distinctUntilChanged()
        .flatMapLatest { targets -> repository.observe(targets).map { observations -> menuConnections(targets, observations) } }

private fun menuConnections(targets: List<GatewayAvailabilityTarget>, observations: Map<String, GatewayAvailability>): List<GatewayMenuConnection> =
    targets.filter { it.config != null }.groupBy { it.gatewayProfileUid }.map { (uid, candidates) ->
        val target = candidates.firstOrNull { observations[it.profileUid]?.isOnline == true }
            ?: candidates.firstOrNull { observations[it.profileUid]?.isConnected == true } ?: candidates.first()
        val config = requireNotNull(target.config)
        GatewayMenuConnection(
            uid,
            target.gatewayName,
            runCatching { URI(config.apiUrl).authority }.getOrNull().orEmpty(),
            GatewayClientDiagnostics.routeIdentity(config),
            observations[target.profileUid] ?: GatewayAvailability()
        )
    }

/** Keeping this subscribed is a visible-screen decision; it never polls in the background. */
@HiltViewModel
internal class GatewayMenuViewModel @Inject constructor(settings: SettingRepository, repository: GatewayAvailabilityRepository) : ViewModel() {
    val connections = gatewayMenuConnections(settings, repository)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0), emptyList())
}
