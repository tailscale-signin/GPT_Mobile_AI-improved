package dev.chungjungsoo.gptmobile.data.github

import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.agent.GatewayOutcomeRecovery
import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord
import dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.GatewaySubmissionRecord
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A durable, principal-scoped proof that this exact command did not dispatch. */
@Serializable
data class GatewayCommandRejection(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("gateway_id") val gatewayId: String,
    @SerialName("principal_id") val principalId: String,
    @SerialName("command_id") val commandId: String,
    @SerialName("job_kind") val jobKind: String,
    @SerialName("admission_state") val admissionState: String,
    @SerialName("error_code") val errorCode: String,
    @SerialName("http_status") val httpStatus: Int,
    @SerialName("failed_stage") val failedStage: String,
    @SerialName("dispatch_state") val dispatchState: String,
    @SerialName("inference_attempted") val inferenceAttempted: Boolean
) {
    internal fun verify(command: String) {
        requireGatewayIdentity(gatewayId, principalId)
        require(schemaVersion == 1 && commandId == command && jobKind in setOf("engineering", "generation"))
        require(admissionState == "rejected" && failedStage == "provider_admission" && dispatchState == "not_dispatched" && !inferenceAttempted)
        require(httpStatus in setOf(400, 403, 409, 412, 422, 429, 503) && safeEngineeringRejectionCode(errorCode) != null)
    }
}

class GatewayCommandRejected(val outcome: GatewayCommandRejection) :
    GatewayEngineeringRequestRejected(
        outcome.httpStatus,
        "The gateway retained a rejection for this command (${outcome.errorCode}; ${outcome.failedStage}). No job was dispatched. Repair the prerequisite and submit a new command.",
        errorCode = outcome.errorCode,
        failedStage = outcome.failedStage
    )

internal fun safeEngineeringRejectionCode(code: String?): String? = code?.takeIf {
    it in setOf(
        "engineering_connection_required", "engineering_scope_required", "engineering_state_or_input_changed", "queue_full", "conversation_conflict",
        "ENGINEERING_REPOSITORY_ACCESS_REQUIRED", "SOURCE_REVISION_CHANGED", "SOURCE_REVISION_REQUIRED", "VALIDATION_POLICY_REQUIRED",
        "ACCEPTANCE_CONTRACT_REQUIRED", "ACCEPTANCE_PRODUCER_REQUIRED", "VALIDATION_CHECKS_REQUIRED", "VALIDATION_CHECK_UNSUPPORTED", "VALIDATION_REVISION_UNAVAILABLE", "VALIDATION_RUNTIME_UNAVAILABLE"
    ) ||
        it.matches(Regex("(?:EXECUTION|SELECTED_ONLY|INFERENCE)_[A-Z0-9_]{1,80}"))
}

/** Caller first verifies the receipt's gateway/account against the frozen original authority. */
internal suspend fun retainGatewayCommandRejection(database: ChatDatabaseV2, expected: GatewaySubmissionRecord, outcome: GatewayCommandRejection) = database.withTransaction {
    outcome.verify(expected.commandId)
    val dao = database.gatewayJobDao()
    val current = dao.submission(expected.commandId) ?: return@withTransaction
    if (current.fingerprint != expected.fingerprint || current.payload != expected.payload || current.state !in GatewayCommandReconciliation.pendingStates) return@withTransaction
    dao.saveSubmission(current.copy(state = "REJECTED:${outcome.httpStatus}", receipt = Json.encodeToString(outcome)))
    val run = current.runId?.let { database.agentRunDao().getById(it) } ?: return@withTransaction
    val message = dao.localMessage(run.assistantMessageId) ?: return@withTransaction
    if (run.chatId != current.chatId ||
        run.userMessageId != current.userMessageId ||
        run.assistantMessageId != current.assistantMessageId ||
        run.profileUid != current.executionProfileUid ||
        run.gatewayJobId != null ||
        run.terminalError == "BACKUP_RESTORED" ||
        message.currentRunId != run.runId ||
        message.ownershipFence != run.ownershipFence
    ) {
        return@withTransaction
    }
    val notice = GatewayCommandRejected(outcome).message.orEmpty()
    val reason = "${GatewayOutcomeRecovery.REJECTED_COMMAND_PREFIX} ${outcome.errorCode}; ${outcome.failedStage}; not_dispatched. $notice"
    val at = System.currentTimeMillis() / 1000
    val changed = if (GatewayOutcomeRecovery.canReadSavedResult(run)) {
        database.agentRunDao().finishGatewayRecovery(run.runId, "BLOCKED", at, reason)
    } else {
        // A user-canceled or superseded run is not reactivated by a late receipt.
        database.agentRunDao().finishActive(run.runId, "BLOCKED", at, reason)
    }
    if (changed != 1) return@withTransaction
    val failure = ProfileFailureRecord(outcome.errorCode, retryAllowed = false, dispatched = false, remoteAcceptance = RemoteRequestAcceptance.NO, phase = outcome.failedStage, requestReplayAllowed = false, dispatchState = outcome.dispatchState, recoveryActions = listOf("REPAIR_PREREQUISITE", "SUBMIT_NEW_COMMAND"))
    val projected = message.copy(timeline = message.timeline + AssistantTimelineItem(AssistantTimelineItemType.NOTICE, content = notice, runError = reason, profileFailure = failure))
    if (dao.projectMessage(message.id, projected.content, projected.timeline, run.ownershipFence, run.runId) == 1) {
        database.agentPersistenceDao().publishReply(run, projected, "BLOCKED", at)
        dao.touchChat(run.chatId, at)
    }
}
