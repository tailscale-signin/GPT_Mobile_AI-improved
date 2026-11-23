package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.context.RouteCapabilityCache
import dev.chungjungsoo.gptmobile.data.network.LocalMetadataReadAttempt
import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal data class GatewayAvailabilityProbe(
    val capabilities: GatewayCapabilities?,
    val readiness: GatewayReadinessObservation?,
    val capabilitiesFailure: GatewayAvailabilityFailure? = null,
    val readinessFailure: GatewayAvailabilityFailure? = null,
    /** Completion time of the metadata check, which may reuse a valid bounded cache. */
    val capabilitiesCheckedAtMillis: Long? = null,
    val readinessCheckedAtMillis: Long? = null,
    /** Original successful capability response time, retained across cache reuse. */
    val capabilitiesObservedAtMillis: Long? = null
)

internal data class GatewayAvailabilityFailure(val status: GatewayAvailabilityStatus, val reasonCode: String)

internal fun interface GatewayAvailabilitySource {
    suspend fun probe(config: ProviderRequestConfig, model: String, refresh: Boolean): GatewayAvailabilityProbe
}

/** Metadata only: no model requests, warmup, job creation, tools or management credentials. */
internal class GatewayAvailabilityNetworkSource(
    private val gateway: GatewayAPI,
    private val dashboard: GatewayDashboardClient
) : GatewayAvailabilitySource {
    private data class CapabilityObservation(val value: GatewayCapabilities, val observedAtMillis: Long)
    private val capabilities = RouteCapabilityCache<CapabilityObservation>({ System.nanoTime() / 1_000_000 }, positiveTtl = 10_000, negativeTtl = 60_000)

    override suspend fun probe(config: ProviderRequestConfig, model: String, refresh: Boolean): GatewayAvailabilityProbe {
        val key = "${GatewayClientDiagnostics.routeIdentity(config)}|${dev.chungjungsoo.gptmobile.data.research.researchHash(config.profileRevision.orEmpty())}|${LocalServiceHealth.networkEpoch}|${GatewayClientDiagnostics.runtimeEpoch(config)}"
        var capabilityObservedAt: Long? = null
        // Readiness and capabilities are independent observations. A timeout from /ready
        // must not discard a successful capability response or label a reachable gateway absent.
        return gatewayAvailabilityProbe(
            readCapabilities = {
                capabilities.getOrLoad(key, forceRefresh = refresh) {
                    gateway.getCapabilities(config, forceRefresh = refresh)?.let { CapabilityObservation(it, System.currentTimeMillis()) }
                }?.also { capabilityObservedAt = it.observedAtMillis }?.value
            },
            readReadiness = { dashboard.readiness(config, model, forceRefresh = refresh) }
        ).copy(capabilitiesObservedAtMillis = capabilityObservedAt)
    }
}

internal suspend fun gatewayAvailabilityProbe(
    readCapabilities: suspend () -> GatewayCapabilities?,
    readReadiness: suspend () -> GatewayReadinessObservation?,
    now: () -> Long = System::currentTimeMillis
): GatewayAvailabilityProbe = coroutineScope {
    data class Observation<T>(val value: T?, val failure: GatewayAvailabilityFailure?, val checkedAt: Long)
    suspend fun <T> observe(phase: String, read: suspend () -> T?): Observation<T> {
        val operationId = java.util.UUID.randomUUID().toString().replace("-", "")
        return try {
            // The observer and its bounded HTTP retries share one logical read identity.
            // Finish before the outer deadline so a stalled read cannot erase its sibling.
            withContext(LocalMetadataReadAttempt(0, operationId)) {
                withTimeout(7_000) { Observation(read(), null, now()) }
            }
        } catch (_: TimeoutCancellationException) {
            // Parent cancellation still owns the whole probe; only a read's own
            // deadline becomes an independent metadata failure.
            currentCoroutineContext().ensureActive()
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.recordEvent(
                "Gateway",
                "METADATA_DEADLINE_EXCEEDED",
                mapOf(
                    "operation_id" to operationId,
                    "incident_id" to operationId,
                    "deadline_owner" to "METADATA_READ",
                    "failedStage" to phase,
                    "errorCode" to "METADATA_DEADLINE_EXCEEDED",
                    "deadline_ms" to 7_000,
                    "inference_dispatched" to false
                ),
                "W"
            )
            Observation(null, GatewayAvailabilityFailure(GatewayAvailabilityStatus.CHECKING, "METADATA_DEADLINE_EXCEEDED"), now())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Observation(null, gatewayAvailabilityFailure(failure), now())
        }
    }
    val capabilities = async { observe("gateway_capabilities", readCapabilities) }
    val readiness = async { observe("gateway_readiness", readReadiness) }
    val capabilityResult = capabilities.await()
    val readinessResult = readiness.await()
    GatewayAvailabilityProbe(capabilityResult.value, readinessResult.value, capabilityResult.failure, readinessResult.failure, capabilityResult.checkedAt, readinessResult.checkedAt, capabilityResult.checkedAt.takeIf { capabilityResult.value != null })
}
