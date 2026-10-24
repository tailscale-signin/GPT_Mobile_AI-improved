package dev.chungjungsoo.gptmobile.data.research

import kotlinx.serialization.Serializable

@Serializable
enum class ResearchAttemptState {
    ACCEPTED,
    BLOCKED_CONFIGURATION,
    WAITING_RESOURCE,
    SEARCHING,
    FETCHING,
    ANALYZING,
    SYNTHESIZING,
    VALIDATING,
    COMPLETED,
    PARTIAL,
    FAILED_NO_EVIDENCE,
    FAILED_DELIVERY,
    CANCELLED,
    INTERRUPTED;

    val terminal: Boolean get() = this in setOf(COMPLETED, PARTIAL, FAILED_NO_EVIDENCE, FAILED_DELIVERY, CANCELLED, INTERRUPTED)
}

@Serializable
enum class ResearchNextAction { SCHEDULE, WAIT_FOR_RESOURCE, REVIEW_CONFIGURATION, RETRY_ACQUISITION, RETRY_DELIVERY, RECONCILE_REQUESTS, EXPLICIT_RESUME, NONE }

@Serializable
data class ResearchAttempt(
    val attemptId: String,
    val taskId: String,
    val generation: Long,
    val state: ResearchAttemptState = ResearchAttemptState.ACCEPTED,
    val blockedReason: String? = null,
    val checkpointId: String? = null,
    val evidenceRevision: String? = null,
    val readableSources: Int = 0,
    val dispatchedRunIds: Set<String> = emptySet(),
    val nextAction: ResearchNextAction = ResearchNextAction.SCHEDULE,
    val terminalOutcome: ResearchAttemptState? = null,
    val queueMillis: Long = 0,
    val updatedAt: Long,
    val recoveryActionId: String? = null
)

@Serializable
data class ResearchSchedulingAction(val actionId: String, val attemptId: String, val generation: Long, val action: ResearchNextAction, val acknowledged: Boolean = false)

@Serializable
data class ResearchOperationalReceipt(
    val sequence: Long,
    val attemptId: String,
    val generation: Long,
    val state: ResearchAttemptState,
    val runId: String? = null,
    val profileUid: String? = null,
    val dispatched: Boolean = false,
    val code: String? = null,
    val queueMillis: Long = 0,
    val time: Long,
    val actionId: String? = null
)

/**
 * This atomic record is the acceptance/execution authority. Task files, Room rows, cards and
 * exports are projections. The complete accepted request is durable before any scheduling.
 */
@Serializable
data class ResearchExecutionRecord(
    val schemaVersion: Int = 1,
    val accepted: AcceptedResearchTask,
    val attempt: ResearchAttempt,
    val previousAttempts: List<ResearchAttempt> = emptyList(),
    val scheduling: ResearchSchedulingAction,
    val receipts: List<ResearchOperationalReceipt> = emptyList()
) {
    fun validated(): ResearchExecutionRecord = apply {
        require(schemaVersion == 1 && accepted.taskId == researchHash("task:${accepted.rootWorkId}"))
        require(accepted.taskText.isNotBlank() && accepted.participantUids.isNotEmpty())
        require(attempt.taskId == accepted.taskId && attempt.generation >= 0)
        require(scheduling.attemptId == attempt.attemptId && scheduling.generation == attempt.generation)
        require(attempt.terminalOutcome == attempt.state.takeIf { it.terminal })
        require(previousAttempts.all { it.taskId == accepted.taskId && it.state.terminal && it.generation < attempt.generation })
    }

    fun transition(
        expectedAttemptId: String,
        expectedGeneration: Long,
        state: ResearchAttemptState,
        now: Long,
        reason: String? = null,
        runId: String? = null,
        profileUid: String? = null,
        dispatched: Boolean = false,
        checkpointId: String? = attempt.checkpointId,
        evidenceRevision: String? = attempt.evidenceRevision,
        readableSources: Int = attempt.readableSources,
        queueMillis: Long = attempt.queueMillis
    ): ResearchExecutionRecord {
        if (expectedAttemptId != attempt.attemptId || expectedGeneration != attempt.generation || attempt.state.terminal) return this
        require(profileUid == null || profileUid in accepted.participantUids)
        require(readableSources >= 0 && queueMillis >= 0)
        if (state in setOf(ResearchAttemptState.COMPLETED, ResearchAttemptState.PARTIAL)) require(!checkpointId.isNullOrBlank()) { "A delivered Research outcome requires a retained delivery receipt" }
        val action = when (state) {
            ResearchAttemptState.ACCEPTED -> ResearchNextAction.SCHEDULE
            ResearchAttemptState.WAITING_RESOURCE -> ResearchNextAction.WAIT_FOR_RESOURCE
            ResearchAttemptState.BLOCKED_CONFIGURATION -> ResearchNextAction.REVIEW_CONFIGURATION
            ResearchAttemptState.FAILED_NO_EVIDENCE -> ResearchNextAction.RETRY_ACQUISITION
            ResearchAttemptState.FAILED_DELIVERY -> ResearchNextAction.RETRY_DELIVERY
            ResearchAttemptState.INTERRUPTED -> if (attempt.dispatchedRunIds.isEmpty() && !dispatched) ResearchNextAction.EXPLICIT_RESUME else ResearchNextAction.RECONCILE_REQUESTS
            else -> ResearchNextAction.NONE
        }
        val activeStates = setOf(ResearchAttemptState.SEARCHING, ResearchAttemptState.FETCHING, ResearchAttemptState.ANALYZING, ResearchAttemptState.SYNTHESIZING, ResearchAttemptState.VALIDATING)
        val aggregateState = when {
            attempt.state == ResearchAttemptState.WAITING_RESOURCE && state == ResearchAttemptState.ACCEPTED -> attempt.state
            attempt.state in activeStates && state in setOf(ResearchAttemptState.ACCEPTED, ResearchAttemptState.WAITING_RESOURCE, ResearchAttemptState.BLOCKED_CONFIGURATION) -> attempt.state
            else -> state
        }
        val next = attempt.copy(
            state = aggregateState, blockedReason = if (aggregateState == state) reason else attempt.blockedReason, checkpointId = checkpointId, evidenceRevision = evidenceRevision, readableSources = readableSources,
            dispatchedRunIds = attempt.dispatchedRunIds + listOfNotNull(runId.takeIf { dispatched }), nextAction = if (aggregateState != state) attempt.nextAction else action,
            terminalOutcome = aggregateState.takeIf { it.terminal }, queueMillis = maxOf(attempt.queueMillis, queueMillis), updatedAt = now
        )
        val same = next.copy(updatedAt = attempt.updatedAt) == attempt
        val last = receipts.lastOrNull()
        if (same && last?.state == state && last.runId == runId && last.profileUid == profileUid && last.dispatched == dispatched && last.code == reason && last.queueMillis == queueMillis) return this
        val receipt = ResearchOperationalReceipt((receipts.lastOrNull()?.sequence ?: 0) + 1, attempt.attemptId, attempt.generation, state, runId, profileUid, dispatched, reason, queueMillis, now)
        return copy(attempt = next, scheduling = scheduling.copy(action = next.nextAction, acknowledged = next.nextAction !in setOf(ResearchNextAction.SCHEDULE, ResearchNextAction.WAIT_FOR_RESOURCE)), receipts = (receipts + receipt).takeLast(256)).validated()
    }

    fun resume(actionId: String, now: Long, reconciled: Boolean = false): ResearchExecutionRecord {
        require(actionId.isNotBlank())
        if (attempt.recoveryActionId == actionId || previousAttempts.any { it.recoveryActionId == actionId }) return this
        require(attempt.state.terminal || attempt.state == ResearchAttemptState.BLOCKED_CONFIGURATION) { "Research already has an active attempt" }
        require(attempt.nextAction != ResearchNextAction.RECONCILE_REQUESTS || reconciled) { "RESEARCH_DISPATCH_RECONCILIATION_REQUIRED" }
        val previous = if (attempt.state.terminal) attempt else attempt.copy(state = ResearchAttemptState.INTERRUPTED, terminalOutcome = ResearchAttemptState.INTERRUPTED, nextAction = ResearchNextAction.EXPLICIT_RESUME)
        val generation = Math.addExact(attempt.generation, 1)
        val id = researchHash("${accepted.taskId}:attempt:$generation:$actionId")
        val next = ResearchAttempt(id, accepted.taskId, generation, checkpointId = attempt.checkpointId, evidenceRevision = attempt.evidenceRevision, readableSources = attempt.readableSources, updatedAt = now, recoveryActionId = actionId)
        // A reviewed unknown request is still unknown. This receipt records the user's explicit
        // authorization of a new generation, never proof of remote rejection or completion.
        val receipt = ResearchOperationalReceipt(
            sequence = (receipts.lastOrNull()?.sequence ?: 0) + 1,
            attemptId = attempt.attemptId,
            generation = attempt.generation,
            state = previous.state,
            dispatched = false,
            code = if (attempt.nextAction == ResearchNextAction.RECONCILE_REQUESTS) "UNKNOWN_REMOTE_REQUESTS_REVIEWED_NEW_ATTEMPT_AUTHORIZED" else "EXPLICIT_RETRY_AUTHORIZED",
            queueMillis = attempt.queueMillis,
            time = now,
            actionId = actionId
        )
        return copy(attempt = next, previousAttempts = previousAttempts + previous, scheduling = ResearchSchedulingAction(researchHash("schedule:$id"), id, generation, ResearchNextAction.SCHEDULE), receipts = (receipts + receipt).takeLast(256)).validated()
    }

    companion object {
        fun accept(task: AcceptedResearchTask, legacy: Boolean = false): ResearchExecutionRecord {
            val id = researchHash("${task.taskId}:attempt:0")
            val state = if (legacy) ResearchAttemptState.INTERRUPTED else ResearchAttemptState.ACCEPTED
            val action = if (legacy) ResearchNextAction.EXPLICIT_RESUME else ResearchNextAction.SCHEDULE
            return ResearchExecutionRecord(accepted = task, attempt = ResearchAttempt(id, task.taskId, 0, state, nextAction = action, terminalOutcome = state.takeIf { it.terminal }, updatedAt = task.acceptedAt), scheduling = ResearchSchedulingAction(researchHash("schedule:$id"), id, 0, action, acknowledged = legacy)).validated()
        }
    }
}
