package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.agent.CombinedAcceptedTurn
import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord
import dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao
import dev.chungjungsoo.gptmobile.data.database.dao.AgentRunDao
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/**
 * Runs after stale Room owners are interrupted and before the existing prompt dispatcher starts.
 * The atomic execution is authoritative; Room receipts repair its projection, never resend work.
 */
class ResearchStartupReconciler @Inject constructor(
    private val store: ResearchSessionStore,
    private val runs: AgentRunDao,
    private val messages: AgentPersistenceDao
) {
    suspend fun reconcile() {
        store.reconcileAllExecutions().forEach { execution ->
            if (execution.attempt.state.terminal || execution.attempt.state == ResearchAttemptState.BLOCKED_CONFIGURATION) return@forEach
            if (execution.accepted.targetSources <= 0 && execution.accepted.sourceScope.isBlank()) return@forEach
            try {
                reconcile(execution)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppLogRecorder.record("Research", "RECONCILIATION_PENDING · root=${execution.accepted.rootWorkId} · reason=${failure.javaClass.simpleName}", "W")
                // A readable authority can still retain the operational failure when a dependent
                // projection is unavailable. Unknown dispatched requests remain fenced from retry.
                runCatching {
                    store.updateExecution(execution.accepted.rootWorkId, execution.attempt, ResearchAttemptState.INTERRUPTED, true, reason = "RECOVERY_PROJECTION_UNAVAILABLE")
                }
            }
        }
    }

    private suspend fun reconcile(execution: ResearchExecutionRecord) {
        val accepted = execution.accepted
        val attempt = execution.attempt
        val ownedIds = attempt.dispatchedRunIds + execution.receipts.filter { it.attemptId == attempt.attemptId && it.generation == attempt.generation }.mapNotNull { it.runId }
        val related = runs.getByChatId(accepted.chatId).filter { run ->
            run.runId in ownedIds || ownedIds.isEmpty() && attempt.generation == 0L && run.userMessageId == accepted.originatingUserMessageId && run.profileUid in accepted.participantUids
        }
        if (related.any { it.status in setOf(AgentRunStatus.QUEUED, AgentRunStatus.RUNNING, AgentRunStatus.WAITING_PROVIDER_LIMIT, AgentRunStatus.WAITING_REMOTE_JOB, "STOPPING") }) return
        val retained = store.exportTurn(accepted.chatId, setOf(accepted.rootWorkId))
        val readable = retained.flatMap { it.snapshot.sources }.filter { it.readable }.distinctBy { source -> source.equivalenceGroup.ifBlank { source.canonicalUrl.ifBlank { source.url } } }.size
        val currentMessages = related.mapNotNull { run ->
            messages.recoveryMessage(run.assistantMessageId)?.takeIf { it.currentRunId == run.runId && it.ownershipFence == run.ownershipFence }?.let { run to it }
        }
        val combined = related.any { it.synthesisKey != null || CombinedAcceptedTurn.context(it.synthesisSnapshot) != null }
        val deliveries = currentMessages.filter { (run, _) ->
            !combined || run.synthesisKey != null || CombinedAcceptedTurn.context(run.synthesisSnapshot)?.role?.finalization == true
        }
        val complete = deliveries.firstNotNullOfOrNull { (run, message) -> delivery(run, message, complete = true) }.takeIf { readable > 0 }
        val partial = deliveries.firstNotNullOfOrNull { (run, message) -> delivery(run, message, complete = false) }.takeIf { readable > 0 }
        val allBlocked = related.isNotEmpty() &&
            currentMessages.size == related.size &&
            currentMessages.all { (run, message) ->
                run.status == AgentRunStatus.FAILED && (message.timeline.lastOrNull { it.profileFailure != null }?.profileFailure ?: run.terminalError?.let { ProfileFailureRecord.classify(it) })?.category in setOf("INCOMPATIBLE_CONFIGURATION", "TOOL_CHOICE_MISMATCH")
            }
        val state = when {
            complete != null -> ResearchAttemptState.COMPLETED
            related.any { it.status == AgentRunStatus.INTERRUPTED } || related.isEmpty() -> ResearchAttemptState.INTERRUPTED
            related.all { it.status == AgentRunStatus.CANCELED } -> ResearchAttemptState.CANCELLED
            partial != null -> ResearchAttemptState.PARTIAL
            allBlocked -> ResearchAttemptState.BLOCKED_CONFIGURATION
            readable > 0 -> ResearchAttemptState.FAILED_DELIVERY
            else -> ResearchAttemptState.FAILED_NO_EVIDENCE
        }
        val reasons = related.mapNotNull { it.terminalError }.distinct().joinToString(" | ").take(400)
        store.updateExecution(
            accepted.rootWorkId,
            attempt,
            state,
            true,
            reason = if (state == ResearchAttemptState.BLOCKED_CONFIGURATION) "ALL_SELECTED_PROFILES_INCOMPATIBLE" else "STARTUP_RECONCILIATION" + reasons.takeIf(String::isNotBlank)?.let { ":$it" }.orEmpty(),
            checkpointId = complete ?: partial,
            readableSources = readable
        )
    }

    private fun delivery(run: AgentRun, message: MessageV2, complete: Boolean): String? {
        if (message.content.isBlank()) return null
        val receipt = message.timeline.mapNotNull { it.researchDeliveryReceipt }.lastOrNull { it.matchesRevision(message.content) && it.evidenceAvailable }
        val quality = message.timeline.mapNotNull { it.combinedQuality }.lastOrNull { it.answerDigest == researchHash(message.content) }
        return if (complete) {
            if (run.status == AgentRunStatus.COMPLETED && (receipt?.accepted == true || quality?.let { it.complete && !it.correctionRequired && it.deliveredScopeStatus in setOf("COMPLETED", "COMPLETED_WITH_LIMITS") } == true)) receipt?.revisionId ?: quality?.answerDigest else null
        } else {
            if (receipt?.partial == true || quality?.let { !it.correctionRequired && it.deliveredScopeStatus == "COMPLETED_WITH_LIMITS" } == true) receipt?.revisionId ?: quality?.answerDigest else null
        }
    }
}
