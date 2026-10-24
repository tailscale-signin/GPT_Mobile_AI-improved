package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.context.PhysicalAttemptRejected
import dev.chungjungsoo.gptmobile.data.context.RequestAdmissionReceipt
import dev.chungjungsoo.gptmobile.data.localruntime.AdmissionOutcome
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** The accepted attempt is captured before queueing and never replaced by a later retry. */
internal class ResearchDispatchLifecycle(
    private val store: ResearchSessionStore,
    val rootWorkId: String,
    val attempt: ResearchAttempt,
    val persistent: Boolean,
    val runId: String,
    private val profileUid: String,
    private val validatePolicy: suspend () -> Unit = {}
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ResearchDispatchLifecycle>

    private suspend fun requireCurrent(): ResearchExecutionRecord {
        val live = store.execution(rootWorkId, persistent)
        if (live == null || live.attempt.attemptId != attempt.attemptId || live.attempt.generation != attempt.generation || live.attempt.state.terminal) {
            throw PhysicalAttemptRejected("RESEARCH_ATTEMPT_SUPERSEDED: this queued request no longer owns the accepted task; no provider request was dispatched.")
        }
        try {
            validatePolicy()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (stale: IllegalStateException) {
            store.updateExecution(
                rootWorkId,
                attempt,
                ResearchAttemptState.BLOCKED_CONFIGURATION,
                persistent,
                reason = "ACCEPTED_POLICY_CHANGED",
                runId = runId,
                profileUid = profileUid
            )
            throw PhysicalAttemptRejected("RESEARCH_CONFIGURATION_CHANGED: accepted settings changed while this task was waiting. Review the Research configuration before resuming.")
        }
        return live
    }

    suspend fun validate() {
        requireCurrent()
    }

    suspend fun admission(state: AdmissionOutcome) {
        when (state) {
            is AdmissionOutcome.Waiting -> {
                requireCurrent()
                store.updateExecution(
                    rootWorkId,
                    attempt,
                    ResearchAttemptState.WAITING_RESOURCE,
                    persistent,
                    reason = if (state.benchmarkBlocker) "BENCHMARK_ACTIVE" else "RESOURCE_IN_USE",
                    runId = runId,
                    profileUid = profileUid,
                    queueMillis = state.queueMillis
                )
            }
            is AdmissionOutcome.Acquired -> {
                requireCurrent()
                store.updateExecution(
                    rootWorkId,
                    attempt,
                    ResearchAttemptState.ACCEPTED,
                    persistent,
                    runId = runId,
                    profileUid = profileUid,
                    queueMillis = state.lease.queueMillis
                )
            }
            is AdmissionOutcome.Released -> Unit
        }
    }

    suspend fun dispatch(physicalAttemptId: String, receipt: RequestAdmissionReceipt?) {
        val current = requireCurrent()
        val role = receipt?.role.orEmpty()
        val state = when {
            role.contains("synthesis") || role.contains("report") -> ResearchAttemptState.SYNTHESIZING
            role.startsWith("research-") || role in setOf("reviewer", "assist") -> ResearchAttemptState.ANALYZING
            else -> ResearchAttemptState.SEARCHING
        }
        val servingProfile = receipt?.runtime?.connectionId ?: profileUid
        store.updateExecution(
            rootWorkId,
            attempt,
            state,
            persistent,
            reason = "PHYSICAL_DISPATCH:$physicalAttemptId",
            runId = runId,
            profileUid = servingProfile.takeIf { it in current.accepted.participantUids },
            dispatched = true
        )
    }

    suspend fun terminal(state: ResearchAttemptState, reason: String?, checkpointId: String? = null, readableSources: Int? = null) {
        store.updateExecution(
            rootWorkId, attempt, state, persistent, reason = reason?.take(500), runId = runId,
            profileUid = profileUid, checkpointId = checkpointId, readableSources = readableSources
        )
    }
}
