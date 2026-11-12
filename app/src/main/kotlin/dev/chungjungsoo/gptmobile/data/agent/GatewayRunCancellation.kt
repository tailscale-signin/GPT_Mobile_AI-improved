package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.delegation.CancellationOrigin
import dev.chungjungsoo.gptmobile.data.network.ApiCredentialRotator
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAPI
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayCancellationOutcome
import kotlinx.coroutines.CancellationException

/** A lifecycle detach never grants authority to stop an accepted server job. */
internal fun requestsRemoteGatewayCancellation(origin: CancellationOrigin, interrupted: Boolean): Boolean =
    !interrupted &&
        origin in setOf(
            CancellationOrigin.USER_WHOLE_RUN,
            CancellationOrigin.USER_PRIMARY_ONLY,
            CancellationOrigin.PERMISSION_REVOKED
        )

internal data class GatewayStopReceipt(val code: String, val message: String) {
    val retainedError: String get() = "$code: $message"
}

/** The terminal Android observer state does not establish the remote job outcome. */
internal enum class GatewayRunStopState(val label: String) {
    LOCAL_WAIT_STOPPED("Stopped waiting locally · remote status unconfirmed"),
    REMOTE_REQUESTED("Stopped waiting locally · remote cancellation requested"),
    REMOTE_CONFIRMED("Gateway cancellation confirmed"),
    REMOTE_ALREADY_TERMINAL("Stopped waiting locally · gateway job already finished")
}

internal fun gatewayRunStopState(run: AgentRun): GatewayRunStopState? {
    if (run.status != AgentRunStatus.CANCELED) return null
    val code = run.terminalError?.substringBefore(':')?.trim()
    return when (code) {
        "GATEWAY_CANCELLATION_CONFIRMED" -> GatewayRunStopState.REMOTE_CONFIRMED.takeIf { !run.gatewayJobId.isNullOrBlank() }
        "GATEWAY_CANCELLATION_REQUESTED" -> GatewayRunStopState.REMOTE_REQUESTED.takeIf { !run.gatewayJobId.isNullOrBlank() }
        "GATEWAY_ALREADY_TERMINAL" -> GatewayRunStopState.REMOTE_ALREADY_TERMINAL.takeIf { !run.gatewayJobId.isNullOrBlank() }
        "GATEWAY_CANCELLATION_UNCONFIRMED", "LOCAL_WAIT_CANCELLED_REMOTE_TERMINATION_UNCONFIRMED" -> GatewayRunStopState.LOCAL_WAIT_STOPPED
        else -> GatewayRunStopState.LOCAL_WAIT_STOPPED.takeIf { !run.gatewayJobId.isNullOrBlank() }
    }
}

/** Uses the retained destination only; an edited profile cannot redirect Stop. */
internal suspend fun stopSavedGatewayRun(run: AgentRun, profile: PlatformV2?, gateway: GatewayAPI, canonicalConfig: suspend () -> ProviderRequestConfig? = { null }): GatewayStopReceipt {
    val jobId = run.gatewayJobId
    val endpoint = run.gatewayBaseUrl
    if (jobId.isNullOrBlank() || endpoint.isNullOrBlank() || profile == null || !sameGatewayEndpoint(profile.apiUrl, endpoint)) {
        return GatewayStopReceipt("GATEWAY_CANCELLATION_UNCONFIRMED", "Stopped observing locally. Restore the original gateway connection to confirm whether the saved job stopped.")
    }
    val response = try {
        // A failed canonical authority lookup must never fall back to the legacy URL-only path.
        val config = canonicalConfig() ?: run {
            val token = ApiCredentialRotator.keysForNewRequest(profile.providerConnectionUid ?: profile.uid, profile.token).firstOrNull()
            ProviderRequestConfig(endpoint, token, profileUid = profile.uid)
        }
        check(sameGatewayEndpoint(config.apiUrl, endpoint) && config.profileUid == run.profileUid)
        gateway.cancelJob(jobId, config)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
    if (response == null || response.jobId != jobId) {
        return GatewayStopReceipt("GATEWAY_CANCELLATION_UNCONFIRMED", "Stopped observing locally. The gateway has not confirmed cancellation; its saved job is retained.")
    }
    return when (response.outcome) {
        GatewayCancellationOutcome.CONFIRMED -> GatewayStopReceipt("GATEWAY_CANCELLATION_CONFIRMED", "The gateway confirmed that the job stopped.")
        GatewayCancellationOutcome.REQUESTED -> GatewayStopReceipt("GATEWAY_CANCELLATION_REQUESTED", "Cancellation requested on the gateway. Check Jobs for confirmation.")
        GatewayCancellationOutcome.NOT_CANCELED -> GatewayStopReceipt("GATEWAY_ALREADY_TERMINAL", "The gateway job had already finished. Its saved result is available in Jobs.")
        GatewayCancellationOutcome.UNKNOWN -> GatewayStopReceipt("GATEWAY_CANCELLATION_UNCONFIRMED", "The gateway received Stop but did not confirm cancellation. Check the saved job status.")
    }
}
