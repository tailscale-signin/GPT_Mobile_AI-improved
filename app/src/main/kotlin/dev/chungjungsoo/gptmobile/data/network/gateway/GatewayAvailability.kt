package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GatewayActionReadinessEvidence
import dev.chungjungsoo.gptmobile.data.github.GitHubAutonomousEngineeringPolicy
import dev.chungjungsoo.gptmobile.data.github.engineeringConfig
import dev.chungjungsoo.gptmobile.data.github.engineeringGatewayProfile
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.net.URI

internal enum class GatewayAvailabilityStatus {
    CHECKING,
    ONLINE,
    BUSY_QUEUE,
    MODEL_LOADING,
    MODEL_UNAVAILABLE,
    NOT_READY,
    DRAINING,
    ACCESS_DENIED,
    INCOMPATIBLE,
    OFFLINE,
    CONFIGURATION_REQUIRED,
    NOT_GATEWAY
}

/** Status is evidence about this exact route/model, never an inference from a provider name. */
internal data class GatewayAvailability(
    val status: GatewayAvailabilityStatus = GatewayAvailabilityStatus.CHECKING,
    val gatewayName: String = "",
    val gatewayProfileUid: String = "",
    val engineeringBinding: Boolean = false,
    val observedAtMillis: Long? = null,
    val observedAtElapsedMillis: Long? = null,
    val instanceId: String? = null,
    /** Retained display evidence only; neither field grants current readiness. */
    val lastObservedStatus: GatewayAvailabilityStatus? = null,
    val lastObservedAtMillis: Long? = null,
    /** Server advertisement only. Saved-work actions verify their own route and identity. */
    val conversationSyncSupported: Boolean = false,
    val actionReadiness: GatewayActionReadinessEvidence = GatewayActionReadinessEvidence(),
    /** Independent metadata observations; none grants selected-model readiness by itself. */
    val capabilitiesCheckedAtMillis: Long? = null,
    val readinessCheckedAtMillis: Long? = null,
    val lastReachableAtMillis: Long? = null,
    val failedPhase: String? = null,
    val failureCode: String? = null,
    val capabilitiesFailureCode: String? = null,
    val readinessFailureCode: String? = null,
    val failedDependency: String? = null,
    val upstreamStatus: Int? = null,
    val recoveryAction: String? = null,
    val observationAgeMillis: Long? = null
) {
    /** Online describes generation for the selected model; it does not grant Jobs, engineering or graph access. */
    val isOnline: Boolean get() = status in setOf(GatewayAvailabilityStatus.ONLINE, GatewayAvailabilityStatus.BUSY_QUEUE)
    val isConnected: Boolean get() = status in setOf(GatewayAvailabilityStatus.ONLINE, GatewayAvailabilityStatus.BUSY_QUEUE, GatewayAvailabilityStatus.MODEL_LOADING, GatewayAvailabilityStatus.MODEL_UNAVAILABLE, GatewayAvailabilityStatus.NOT_READY, GatewayAvailabilityStatus.DRAINING)

    internal fun freshnessRemainingMillis(now: Long): Long {
        val observed = observedAtElapsedMillis ?: return 0
        val age = now - observed
        return if (now < observed || age < 0) 0 else (GATEWAY_AVAILABILITY_FRESH_MILLIS - age).coerceAtLeast(0)
    }

    internal fun fresh(now: Long): GatewayAvailability {
        if (observedAtElapsedMillis == null) return this
        return if (freshnessRemainingMillis(now) == 0L) {
            copy(status = GatewayAvailabilityStatus.CHECKING, lastObservedStatus = lastObservedStatus ?: status, lastObservedAtMillis = lastObservedAtMillis ?: observedAtMillis)
        } else {
            this
        }
    }

    internal fun unverified(status: GatewayAvailabilityStatus = GatewayAvailabilityStatus.CHECKING): GatewayAvailability = copy(
        status = status,
        observedAtElapsedMillis = null,
        lastObservedStatus = lastObservedStatus ?: this.status.takeIf { observedAtMillis != null },
        lastObservedAtMillis = lastObservedAtMillis ?: observedAtMillis
    )
}

internal const val GATEWAY_AVAILABILITY_POLL_MILLIS = 10_000L
internal const val GATEWAY_BACKGROUND_POLL_MILLIS = 180_000L
internal const val GATEWAY_AVAILABILITY_FRESH_MILLIS = 20_000L

internal data class GatewayAvailabilityTarget(
    val profileUid: String,
    val gatewayProfileUid: String,
    val gatewayName: String,
    val model: String,
    val config: ProviderRequestConfig?,
    val engineeringBinding: Boolean
) {
    override fun toString(): String = "GatewayAvailabilityTarget(profileUid=$profileUid, configured=${config != null}, engineeringBinding=$engineeringBinding)"
}

internal fun validGatewayAvailabilityModel(model: String): Boolean = model.isNotBlank() && model == model.trim() && model.length <= 512 && model.none(Char::isISOControl)

/**
 * A saved self-hosted route is only a discovery candidate. Only a valid capability and
 * exact-model readiness response can identify it as an online gateway. GitHub uses its
 * explicit engineering binding and never substitutes the inference route.
 */
internal fun gatewayAvailabilityTargets(
    profiles: List<PlatformV2>,
    features: AppFeatureSettings,
    forGitHub: Boolean
): List<GatewayAvailabilityTarget> = profiles.filter { it.enabled }.mapNotNull { selected ->
    if (profiles.count { it.uid == selected.uid } != 1) return@mapNotNull null
    val binding = features.githubProfileOverrides[selected.uid]?.engineeringGatewayProfileUid
    val gateway = if (forGitHub) {
        // A retained binding does not make a remote inference profile eligible for
        // saved engineering jobs, or make its remote model local to that gateway.
        if (!GitHubAutonomousEngineeringPolicy.supportsSavedJobs(selected)) return@mapNotNull null
        if (binding.isNullOrBlank()) return@mapNotNull null
        engineeringGatewayProfile(profiles, binding)
    } else {
        selected.takeIf { it.compatibleType in setOf(ClientType.LLAMA, ClientType.OLLAMA) } ?: return@mapNotNull null
    }
    GatewayAvailabilityTarget(
        selected.uid,
        gateway?.uid ?: binding.orEmpty(),
        gateway?.name.orEmpty(),
        selected.model,
        gateway?.takeIf { validGatewayAvailabilityRoute(it.apiUrl) && validGatewayAvailabilityModel(selected.model) }?.engineeringConfig(),
        forGitHub
    )
}

/**
 * Menu discovery includes each saved route and explicit binding. One selected model can
 * have both, so menu observation keys must not erase either connection's evidence.
 */
internal fun gatewayMenuTargets(profiles: List<PlatformV2>, features: AppFeatureSettings): List<GatewayAvailabilityTarget> =
    (gatewayAvailabilityTargets(profiles, features, true) + gatewayAvailabilityTargets(profiles, features, false))
        .filter { it.config != null }
        .distinctBy { it.gatewayProfileUid to it.model }
        .map { it.copy(profileUid = "gateway_menu:${it.gatewayProfileUid}:${researchHash(it.model)}") }

private fun validGatewayAvailabilityRoute(url: String): Boolean = runCatching {
    val uri = URI(url.trim())
    uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null
}.getOrDefault(false)
