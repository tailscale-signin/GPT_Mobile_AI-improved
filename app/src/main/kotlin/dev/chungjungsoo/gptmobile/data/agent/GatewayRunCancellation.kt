package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
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

/** Uses the retained destination only; an edited profile cannot redirect Stop. */
internal suspend fun stopSavedGatewayRun(run: AgentRun, profile: PlatformV2?, gateway: GatewayAPI): GatewayStopReceipt {
    val jobId = run.gatewayJobId
    val endpoint = run.gatewayBaseUrl
    if (jobId.isNullOrBlank() || endpoint.isNullOrBlank() || profile == null || !sameGatewayEndpoint(profile.apiUrl, endpoint)) {
        return GatewayStopReceipt("GATEWAY_CANCELLATION_UNCONFIRMED", "Stopped observing locally. Restore the original gateway connection to confirm whether the saved job stopped.")
    }
    val response = try {
        val token = ApiCredentialRotator.keysForNewRequest(profile.providerConnectionUid ?: profile.uid, profile.token).firstOrNull()
        gateway.cancelJob(jobId, ProviderRequestConfig(endpoint, token, profileUid = profile.uid))
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
