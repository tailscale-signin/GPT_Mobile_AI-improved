package dev.chungjungsoo.gptmobile.presentation.ui.home

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GatewayChatProfilePolicy
import dev.chungjungsoo.gptmobile.data.github.engineeringConfig
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityStatus
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayClientDiagnostics
import dev.chungjungsoo.gptmobile.data.queue.GatewayQueuedSelection
import java.net.URI

internal data class GatewayChatProfileChoice(
    val profileUid: String,
    val name: String,
    val model: String,
    val host: String,
    val status: GatewayAvailabilityStatus,
    val selection: GatewayQueuedSelection,
    val configurationRevision: String? = null
)

/** Provider family and a reachable URL are candidates, not proof of gateway support. */
internal object GatewayChatProfiles {
    fun choices(
        profiles: List<PlatformV2>,
        knownGatewayProfileUids: Set<String>,
        connections: List<GatewayMenuConnection>,
        configurationRevisions: Map<String, String> = emptyMap()
    ): List<GatewayChatProfileChoice> = GatewayChatProfilePolicy.eligible(profiles, knownGatewayProfileUids).filter { profile ->
        val observation = directConnection(profile, connections, configurationRevisions[profile.uid])?.availability
        observation?.status !in setOf(GatewayAvailabilityStatus.NOT_GATEWAY, GatewayAvailabilityStatus.INCOMPATIBLE) &&
            !(observation?.instanceId != null && (observation.isConnected || observation.lastObservedAtMillis != null) && !observation.conversationSyncSupported)
    }.map { profile ->
        val connection = directConnection(profile, connections, configurationRevisions[profile.uid])
        GatewayChatProfileChoice(
            profileUid = profile.uid,
            name = profile.name,
            model = profile.model,
            host = runCatching { URI(profile.apiUrl).authority }.getOrNull().orEmpty(),
            status = connection?.availability?.status ?: GatewayAvailabilityStatus.CHECKING,
            selection = GatewayQueuedSelection.capture(profile, profile.model),
            configurationRevision = configurationRevisions[profile.uid]
        )
    }

    private fun directConnection(profile: PlatformV2, connections: List<GatewayMenuConnection>, expectedRevision: String?): GatewayMenuConnection? =
        connections.firstOrNull {
            expectedRevision != null &&
                it.configurationRevision == expectedRevision &&
                it.profileUid == profile.uid &&
                it.model == profile.model &&
                it.routeIdentity == GatewayClientDiagnostics.routeIdentity(profile.engineeringConfig())
        }
}
