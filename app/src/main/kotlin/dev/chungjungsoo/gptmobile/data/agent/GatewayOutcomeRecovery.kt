package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformWhile

/** A missing stream response is not proof that gateway-owned tools did not execute. */
internal object GatewayOutcomeRecovery {
    const val ERROR_CODE = "GATEWAY_OUTCOME_UNKNOWN"
    const val PARKED_RESULT_PREFIX = "GATEWAY_OUTCOME_UNKNOWN: GATEWAY_RECOVERY_EXPIRED"
    const val ISOLATED_RESULT_PREFIX = "GATEWAY_ISOLATED_RESULT_RECOVERED:"
    private const val LEGACY_CATEGORY = "REMOTE_OUTCOME_UNKNOWN"

    class UnreconciledTask :
        IllegalStateException(
            "$ERROR_CODE: This task has an unreconciled gateway request that may already have performed actions. " +
                "Resume and Retry are blocked until its saved job result is reconciled. Check the gateway and remote results; no new model or tool request was sent."
        )

    /** A parked read remains recoverable; unrelated blocked/canceled work is never reopened. */
    fun canReadSavedResult(run: AgentRun): Boolean = run.status == AgentRunStatus.INTERRUPTED ||
        run.status == AgentRunStatus.BLOCKED &&
        run.terminalError?.startsWith(PARKED_RESULT_PREFIX) == true ||
        run.status in setOf(AgentRunStatus.FAILED, AgentRunStatus.PARTIAL, AgentRunStatus.EVIDENCE_MISSING) &&
        requiresReconciliation(run.terminalError.orEmpty()) &&
        !run.terminalError.orEmpty().contains("GATEWAY_SAVED_RESULT_") &&
        !run.terminalError.orEmpty().contains("GATEWAY_RECOVERED_RESULT_UNVERIFIED")

    fun requiresReconciliation(message: String, errorCode: String? = null): Boolean =
        errorCode.equals(ERROR_CODE, ignoreCase = true) ||
            errorCode.equals(LEGACY_CATEGORY, ignoreCase = true) ||
            message.contains(ERROR_CODE, ignoreCase = true) ||
            message.contains(LEGACY_CATEGORY, ignoreCase = true)

    /** Read persisted task identity and receipts; a Resume/Retry descriptor cannot grant replay permission. */
    fun requireTaskReconciled(chatId: Int, sourceMessageId: Int, messages: List<MessageV2>, runs: List<AgentRun>) {
        val rootWorkId = "$chatId:$sourceMessageId"
        fun List<AssistantTimelineItem>.belongsToTask(): Boolean = any { it.recovery?.rootWorkId == rootWorkId }
        fun List<AssistantTimelineItem>.hasUnknownOutcome(): Boolean = any { requiresReconciliation(it.runError.orEmpty(), it.profileFailure?.category) }
        val taskMessages = messages.filter { message ->
            message.chatId == chatId &&
                message.platformType != null &&
                (
                    message.linkedMessageId == sourceMessageId || message.timeline.belongsToTask() || message.revisions.any { it.timeline.belongsToTask() }
                    )
        }
        val taskMessageIds = taskMessages.map { it.id }.toSet()
        val taskRuns = runs.filter { it.chatId == chatId && (it.userMessageId == sourceMessageId || it.assistantMessageId in taskMessageIds) }
        fun reconciled(runId: String?, assistantMessageId: Int): Boolean = taskRuns.any { run ->
            run.runId == runId &&
                run.assistantMessageId == assistantMessageId &&
                (
                    run.status == AgentRunStatus.COMPLETED &&
                        run.terminalError.isNullOrBlank() ||
                        run.status == AgentRunStatus.EVIDENCE_MISSING &&
                        run.terminalError?.startsWith(ISOLATED_RESULT_PREFIX) == true
                    ) &&
                run.completedAt != null &&
                !run.gatewayJobId.isNullOrBlank() &&
                !run.gatewayBaseUrl.isNullOrBlank()
        }
        val unknownRun = taskRuns.any { requiresReconciliation(it.terminalError.orEmpty()) }
        val unknownMessage = taskMessages.any { message ->
            message.timeline.hasUnknownOutcome() &&
                !reconciled(message.currentRunId, message.id) ||
                message.revisions.any { it.timeline.hasUnknownOutcome() && !reconciled(it.runId, message.id) }
        }
        if (unknownRun || unknownMessage) throw UnreconciledTask()
    }
}

/** Deliver the uncertainty receipt, then stop the same stream before a trailing error can replace it. */
internal fun Flow<ProviderEvent>.stopAtUnknownGatewayOutcome(): Flow<ProviderEvent> = transformWhile { event ->
    emit(event)
    event !is ProviderEvent.Failed || !GatewayOutcomeRecovery.requiresReconciliation(event.message, event.errorCode)
}
