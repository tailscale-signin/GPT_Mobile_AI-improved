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
    val identity: String = researchHash("${config?.let(::gatewayDashboardIdentity) ?: gatewayProfileUid}|${config?.profileRevision.orEmpty()}|$model")

    override fun toString(): String = "GatewayAvailabilityTarget(identity=$identity, configured=${config != null}, needsAttention=${problem != null})"
}

internal data class GatewayAvailability(
    val identity: String,
    val status: GatewayAvailabilityStatus,
    val message: String,
    val checkedAtMillis: Long? = null,
    val gatewayInstanceId: String? = null,
    val receivedAtMonotonicMillis: Long? = null,
    val observationAgeAtReceiptMillis: Long = 0,
    val observedAtMillis: Long? = null
) {
    internal fun freshnessRemainingMillis(now: Long, elapsedMillis: Long? = null): Long {
        val elapsed = if (receivedAtMonotonicMillis != null && elapsedMillis != null) {
            elapsedMillis - receivedAtMonotonicMillis
        } else {
            checkedAtMillis?.let { now - it } ?: return 0
        }
        if (elapsed < 0 || observationAgeAtReceiptMillis !in 0 until GATEWAY_AVAILABILITY_FRESH_MILLIS) return 0
        return (GATEWAY_AVAILABILITY_FRESH_MILLIS - observationAgeAtReceiptMillis - elapsed).coerceAtLeast(0)
    }

    fun isFresh(now: Long, elapsedMillis: Long? = null): Boolean = freshnessRemainingMillis(now, elapsedMillis) > 0

    fun isAvailable(now: Long, elapsedMillis: Long? = null): Boolean = status in setOf(GatewayAvailabilityStatus.ONLINE, GatewayAvailabilityStatus.BUSY_QUEUE) && isFresh(now, elapsedMillis)

    fun at(now: Long, elapsedMillis: Long? = null): GatewayAvailability =
        if (checkedAtMillis != null && !isFresh(now, elapsedMillis)) {
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
            target.problem ?: "Choose a valid model for this connection.".takeIf { !validGatewayAvailabilityModel(profile.model) }
        )
    }

internal fun validGatewayAvailabilityModel(model: String): Boolean = model.isNotBlank() && model == model.trim() && model.length <= 512 && model.none(Char::isISOControl)

internal fun gatewayAvailability(target: GatewayAvailabilityTarget, observation: GatewayReadinessObservation?, now: Long, elapsedMillis: Long? = null): GatewayAvailability {
    // Both caches retain the original evidence age. Wall-clock corrections cannot
    // renew a cached client response when its monotonic receipt is available.
    val clientAge = observation?.let {
        val age = if (elapsedMillis != null && it.receivedAtMonotonicMillis != null) elapsedMillis - it.receivedAtMonotonicMillis else now - it.receivedAtMillis
        age.takeIf { delta -> delta >= 0 } ?: Long.MAX_VALUE
    } ?: 0
    val serverAge = observation?.observationAgeMillis ?: 0
    val age = if (serverAge < 0 || serverAge > Long.MAX_VALUE - clientAge) Long.MAX_VALUE else serverAge + clientAge
    fun state(status: GatewayAvailabilityStatus, message: String) = GatewayAvailability(
        target.identity,
        status,
        message,
        now,
        observation?.instanceId,
        elapsedMillis,
        age,
        observation?.observedAtMillis ?: observation?.receivedAtMillis
    ).at(now, elapsedMillis)
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
