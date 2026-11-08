package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GitHubAutonomousEngineeringPolicy
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.research.researchHash

internal const val GATEWAY_AVAILABILITY_FRESH_MILLIS = 20_000L
internal const val GATEWAY_AVAILABILITY_POLL_MILLIS = 10_000L

internal enum class GatewayAvailabilityStatus { ONLINE, BUSY_QUEUE, MODEL_UNAVAILABLE, CHECKING, ATTENTION, OFFLINE }

/** An explicit saved connection and the exact model selected for execution. */
internal data class GatewayAvailabilityTarget(
    val profileUid: String,
    val gatewayProfileUid: String,
    val model: String,
    val config: ProviderRequestConfig?,
    val problem: String? = null
) {
    val identity: String = researchHash("${config?.let(::gatewayDashboardIdentity) ?: gatewayProfileUid}|$model")
}

internal data class GatewayAvailability(
    val identity: String,
    val status: GatewayAvailabilityStatus,
    val message: String,
    val checkedAtMillis: Long? = null,
    val gatewayInstanceId: String? = null
) {
    fun isFresh(now: Long): Boolean = checkedAtMillis?.let { now - it in 0 until GATEWAY_AVAILABILITY_FRESH_MILLIS } == true

    fun isAvailable(now: Long): Boolean = status in setOf(GatewayAvailabilityStatus.ONLINE, GatewayAvailabilityStatus.BUSY_QUEUE) && isFresh(now)

    fun at(now: Long): GatewayAvailability =
        if (checkedAtMillis != null && !isFresh(now)) {
            copy(status = GatewayAvailabilityStatus.CHECKING, message = "Checking model availability…")
        } else {
            this
        }
}

/** Ordinary inference and GitHub engineering retain their separately selected connections. */
internal fun gatewayAvailabilityTargets(profiles: List<PlatformV2>, features: AppFeatureSettings, engineering: Boolean): List<GatewayAvailabilityTarget> =
    profiles.filter { it.enabled }.mapNotNull { profile ->
        val gatewayUid = if (engineering) {
            features.githubProfileOverrides[profile.uid]?.engineeringGatewayProfileUid?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        } else {
            profile.uid.takeIf { GitHubAutonomousEngineeringPolicy.supportsSavedJobs(profile) } ?: return@mapNotNull null
        }
        val target = gatewayDashboardTarget(profile.uid, profiles, gatewayUid) ?: return@mapNotNull null
        GatewayAvailabilityTarget(
            profile.uid,
            gatewayUid,
            profile.model,
            target.config,
            target.problem ?: "Choose a model for this connection.".takeIf { profile.model.isBlank() }
        )
    }

internal fun gatewayAvailability(target: GatewayAvailabilityTarget, observation: GatewayReadinessObservation?, now: Long): GatewayAvailability {
    fun state(status: GatewayAvailabilityStatus, message: String) = GatewayAvailability(target.identity, status, message, observation?.receivedAtMillis ?: now, observation?.instanceId)
    if (observation == null) return state(GatewayAvailabilityStatus.ATTENTION, "This connection does not report model availability.")
    if (observation.modelLoading == true) return state(GatewayAvailabilityStatus.ATTENTION, "Connected · selected model is loading")
    if (observation.selectedModelAvailable == false) return state(GatewayAvailabilityStatus.MODEL_UNAVAILABLE, "Connected · selected model unavailable")
    if (observation.draining) return state(GatewayAvailabilityStatus.ATTENTION, "Connected · new work is paused")
    if (!observation.ready || observation.generationReady != true || observation.selectedModelAvailable != true) {
        return state(GatewayAvailabilityStatus.ATTENTION, "Connected · model readiness needs attention")
    }
    if (observation.availableWorkers == 0L) {
        return if (observation.canAccept == true && observation.canQueue == true) {
            state(GatewayAvailabilityStatus.BUSY_QUEUE, "Busy · new work can queue")
        } else {
            state(GatewayAvailabilityStatus.ATTENTION, "Busy · queue availability is unverified")
        }
    }
    if (observation.canAccept == false) return state(GatewayAvailabilityStatus.ATTENTION, "Connected · new work is unavailable")
    return state(GatewayAvailabilityStatus.ONLINE, "Selected model is ready").at(now)
}
