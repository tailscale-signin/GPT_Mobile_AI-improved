package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GatewayVerifiedScope
import dev.chungjungsoo.gptmobile.data.github.GatewayVerifiedScopeStore
import dev.chungjungsoo.gptmobile.data.github.engineeringConfig
import dev.chungjungsoo.gptmobile.data.github.gatewayIdentityKey
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailability
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityRepository
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityStatus
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
    val availability: GatewayAvailability,
    val verifiedScope: GatewayVerifiedScope? = null
) {
    val canBrowseHistory: Boolean get() = availability.isConnected || verifiedScope != null
    val navigationIdentity: String get() = gatewayIdentityKey(routeIdentity, verifiedScope?.configurationRevision.orEmpty(), verifiedScope?.gatewayId.orEmpty(), verifiedScope?.principalId.orEmpty())
}

private data class GatewayMenuInputs(val targets: List<GatewayAvailabilityTarget>, val profiles: List<PlatformV2>, val scopes: Map<String, GatewayVerifiedScope>)

@OptIn(ExperimentalCoroutinesApi::class)
internal fun gatewayMenuConnections(settings: SettingRepository, repository: GatewayAvailabilityRepository, scopes: GatewayVerifiedScopeStore): Flow<List<GatewayMenuConnection>> =
    combine(settings.observePlatformV2s(), settings.observeFeatureSettings(), scopes.changes) { profiles, features, _ ->
        val unique = profiles.filter { profile -> profiles.count { it.uid == profile.uid } == 1 }
        val verified = unique.mapNotNull { profile -> scopes.verified(profile)?.let { profile.uid to it } }.toMap()
        GatewayMenuInputs(gatewayMenuTargets(profiles, features), unique, verified)
    }
        .distinctUntilChanged()
        .flatMapLatest { inputs -> repository.observe(inputs.targets).map { observations -> menuConnections(inputs, observations) } }

private fun menuConnections(inputs: GatewayMenuInputs, observations: Map<String, GatewayAvailability>): List<GatewayMenuConnection> {
    val discovered = inputs.targets.filter { it.config != null }.groupBy { it.gatewayProfileUid }.map { (uid, candidates) ->
        val target = candidates.firstOrNull { observations[it.profileUid]?.isOnline == true }
            ?: candidates.firstOrNull { observations[it.profileUid]?.isConnected == true } ?: candidates.first()
        val config = requireNotNull(target.config)
        GatewayMenuConnection(
            uid,
            target.gatewayName,
            runCatching { URI(config.apiUrl).authority }.getOrNull().orEmpty(),
            GatewayClientDiagnostics.routeIdentity(config),
            observations[target.profileUid] ?: GatewayAvailability(),
            inputs.scopes[uid]
        )
    }
    // A disabled profile or missing model can still own readable history. This never grants Online.
    return discovered + inputs.profiles.filter { it.uid in inputs.scopes && discovered.none { row -> row.profileUid == it.uid } }.map { profile ->
        GatewayMenuConnection(
            profile.uid,
            profile.name,
            runCatching { URI(profile.apiUrl).authority }.getOrNull().orEmpty(),
            GatewayClientDiagnostics.routeIdentity(profile.engineeringConfig()),
            GatewayAvailability(status = GatewayAvailabilityStatus.CONFIGURATION_REQUIRED),
            inputs.scopes[profile.uid]
        )
    }
}

/** Keeping this subscribed is a visible-screen decision; it never polls in the background. */
@HiltViewModel
internal class GatewayMenuViewModel @Inject constructor(settings: SettingRepository, repository: GatewayAvailabilityRepository, scopes: GatewayVerifiedScopeStore) : ViewModel() {
    val connections = gatewayMenuConnections(settings, repository, scopes)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0), emptyList())
}
