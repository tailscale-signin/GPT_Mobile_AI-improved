package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.GatewayJobRecord
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAPI
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayCancellationOutcome
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Reading or attaching a job never invokes this explicit user control. */
@Singleton
class GatewayJobControl @Inject constructor(
    private val repository: GatewayJobRepository,
    private val history: GatewayJobClient,
    private val gateway: GatewayAPI,
    private val engineering: GatewayEngineeringClient
) {
    /** A confirmation preview contains no credentials and performs only reads. */
    suspend fun inspect(record: GatewayJobRecord): GatewayJobStopReview = inspectRoute(record).second

    /** Called only after confirmation. A retry reads the original job before another possible POST. */
    suspend fun stop(review: GatewayJobStopReview): GatewayJobStopResult {
        return try {
            val (config, fresh) = inspectRoute(review.record)
            fresh.observedResult?.let { return it }
            currentCoroutineContext().ensureActive()
            if (fresh.summary.jobKind == "engineering") {
                val job = engineering.get(config, fresh.summary.jobId, -1)
                verifyEngineering(fresh, job)
                engineeringResult(job)?.let { return it }
                currentCoroutineContext().ensureActive()
                val response = engineering.control(mutationConfig(fresh.record, config), job, "cancel")
                verifyEngineering(fresh, response)
                check(response.revision >= job.revision && response.executionBindingId == job.executionBindingId && response.executionBinding == job.executionBinding) { "The saved job execution identity changed." }
                engineeringResult(response) ?: GatewayJobStopResult(GatewayJobStopOutcome.UNKNOWN)
            } else {
                if (gateway.getCapabilities(config, forceRefresh = true)?.supportsCancellation != true) {
                    return GatewayJobStopResult(GatewayJobStopOutcome.UNAVAILABLE)
                }
                currentCoroutineContext().ensureActive()
                val response = gateway.cancelJob(fresh.summary.jobId, mutationConfig(fresh.record, config))
                if (response == null || response.jobId != fresh.summary.jobId) {
                    GatewayJobStopResult(GatewayJobStopOutcome.UNKNOWN)
                } else {
                    GatewayJobStopResult(
                        when (response.outcome) {
                            GatewayCancellationOutcome.CONFIRMED -> GatewayJobStopOutcome.CONFIRMED
                            GatewayCancellationOutcome.REQUESTED -> GatewayJobStopOutcome.REQUESTED
                            GatewayCancellationOutcome.NOT_CANCELED -> GatewayJobStopOutcome.ALREADY_TERMINAL
                            GatewayCancellationOutcome.UNKNOWN -> GatewayJobStopOutcome.UNKNOWN
                        }
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failed read or lost mutation response cannot establish remote cancellation.
            GatewayJobStopResult(GatewayJobStopOutcome.UNKNOWN)
        }
    }

    private suspend fun mutationConfig(record: GatewayJobRecord, frozen: ProviderRequestConfig): ProviderRequestConfig {
        val identity = history.identity(frozen)
        check(identity.gatewayId == record.gatewayId && identity.principalId == record.principalId) { "Restore the original gateway and account before confirming Stop." }
        check(repository.originalRoute(record.connectionProfileUid, record.endpoint).config == frozen) { "The gateway connection changed while Stop was being reviewed." }
        currentCoroutineContext().ensureActive()
        return frozen.withGatewayIdentity(identity)
    }

    private suspend fun inspectRoute(record: GatewayJobRecord): Pair<ProviderRequestConfig, GatewayJobStopReview> {
        requireGatewayIdentity(record.gatewayId, record.principalId)
        check(record.kind in setOf("engineering", "generation", "ordinary") && record.key == gatewayIdentityKey(record.gatewayId, record.principalId, record.kind, record.remoteJobId)) { "The saved job identity is unavailable." }
        val original = repository.summary(record)
        val route = repository.originalRoute(record.connectionProfileUid, record.endpoint)
        var cursor: String? = null
        val cursors = mutableSetOf<String>()
        repeat(20) {
            val page = history.catalog(route.config, cursor)
            check(page.gatewayId == record.gatewayId && page.principalId == record.principalId) { "Restore the original gateway and account before stopping this job." }
            val current = page.items.singleOrNull { it.jobKind == record.kind && it.jobId == record.remoteJobId }
            if (current != null) {
                check(original.jobId == current.jobId && original.jobKind == current.jobKind && original.conversationId == current.conversationId && original.profileId == current.profileId && original.model == current.model && original.repository == current.repository && original.branch == current.branch && original.base == current.base && current.revision >= original.revision) { "The saved job destination changed. Refresh Jobs before confirming Stop." }
                check(record.executionProfileUid == current.profileId && record.conversationKey == current.conversationId?.let { gatewayIdentityKey(record.gatewayId, record.principalId, it) }) { "The saved job conversation or profile changed." }
                return route.config to GatewayJobStopReview(record, current)
            }
            if (!page.hasMore) error("The original job is unavailable in this gateway account. No Stop request was sent.")
            cursor = requireNotNull(page.nextCursor).also { check(cursors.add(it)) { "The gateway catalog cursor did not advance." } }
        }
        error("The job could not be verified within the catalog read limit. No Stop request was sent.")
    }

    private fun verifyEngineering(review: GatewayJobStopReview, job: GatewayEngineeringJob) {
        check(job.id == review.summary.jobId && job.gatewayId == review.record.gatewayId && job.principalId == review.record.principalId && job.conversationId == review.summary.conversationId && job.repository == review.summary.repository && job.branch == review.summary.branch && job.revision >= review.summary.revision) { "The original engineering job identity changed. Stop was not confirmed." }
    }

    private fun engineeringResult(job: GatewayEngineeringJob): GatewayJobStopResult? = gatewayObservedStopResult(job.state, null, false)
}

data class GatewayJobStopReview(val record: GatewayJobRecord, val summary: GatewayJobSummary) {
    val observedResult: GatewayJobStopResult?
        get() = gatewayObservedStopResult(summary.state, summary.phase, summary.cancellationRequested)
            ?: if ("cancel" !in summary.allowedActions) GatewayJobStopResult(GatewayJobStopOutcome.UNAVAILABLE) else null
}

data class GatewayJobStopResult(val outcome: GatewayJobStopOutcome)

enum class GatewayJobStopOutcome { REQUESTED, CONFIRMED, ALREADY_TERMINAL, UNKNOWN, UNAVAILABLE }

private fun gatewayObservedStopResult(state: String, phase: String?, requested: Boolean): GatewayJobStopResult? {
    val states = listOfNotNull(state, phase).map(String::uppercase)
    return when {
        states.any { it in setOf("CANCELED", "CANCELLED") } -> GatewayJobStopResult(GatewayJobStopOutcome.CONFIRMED)
        states.any { it in setOf("COMPLETED", "SUCCEEDED", "FINISHED", "DONE", "FAILED", "ERROR", "EXPIRED") } -> GatewayJobStopResult(GatewayJobStopOutcome.ALREADY_TERMINAL)
        requested || states.any { it in setOf("CANCELLING", "CANCELING", "CANCEL_REQUESTED", "CANCELLATION_REQUESTED") } -> GatewayJobStopResult(GatewayJobStopOutcome.REQUESTED)
        else -> null
    }
}
