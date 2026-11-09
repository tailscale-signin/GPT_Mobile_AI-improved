package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GatewayChatProfilePolicy
import dev.chungjungsoo.gptmobile.data.github.GatewayKnownProfileStore
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

internal data class GatewayMenuConnection(
    val profileUid: String,
    val name: String,
    val host: String,
    val routeIdentity: String,
    val availability: GatewayAvailability,
    val verifiedScope: GatewayVerifiedScope? = null,
    val model: String = "",
    val configurationRevision: String? = null
) {
    val canBrowseHistory: Boolean get() = availability.isConnected || verifiedScope != null
    val navigationIdentity: String get() = gatewayIdentityKey(routeIdentity, verifiedScope?.configurationRevision.orEmpty(), verifiedScope?.gatewayId.orEmpty(), verifiedScope?.principalId.orEmpty())
}

private data class GatewayMenuInputs(val targets: List<GatewayAvailabilityTarget>, val profiles: List<PlatformV2>, val scopes: Map<String, GatewayVerifiedScope>)

@OptIn(ExperimentalCoroutinesApi::class)
internal fun gatewayMenuConnections(
    settings: SettingRepository,
    repository: GatewayAvailabilityRepository,
    scopes: GatewayVerifiedScopeStore,
    knownProfiles: GatewayKnownProfileStore? = null
): Flow<List<GatewayMenuConnection>> =
    combine(settings.observePlatformV2s(), settings.observeFeatureSettings(), scopes.changes) { profiles, features, _ ->
        val unique = profiles.filter { profile -> profiles.count { it.uid == profile.uid } == 1 }
        val verified = unique.mapNotNull { profile -> scopes.verified(profile)?.let { profile.uid to it } }.toMap()
        val revisions = unique.associate { it.uid to scopes.configurationRevision(it) }
        val targets = gatewayMenuTargets(profiles, features).map { target ->
            target.copy(config = target.config?.copy(profileRevision = revisions[target.gatewayProfileUid]))
        }
        GatewayMenuInputs(targets, unique, verified)
    }
        .distinctUntilChanged()
        .flatMapLatest { inputs ->
            repository.observeBackground(inputs.targets).map { observations ->
                // Engineering bindings can share an endpoint, but only the profile's own
                // current route and model establish support for a new direct Gateway chat.
                if (knownProfiles != null) {
                    inputs.profiles.filter(GatewayChatProfilePolicy::configured).forEach { profile ->
                        val directIdentity = GatewayClientDiagnostics.routeIdentity(profile.engineeringConfig())
                        val target = inputs.targets.firstOrNull { target ->
                            target.gatewayProfileUid == profile.uid &&
                                target.model == profile.model &&
                                target.config?.let(GatewayClientDiagnostics::routeIdentity) == directIdentity
                        }
                        val direct = target?.let { observations[it.profileUid] }
                        val revision = target?.config?.profileRevision
                        if (direct != null && revision != null) {
                            when {
                                direct.status in setOf(GatewayAvailabilityStatus.NOT_GATEWAY, GatewayAvailabilityStatus.INCOMPATIBLE) -> knownProfiles.forget(profile, revision)
                                direct.instanceId != null && (direct.isConnected || direct.lastObservedStatus in confirmedGatewayStatuses) -> knownProfiles.remember(profile, direct.instanceId, revision, direct.conversationSyncSupported)
                            }
                        }
                    }
                }
                menuConnections(inputs, observations)
            }
        }

private val confirmedGatewayStatuses = setOf(
    GatewayAvailabilityStatus.ONLINE,
    GatewayAvailabilityStatus.BUSY_QUEUE,
    GatewayAvailabilityStatus.MODEL_LOADING,
    GatewayAvailabilityStatus.MODEL_UNAVAILABLE,
    GatewayAvailabilityStatus.NOT_READY,
    GatewayAvailabilityStatus.DRAINING
)

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
            inputs.scopes[uid],
            target.model,
            config.profileRevision
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
            inputs.scopes[profile.uid],
            profile.model
        )
    }
}

/** Navigation observes the process monitor; mounting Home never starts another probe loop. */
@HiltViewModel
internal class GatewayMenuViewModel @Inject constructor(monitor: GatewayMenuMonitor) : ViewModel() {
    val connections = monitor.connections
}
