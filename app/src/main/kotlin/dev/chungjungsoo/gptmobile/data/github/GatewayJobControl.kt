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

    /** Review the exact retained recovery without attaching it to a different local chat. */
    suspend fun inspectRecoveryApproval(record: GatewayJobRecord): GatewayEngineeringJob = inspectRecoveryRoute(record).second

    private suspend fun inspectRecoveryRoute(record: GatewayJobRecord): Pair<ProviderRequestConfig, GatewayEngineeringJob> {
        val (config, review) = inspectRoute(record, forApproval = true)
        check(review.summary.jobKind == "engineering") { "This saved job does not own an engineering recovery." }
        check(review.summary.recoveryJobId != null || (review.summary.recoveryOf != null && "approve" in review.summary.allowedActions)) { "This recovery no longer needs merge approval." }
        val root = engineering.get(config, review.summary.jobId, -1)
        verifyEngineering(review, root, forApproval = true)
        check(root.base == review.summary.base && !root.base.isNullOrBlank() && !root.executionBindingId.isNullOrBlank() && root.recoveryOf == review.summary.recoveryOf) { "The exact recovery revision is no longer available for approval." }
        val visited = mutableSetOf(root.id)
        var job = root
        var descendants = 0
        while (job.recoveryJobId != null) {
            val childId = requireNotNull(job.recoveryJobId)
            check(descendants < 5 && visited.add(childId)) { "The recovery chain is cyclic or exceeds its five-descendant review limit." }
            val child = engineering.get(config, childId, -1)
            check(
                child.id == childId &&
                    child.recoveryOf == job.id &&
                    child.gatewayId == root.gatewayId &&
                    child.principalId == root.principalId &&
                    child.repository == root.repository &&
                    child.base == root.base &&
                    child.executionBindingId == root.executionBindingId &&
                    child.executionBinding == root.executionBinding
            ) { "The recovery descendant no longer belongs to this saved job's execution authority." }
            // Each recovery owns its own branch and may have a different or no
            // conversation. Only the original saved conversation is attached here.
            job = child
            descendants++
        }
        check(
            job.recoveryOf != null &&
                job.state == "MERGE_READY" &&
                job.mergeReady &&
                job.pr != null &&
                job.headSha?.matches(Regex("[a-f0-9]{40}")) == true &&
                (descendants > 0 || "approve" in review.summary.allowedActions)
        ) { "The exact recovery revision is no longer available for approval." }
        return config to job
    }

    suspend fun approveRecovery(record: GatewayJobRecord, reviewed: GatewayEngineeringJob) {
        val (config, fresh) = inspectRecoveryRoute(record)
        check(
            fresh.id == reviewed.id &&
                fresh.revision == reviewed.revision &&
                fresh.pr == reviewed.pr &&
                fresh.headSha == reviewed.headSha &&
                fresh.recoveryOf == reviewed.recoveryOf &&
                fresh.repository == reviewed.repository &&
                fresh.base == reviewed.base &&
                fresh.executionBindingId == reviewed.executionBindingId &&
                fresh.executionBinding == reviewed.executionBinding
        ) { "The recovery changed. Reopen its approval before continuing." }
        val response = engineering.control(mutationConfig(record, config, forApproval = true), fresh, "approve")
        check(
            response.id == fresh.id &&
                response.revision > fresh.revision &&
                response.gatewayId == fresh.gatewayId &&
                response.principalId == fresh.principalId &&
                response.repository == fresh.repository &&
                response.base == fresh.base &&
                response.branch == fresh.branch &&
                response.recoveryOf == fresh.recoveryOf &&
                response.executionBindingId == fresh.executionBindingId &&
                response.executionBinding == fresh.executionBinding
        ) { "Approval outcome is unknown. Refresh the original recovery." }
    }

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
                            GatewayCancellationOutcome.UNAVAILABLE -> GatewayJobStopOutcome.UNAVAILABLE
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

    private suspend fun mutationConfig(record: GatewayJobRecord, frozen: ProviderRequestConfig, forApproval: Boolean = false): ProviderRequestConfig {
        val identity = history.identity(frozen)
        check(identity.gatewayId == record.gatewayId && identity.principalId == record.principalId) { if (forApproval) "Restore the original gateway and account before confirming merge approval." else "Restore the original gateway and account before confirming Stop." }
        check(repository.originalRoute(record.connectionProfileUid, record.endpoint, record.gatewayId, record.principalId).config == frozen) { if (forApproval) "The gateway connection changed while merge approval was being reviewed." else "The gateway connection changed while Stop was being reviewed." }
        currentCoroutineContext().ensureActive()
        return frozen.withGatewayIdentity(identity)
    }

    private suspend fun inspectRoute(record: GatewayJobRecord, forApproval: Boolean = false): Pair<ProviderRequestConfig, GatewayJobStopReview> {
        requireGatewayIdentity(record.gatewayId, record.principalId)
        check(record.kind in setOf("engineering", "generation", "ordinary") && record.key == gatewayIdentityKey(record.gatewayId, record.principalId, record.kind, record.remoteJobId)) { "The saved job identity is unavailable." }
        val original = repository.summary(record)
        val route = repository.originalRoute(record.connectionProfileUid, record.endpoint, record.gatewayId, record.principalId)
        var cursor: String? = null
        val cursors = mutableSetOf<String>()
        repeat(20) {
            val page = history.catalog(route.config, cursor)
            check(page.gatewayId == record.gatewayId && page.principalId == record.principalId) { if (forApproval) "Restore the original gateway and account before reviewing this recovery merge approval." else "Restore the original gateway and account before stopping this job." }
            val current = page.items.singleOrNull { it.jobKind == record.kind && it.jobId == record.remoteJobId }
            if (current != null) {
                check(original.jobId == current.jobId && original.jobKind == current.jobKind && original.conversationId == current.conversationId && original.profileId == current.profileId && original.model == current.model && original.repository == current.repository && original.branch == current.branch && original.base == current.base && current.revision >= original.revision) { if (forApproval) "The saved job destination changed. Refresh Jobs before confirming merge approval." else "The saved job destination changed. Refresh Jobs before confirming Stop." }
                check(record.executionProfileUid == current.profileId && record.conversationKey == current.conversationId?.let { gatewayIdentityKey(record.gatewayId, record.principalId, it) }) { "The saved job conversation or profile changed." }
                return route.config to GatewayJobStopReview(record, current)
            }
            if (!page.hasMore) error(if (forApproval) "The original recovery job is unavailable in this gateway account. No merge approval request was sent." else "The original job is unavailable in this gateway account. No Stop request was sent.")
            cursor = requireNotNull(page.nextCursor).also { check(cursors.add(it)) { "The gateway catalog cursor did not advance." } }
        }
        error(if (forApproval) "The recovery job could not be verified within the catalog read limit. No merge approval request was sent." else "The job could not be verified within the catalog read limit. No Stop request was sent.")
    }

    private fun verifyEngineering(review: GatewayJobStopReview, job: GatewayEngineeringJob, forApproval: Boolean = false) {
        check(job.id == review.summary.jobId && job.gatewayId == review.record.gatewayId && job.principalId == review.record.principalId && job.conversationId == review.summary.conversationId && job.repository == review.summary.repository && job.branch == review.summary.branch && job.revision >= review.summary.revision) { if (forApproval) "The original recovery job identity changed. Merge approval was not confirmed." else "The original engineering job identity changed. Stop was not confirmed." }
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
