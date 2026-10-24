package dev.chungjungsoo.gptmobile.data.agent

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
enum class CombinedBudgetPool { BASELINE, FINALIZATION, OPTIONAL }

@Serializable
enum class CombinedReservationStatus { RESERVED, SETTLED, UNKNOWN, RELEASED }

@Serializable
data class CombinedUsage(
    val resources: CombinedResourceQuota,
    val measuredInput: Boolean = false,
    val measuredOutput: Boolean = false,
    val cachedInputTokens: Long? = null,
    val visibleOutputTokens: Long? = null,
    val reasoningOutputTokens: Long? = null,
    val costMicros: Long? = null
)

@Serializable
data class CombinedReservation(
    val attemptId: String,
    val ownerKey: String,
    val role: CombinedRunRole,
    val pool: CombinedBudgetPool,
    val resources: CombinedResourceQuota,
    val upperCostMicros: Long?,
    val createdAtMillis: Long,
    val status: CombinedReservationStatus = CombinedReservationStatus.RESERVED,
    val usage: CombinedUsage? = null,
    val reason: String? = null,
    val effectFingerprint: String? = null
) {
    val charged: CombinedResourceQuota get() = when (status) {
        CombinedReservationStatus.RELEASED -> CombinedResourceQuota()
        CombinedReservationStatus.SETTLED -> usage?.resources ?: resources
        else -> resources
    }
    val chargedCost: Long? get() = if (status == CombinedReservationStatus.RELEASED) 0 else usage?.costMicros ?: upperCostMicros
}

@Serializable
data class CombinedBudgetAdjustment(val ownerKey: String, val reason: String, val resources: CombinedResourceQuota, val costMicros: Long = 0)

@Serializable
data class CombinedWorkAccounting(
    val terminal: Int,
    val succeeded: Int,
    val failed: Int,
    val partial: Int,
    val skipped: Int,
    val cancelled: Int,
    val interrupted: Int,
    val budgetBlocked: Int,
    val legacyTerminalUnknown: Int,
    val waitingForResource: Int,
    val pending: Int
)

@Serializable
data class CombinedBudgetSnapshot(
    val version: Int = 1,
    val rootBudgetId: String,
    val chatId: Int,
    val requestedPolicyDigest: String,
    val effectivePlanDigest: String,
    val plan: CombinedWorkPlan,
    val reservations: Map<String, CombinedReservation> = emptyMap(),
    val baselineAdjustments: List<CombinedBudgetAdjustment> = emptyList(),
    val pendingWork: Map<String, CombinedWorkItem> = plan.workItems.associateBy { it.id },
    /** Legacy name means terminal work, including failures; use workAccounting for outcomes. */
    val completedWork: Set<String> = emptySet(),
    val workAssignments: Map<String, String> = emptyMap(),
    val discoveredSources: Map<String, String> = emptyMap(),
    val stageOutcomes: Map<String, String> = emptyMap(),
    val terminalOutcome: String? = null,
    val terminalReason: String? = null,
    val progressFingerprints: Set<String> = emptySet(),
    val progressAtRenewal: Int = 0,
    val noProgressBatches: Int = 0,
    val optionalTranches: Long = 1,
    val cancelled: Boolean = false,
    val closed: Boolean = false,
    val revision: Long = 0,
    val deliveryStatus: String = "PENDING",
    val retryEligible: Boolean = false,
    val firstFailure: String? = null,
    val deliverySnapshotId: String? = null,
    val deliveryFailure: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val resourceWaits: Map<String, String> = emptyMap(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val workAccounting: CombinedWorkAccounting? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val researchWorkerOutcomes: Map<String, Map<String, String>> = emptyMap()
) {
    val charged: CombinedResourceQuota get() = reservations.values.fold(CombinedResourceQuota()) { sum, item -> sum + item.charged }
    val unknownAttemptIds: Set<String> get() = reservations.values.filter { it.status in setOf(CombinedReservationStatus.RESERVED, CombinedReservationStatus.UNKNOWN) }.mapTo(linkedSetOf()) { it.attemptId }
    val costMicros: Long? get() = if (reservations.values.any { it.status != CombinedReservationStatus.RELEASED && it.chargedCost == null }) null else reservations.values.sumOf { it.chargedCost ?: 0 }

    fun withWorkAccounting(): CombinedBudgetSnapshot {
        val terminal = completedWork + stageOutcomes.keys
        val statuses = terminal.map { id ->
            val recorded = stageOutcomes[id]
            when {
                recorded?.endsWith(":UNUSED_OPTIONAL_CAPACITY") == true -> "SKIPPED"
                recorded == null -> "LEGACY_TERMINAL_UNKNOWN"
                else -> recorded.substringBefore(':')
            }
        }.groupingBy { it }.eachCount()
        return copy(
            resourceWaits = resourceWaits - terminal,
            workAccounting = CombinedWorkAccounting(
                terminal.size, statuses["COMPLETED"] ?: 0, statuses["FAILED"] ?: 0,
                statuses["PARTIAL"] ?: 0, statuses["SKIPPED"] ?: 0, statuses["CANCELLED"] ?: 0,
                statuses["INTERRUPTED"] ?: 0, statuses["BUDGET_BLOCKED"] ?: 0,
                statuses["LEGACY_TERMINAL_UNKNOWN"] ?: 0, (resourceWaits.keys - terminal).size,
                (pendingWork.keys - terminal).size
            )
        )
    }
}

sealed interface CombinedBudgetAdmission {
    data class Granted(val reservation: CombinedReservation) : CombinedBudgetAdmission
    data class Denied(val reason: String) : CombinedBudgetAdmission
}

class CombinedBudgetExceeded(val reason: String) : IllegalStateException(reason)

/**
 * One serialized admission point for the entire accepted turn. Persist before publishing admission;
 * unknown completion retains its upper bound and retries cannot reset counters or duplicate IDs.
 */
class CombinedTurnBudget(
    initial: CombinedBudgetSnapshot,
    private val persist: suspend (CombinedBudgetSnapshot) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private var state = initial.withWorkAccounting()
    private val childJobs = ConcurrentHashMap<Job, Unit>()

    suspend fun snapshot(): CombinedBudgetSnapshot = mutex.withLock { state }

    /** Queue ownership is not a model attempt or a terminal stage outcome. */
    suspend fun waitingForResource(ownerKey: String, reason: String?) = mutex.withLock {
        if (state.closed || state.cancelled || ownerKey in state.completedWork || ownerKey in state.stageOutcomes) return@withLock
        require(ownerKey in state.plan.baselineByOwner || ownerKey in state.pendingWork)
        val waits = if (reason == null) state.resourceWaits - ownerKey else state.resourceWaits + (ownerKey to reason.take(300))
        if (waits != state.resourceWaits) commit(state.copy(resourceWaits = waits))
    }

    /** Detached jobs retain the root's cancellation lifetime, including cancellation before start. */
    suspend fun registerChild(job: Job) = mutex.withLock {
        if (state.closed || state.cancelled) {
            job.cancel(CancellationException("Combined turn is closed"))
        } else {
            childJobs[job] = Unit
            job.invokeOnCompletion { childJobs.remove(job) }
        }
    }

    suspend fun assignedContext(context: CombinedExecutionContext): CombinedExecutionContext = mutex.withLock {
        if (context.workItemId != null) return@withLock context
        val assigned = state.workAssignments[context.ownerKey]?.takeIf { it in state.pendingWork }
            ?: state.pendingWork.values.firstOrNull { it.kind == CombinedWorkKind.EVIDENCE && it.required && (it.assignedOwnerKey == context.ownerKey || context.requested.acceptedResearch != null && it.reason == "ACCEPTED_RESEARCH_REQUIREMENT") }?.id
        context.copy(workItemId = assigned)
    }

    suspend fun availableToolOperations(context: CombinedExecutionContext): Long = mutex.withLock {
        if (state.closed || state.cancelled || !state.plan.toolsAllowed || context.role.finalization) return@withLock 0L
        val owner = budgetOwner(state, context)
        val baseline = allowance(state, CombinedBudgetPool.BASELINE, owner).minusFloor(charged(state, CombinedBudgetPool.BASELINE, owner)).toolOperations
        val optional = allowance(state, CombinedBudgetPool.OPTIONAL, owner).minusFloor(charged(state, CombinedBudgetPool.OPTIONAL, null)).toolOperations
        minOf(Math.addExact(baseline, optional), ((state.plan.hardLimits.toolOperations ?: Long.MAX_VALUE) - state.charged.toolOperations).coerceAtLeast(0))
    }

    suspend fun rebindConversation(chatId: Int) = mutex.withLock {
        require(chatId >= 0 && (state.chatId == 0 || state.chatId == chatId)) { "A root budget cannot move to another conversation." }
        if (state.chatId != chatId) commit(state.copy(chatId = chatId))
    }

    suspend fun reserve(
        context: CombinedExecutionContext,
        attemptId: String,
        resources: CombinedResourceQuota,
        upperCostMicros: Long? = null,
        effectFingerprint: String? = null
    ): CombinedBudgetAdmission = mutex.withLock {
        require(context.rootBudgetId == state.rootBudgetId && context.requested.requestedPolicyDigest == state.requestedPolicyDigest)
        require(attemptId.isNotBlank() && (upperCostMicros == null || upperCostMicros >= 0))
        if (state.cancelled || state.closed) return@withLock CombinedBudgetAdmission.Denied("Combined turn is cancelled or closed; no further dispatch is allowed.")
        if (state.plan.hardLimits.deadlineEpochMillis?.let { now() >= it } == true) return@withLock CombinedBudgetAdmission.Denied("The accepted turn deadline has been reached.")
        if (context.requested.singlePass && (context.role.finalization || context.role == CombinedRunRole.VERIFIER)) {
            if (context.role != CombinedRunRole.SYNTHESIS) return@withLock CombinedBudgetAdmission.Denied("COMBINED_SINGLE_PASS: Automatic verification, continuation and repair are disabled.")
            if (state.reservations.values.any { it.role.finalization && it.resources.inputTokens > 0 && it.status != CombinedReservationStatus.RELEASED }) {
                return@withLock CombinedBudgetAdmission.Denied("COMBINED_ALREADY_DISPATCHED: This turn's primary request was already admitted. Saved or uncertain output must not be recomputed.")
            }
        }
        if (attemptId in state.reservations) return@withLock CombinedBudgetAdmission.Denied("Physical attempt ID was already admitted; no action may be repeated.")
        if (effectFingerprint != null && state.reservations.values.any { it.effectFingerprint == effectFingerprint && it.status in setOf(CombinedReservationStatus.RESERVED, CombinedReservationStatus.UNKNOWN) }) {
            return@withLock CombinedBudgetAdmission.Denied("An equivalent action is in flight or has an unknown outcome. Reconcile it before dispatching it again.")
        }
        if (context.role == CombinedRunRole.CONTRIBUTOR && context.profileUid in state.plan.blockedParticipantUids) {
            return@withLock CombinedBudgetAdmission.Denied("Participant was budget-blocked before dispatch; it was not considered or called.")
        }
        if (context.role == CombinedRunRole.CONTRIBUTOR && context.ownerKey in state.stageOutcomes) {
            return@withLock CombinedBudgetAdmission.Denied("This contributor stage is terminal. A fresh accepted regeneration is required before calling it again.")
        }
        if (context.role == CombinedRunRole.RESEARCH && context.workItemId?.startsWith("research:") == true) {
            if (CombinedResearchStages.isTaskStage(context.workItemId) && context.profileUid !in state.plan.researchAssignment?.eligibleWorkerUids.orEmpty()) return@withLock CombinedBudgetAdmission.Denied("RESEARCH_WORKER_NOT_ASSIGNED: the profile was not accepted for this task.")
            if (CombinedResearchStages.isTaskStage(context.workItemId) && "contributor:${context.profileUid}" in state.stageOutcomes) return@withLock CombinedBudgetAdmission.Denied("RESEARCH_WORKER_STAGE_TERMINAL: this contributor already finished its accepted work.")
            if (context.workItemId in state.plan.unfundedRequiredWorkIds) return@withLock CombinedBudgetAdmission.Denied("RESEARCH_STAGE_UNFUNDED: accepted hard limits cannot fund this stage.")
            if (context.ownerKey in state.stageOutcomes) return@withLock CombinedBudgetAdmission.Denied("RESEARCH_STAGE_TERMINAL: this stage requires a newly accepted request.")
            if (context.profileUid in state.researchWorkerOutcomes[context.workItemId].orEmpty()) return@withLock CombinedBudgetAdmission.Denied("RESEARCH_WORKER_STAGE_TERMINAL: this profile already finished its accepted stage.")
        }
        if (resources.toolOperations > 0 && (!state.plan.toolsAllowed || context.role.finalization)) return@withLock CombinedBudgetAdmission.Denied("This Combined role is tool-free.")
        if (resources.toolOperations > 0 && context.requested.acceptedResearch != null && context.workItemId == null && context.role != CombinedRunRole.VERIFIER) return@withLock CombinedBudgetAdmission.Denied("REQUIRED_ACQUISITION_UNASSIGNED: Required Research requests must carry their funded work item; dispatched=false")
        hardLimitReason(state, resources, upperCostMicros)?.let { return@withLock CombinedBudgetAdmission.Denied(it) }

        var next = state
        val owner = budgetOwner(state, context)
        val primaryPool = if (context.role.finalization) CombinedBudgetPool.FINALIZATION else CombinedBudgetPool.BASELINE
        // Only the first actual compiled request for a sealed owner may correct its input estimate.
        // This cannot create extra output, tool work, participants, or repeatable 'essential' passes.
        val firstProviderAttempt = resources.inputTokens > 0 && next.reservations.values.none { it.ownerKey == owner && it.resources.inputTokens > 0 }
        val plannedPrimary = allowance(next, primaryPool, owner)
        if (firstProviderAttempt &&
            resources.inputTokens > plannedPrimary.inputTokens &&
            (context.role.finalization || owner in next.plan.baselineByOwner)
        ) {
            val adjustment = CombinedBudgetAdjustment(
                owner,
                "ACTUAL_COMPILED_REQUIRED_CONTEXT",
                CombinedResourceQuota(inputTokens = resources.inputTokens - plannedPrimary.inputTokens),
                ((upperCostMicros ?: 0) - remainingProtectedCost(next, owner)).coerceAtLeast(0)
            )
            next = next.copy(baselineAdjustments = next.baselineAdjustments + adjustment)
        }
        val requiredItem = context.workItemId?.let { next.pendingWork[it] }
        if (resources.toolOperations == 0L && requiredItem?.required == true && requiredItem.role == context.role) {
            val remaining = allowance(next, primaryPool, owner).minusFloor(charged(next, primaryPool, if (primaryPool == CombinedBudgetPool.BASELINE) owner else null))
            val difference = resources.minusFloor(remaining)
            if (difference != CombinedResourceQuota()) {
                hardLimitReason(next, protectedRemaining(next) + difference, upperCostMicros)?.let {
                    return@withLock CombinedBudgetAdmission.Denied("REQUIRED_WORK_UNFUNDED: $it; dispatched=false")
                }
                next = next.copy(baselineAdjustments = next.baselineAdjustments + CombinedBudgetAdjustment(owner, "ACTUAL_COMPILED_REQUIRED_CONTEXT", difference))
            }
        }
        val primaryAvailable = allowance(next, primaryPool, owner).minusFloor(charged(next, primaryPool, if (primaryPool == CombinedBudgetPool.BASELINE) owner else null))
        var pool = if (resources.fits(primaryAvailable)) primaryPool else CombinedBudgetPool.OPTIONAL
        if (resources.toolOperations > 0 && resources.inputTokens == 0L && context.workItemId == null) pool = CombinedBudgetPool.OPTIONAL
        if (pool != CombinedBudgetPool.OPTIONAL) {
            hardLimitReason(next, resources + protectedRemaining(next).minusFloor(resources), upperCostMicros)?.let {
                return@withLock CombinedBudgetAdmission.Denied("Required context increased beyond the funded plan; other contributors and finalization remain reserved. $it")
            }
        }
        if (next.plan.hardLimits.costMicros != null && upperCostMicros != null) {
            val protectedCost = protectedCost(next)
            val deductible = if (pool == CombinedBudgetPool.OPTIONAL) 0 else minOf(upperCostMicros, remainingProtectedCost(next, owner))
            val costWithProtection = Math.addExact(upperCostMicros, (protectedCost - deductible).coerceAtLeast(0))
            hardLimitReason(next, resources, costWithProtection)?.let { return@withLock CombinedBudgetAdmission.Denied("Other contributors and finalization have protected funding. $it") }
        }
        if (pool == CombinedBudgetPool.OPTIONAL) {
            val protected = protectedRemaining(next)
            hardLimitReason(next, resources + protected, upperCostMicros)?.let { return@withLock CombinedBudgetAdmission.Denied("Finalization and unstarted contributors are reserved. $it") }
            var available = allowance(next, pool, owner).minusFloor(charged(next, pool, null))
            if (!resources.fits(available)) {
                val renewed = renewFor(next, context, resources)
                    ?: return@withLock CombinedBudgetAdmission.Denied("Optional Combined work allowance reached; retain completed evidence and finish the accepted task.")
                next = renewed
                available = allowance(next, pool, owner).minusFloor(charged(next, pool, null))
                if (!resources.fits(available)) return@withLock CombinedBudgetAdmission.Denied("The next request must be a bounded batch within the renewed tranche.")
            }
        }
        val reservation = CombinedReservation(attemptId, owner, context.role, pool, resources, upperCostMicros, now(), effectFingerprint = effectFingerprint)
        commit(next.copy(reservations = next.reservations + (attemptId to reservation)))
        CombinedBudgetAdmission.Granted(reservation)
    }

    suspend fun settle(attemptId: String, usage: CombinedUsage, outcomeKnown: Boolean = true) = mutex.withLock {
        val reservation = state.reservations[attemptId] ?: return@withLock
        if (reservation.status == CombinedReservationStatus.RELEASED) return@withLock
        if (reservation.status == CombinedReservationStatus.SETTLED) {
            require(reservation.usage == usage) { "A physical attempt cannot be settled twice with different usage." }
            return@withLock
        }
        val result = reservation.copy(
            status = if (outcomeKnown) CombinedReservationStatus.SETTLED else CombinedReservationStatus.UNKNOWN,
            usage = if (outcomeKnown) usage else null,
            reason = if (outcomeKnown) null else "Completion or billing unknown; upper reservation retained"
        )
        commit(state.copy(reservations = state.reservations + (attemptId to result)))
    }

    suspend fun unknown(attemptId: String, reason: String) = mutex.withLock {
        val item = state.reservations[attemptId] ?: return@withLock
        if (item.status == CombinedReservationStatus.SETTLED || item.status == CombinedReservationStatus.RELEASED) return@withLock
        commit(state.copy(reservations = state.reservations + (attemptId to item.copy(status = CombinedReservationStatus.UNKNOWN, reason = reason.take(500)))))
    }

    /** Only verified pre-dispatch rejection can release an attempt; cancellation after dispatch cannot. */
    suspend fun releaseNotDispatched(attemptId: String) = mutex.withLock {
        val item = state.reservations[attemptId] ?: return@withLock
        if (item.status != CombinedReservationStatus.RESERVED) return@withLock
        commit(state.copy(reservations = state.reservations + (attemptId to item.copy(status = CombinedReservationStatus.RELEASED, reason = "Confirmed not dispatched"))))
    }

    suspend fun completeWork(itemId: String) = mutex.withLock {
        if (itemId !in state.pendingWork) return@withLock
        commit(state.copy(pendingWork = state.pendingWork - itemId, completedWork = state.completedWork + itemId, stageOutcomes = state.stageOutcomes + (itemId to "COMPLETED")))
    }

    /** Explicit user retry only; retained attempts remain charged and the final reservation is protected. */
    suspend fun reopenContributor(profileUid: String): Boolean = mutex.withLock {
        if (state.closed || state.cancelled || profileUid !in state.plan.requested.participantUids || state.reservations.values.any { it.role.finalization && it.status != CombinedReservationStatus.RELEASED }) return@withLock false
        val owner = "contributor:$profileUid"
        if (owner !in state.stageOutcomes) return@withLock true
        val owners = (state.plan.baselineByOwner.keys.filter { it == owner || it.startsWith("research:$profileUid:") } + owner).toSet()
        val sharedStages = state.plan.workItems.filter { CombinedResearchStages.isTaskStage(it.id) && profileUid in state.plan.researchAssignment?.eligibleWorkerUids.orEmpty() }.associateBy { it.id }
        val stages = state.plan.workItems.filter { it.kind == CombinedWorkKind.STAGE && it.assignedOwnerKey in owners }.associateBy { it.id } + sharedStages
        // A user authorizes another bounded attempt, not a refund of the previous request.
        // reserve() still enforces the whole-turn hard limits and other owners' protection.
        val adjustments = owners.mapNotNull { key -> state.plan.baselineByOwner[key]?.let { CombinedBudgetAdjustment(key, "USER_REQUESTED_RETRY", it, state.plan.costReservesByOwner[key] ?: 0) } }
        commit(
            state.copy(
                stageOutcomes = state.stageOutcomes - owners - sharedStages.keys,
                completedWork = state.completedWork - owners - stages.keys,
                pendingWork = state.pendingWork + stages,
                baselineAdjustments = state.baselineAdjustments + adjustments,
                researchWorkerOutcomes = state.researchWorkerOutcomes.mapValues { (stage, workers) -> if (stage in sharedStages) workers - profileUid else workers }
            )
        )
        true
    }

    /** One worker finishing acquisition cannot close a stage shared by the other accepted workers. */
    suspend fun finishResearchStage(stageId: String, profileUid: String, outcome: String, reason: String? = null) {
        if (!CombinedResearchStages.isTaskStage(stageId)) {
            finishStage(stageId, outcome, reason)
            return
        }
        mutex.withLock {
            if (state.closed || state.cancelled || stageId in state.stageOutcomes) return@withLock
            require(profileUid in state.plan.researchAssignment?.eligibleWorkerUids.orEmpty())
            require(state.plan.workItems.any { it.id == stageId })
            require(outcome in TERMINAL_OUTCOMES)
            val workers = state.researchWorkerOutcomes[stageId].orEmpty()
            if (profileUid in workers) return@withLock
            val recorded = if (reason.isNullOrBlank()) outcome else "$outcome:${reason.take(300)}"
            val next = state.copy(researchWorkerOutcomes = state.researchWorkerOutcomes + (stageId to (workers + (profileUid to recorded))))
            commit(closeFinishedResearchStages(next))
        }
    }

    /** Interrupted or failed contributors also settle their share, without blocking active peers. */
    private fun closeFinishedResearchStages(snapshot: CombinedBudgetSnapshot): CombinedBudgetSnapshot {
        val participants = snapshot.plan.researchAssignment?.eligibleWorkerUids.orEmpty()
        if (participants.isEmpty()) return snapshot
        var next = snapshot
        snapshot.plan.workItems.filter { CombinedResearchStages.isTaskStage(it.id) && it.id !in snapshot.stageOutcomes }.forEach { stage ->
            val recorded = next.researchWorkerOutcomes[stage.id].orEmpty().toMutableMap()
            participants.filterNot { it in recorded }.forEach { uid ->
                next.stageOutcomes["contributor:$uid"]?.let { terminal ->
                    recorded[uid] = if (terminal.substringBefore(':') == "COMPLETED") "PARTIAL:CONTRIBUTOR_TERMINAL_BEFORE_STAGE_COMPLETED" else terminal
                }
            }
            next = next.copy(researchWorkerOutcomes = next.researchWorkerOutcomes + (stage.id to recorded))
            if (participants.all { it in recorded }) {
                val outcome = if (participants.all { recorded.getValue(it).substringBefore(':') == "COMPLETED" }) "COMPLETED" else "PARTIAL:RESEARCH_WORKER_INCOMPLETE"
                next = next.copy(
                    pendingWork = next.pendingWork - stage.id,
                    completedWork = next.completedWork + stage.id,
                    stageOutcomes = next.stageOutcomes + (stage.id to outcome),
                    firstFailure = next.firstFailure ?: outcome.takeUnless { it == "COMPLETED" }
                )
            }
        }
        return next
    }

    /** A contributor's response, including failure, releases its unused protected baseline. */
    suspend fun finishStage(ownerKey: String, outcome: String, reason: String? = null) = mutex.withLock {
        // Recovery and late callbacks still retain their raw run outcomes, but a closed root's
        // delivery disposition is final. Replaying them must not undo explicit Use Reply/cancel.
        if (state.closed || state.cancelled) return@withLock
        require(ownerKey == "finalization" || ownerKey in state.plan.baselineByOwner || ownerKey in state.pendingWork || ownerKey in state.plan.requested.participantUids.map { "contributor:$it" })
        require(outcome in TERMINAL_OUTCOMES)
        val recorded = if (reason.isNullOrBlank()) outcome else "$outcome:${reason.take(300)}"
        if (state.stageOutcomes[ownerKey] == recorded) return@withLock
        // A failed/partial merge can be explicitly retried within its accepted budget. Keep its
        // unused final reserve protected until delivery or a closed/cancelled root ends the task.
        val release = ownerKey != "finalization" || outcome in setOf("COMPLETED", "CANCELLED", "BUDGET_BLOCKED")
        val stageItems = state.pendingWork.filterValues { it.kind == CombinedWorkKind.STAGE && it.assignedOwnerKey == ownerKey }.keys + ownerKey
        commit(
            closeFinishedResearchStages(
                state.copy(
                    pendingWork = if (release) state.pendingWork - stageItems else state.pendingWork,
                    completedWork = if (release) state.completedWork + stageItems else state.completedWork,
                    stageOutcomes = state.stageOutcomes + (ownerKey to recorded),
                    deliveryStatus = if (ownerKey == "finalization") {
                        when (outcome) {
                            "COMPLETED" -> "COMPLETED"
                            "CANCELLED", "BUDGET_BLOCKED" -> outcome
                            else -> "RECOVERABLE_FAILURE"
                        }
                    } else {
                        state.deliveryStatus
                    },
                    retryEligible = if (ownerKey == "finalization") outcome !in setOf("COMPLETED", "CANCELLED", "BUDGET_BLOCKED") && !state.closed && !state.cancelled else state.retryEligible,
                    firstFailure = state.firstFailure ?: recorded.takeIf { outcome !in setOf("COMPLETED", "CANCELLED") },
                    deliveryFailure = if (ownerKey == "finalization") recorded.takeIf { outcome !in setOf("COMPLETED", "CANCELLED") } else state.deliveryFailure
                )
            )
        )
    }

    /** Assign only already-sealed questions. A new result URL can never add a task or units. */
    suspend fun assignEvidenceWork(context: CombinedExecutionContext, arguments: String, inspection: Boolean): String? = mutex.withLock {
        val explicit = context.workItemId?.let(state.pendingWork::get)
        if (explicit != null && explicit.kind == CombinedWorkKind.EVIDENCE) return@withLock explicit.id
        val pending = state.pendingWork.values.filter { it.kind == CombinedWorkKind.EVIDENCE && (it.assignedOwnerKey == null || it.assignedOwnerKey == context.ownerKey || context.requested.acceptedResearch != null && it.reason == "ACCEPTED_RESEARCH_REQUIREMENT") }
        if (pending.isEmpty()) return@withLock null
        val urls = CombinedWorkInventory.urls(arguments)
        val exact = pending.firstOrNull { item -> item.sourceUrls.any { it in urls } }
        val discovered = urls.firstNotNullOfOrNull { url -> state.discoveredSources[combinedDigest(url)]?.let(state.pendingWork::get) }
        val queryTerms = CombinedWorkInventory.terms(arguments)
        val question = if (inspection) {
            null
        } else {
            pending.map { it to CombinedWorkInventory.terms(it.description).intersect(queryTerms).size }
                .filter { it.second > 0 }.maxByOrNull { it.second }?.first
        }
        val sharedResearch = pending.firstOrNull { it.required && it.reason == "ACCEPTED_RESEARCH_REQUIREMENT" && context.requested.acceptedResearch != null }
        val assigned = exact ?: discovered ?: question ?: sharedResearch ?: return@withLock null
        if (state.workAssignments[context.ownerKey] != assigned.id) commit(state.copy(workAssignments = state.workAssignments + (context.ownerKey to assigned.id)))
        assigned.id
    }

    /** URLs from an on-topic query may be inspected for that question; discovery itself is not progress. */
    suspend fun recordDiscovery(itemId: String, result: String) = mutex.withLock {
        val item = state.pendingWork[itemId]?.takeIf { it.kind == CombinedWorkKind.EVIDENCE } ?: return@withLock
        val room = (2048 - state.discoveredSources.size).coerceAtLeast(0)
        val candidates = CombinedWorkInventory.urls(result).take(minOf(20L, minOf(10L, item.remainingUnits) * 2, room.toLong()).toInt())
            .associate { combinedDigest(it) to itemId }
        if (candidates.isNotEmpty()) commit(state.copy(discoveredSources = state.discoveredSources + candidates))
    }

    /** Re-reading the same page cannot create progress; only sources assigned to a finite question count. */
    suspend fun recordInspection(itemId: String, arguments: String, explicitlyAssigned: Boolean = false): Boolean = mutex.withLock {
        val item = state.pendingWork[itemId]?.takeIf { it.kind == CombinedWorkKind.EVIDENCE } ?: return@withLock false
        val identities = CombinedWorkInventory.urls(arguments).filter { url -> explicitlyAssigned || url in item.sourceUrls || state.discoveredSources[combinedDigest(url)] == itemId }
            .map { combinedDigest("source:$it") }.filterNot { it in state.progressFingerprints }.toSet()
        if (identities.isEmpty()) return@withLock false
        val remaining = (item.remainingUnits - identities.size).coerceAtLeast(0)
        commit(
            state.copy(
                pendingWork = if (remaining == 0L) state.pendingWork - itemId else state.pendingWork + (itemId to item.copy(remainingUnits = remaining)),
                completedWork = if (remaining == 0L) state.completedWork + itemId else state.completedWork,
                stageOutcomes = if (remaining == 0L) state.stageOutcomes + (itemId to "COMPLETED") else state.stageOutcomes,
                discoveredSources = state.discoveredSources - CombinedWorkInventory.urls(arguments).map(::combinedDigest).toSet(),
                progressFingerprints = state.progressFingerprints + identities,
                noProgressBatches = 0
            )
        )
        true
    }

    /** Called for a completed inspected source/claim/subtopic, never just additional output tokens. */
    suspend fun progress(itemId: String, fingerprint: String, completedUnits: Long = 1) = mutex.withLock {
        val item = state.pendingWork[itemId] ?: return@withLock
        require(completedUnits > 0)
        val identity = combinedDigest("$itemId:$fingerprint")
        if (identity in state.progressFingerprints) return@withLock
        val remaining = (item.remainingUnits - completedUnits).coerceAtLeast(0)
        commit(
            state.copy(
                pendingWork = if (remaining == 0L) state.pendingWork - itemId else state.pendingWork + (itemId to item.copy(remainingUnits = remaining)),
                completedWork = if (remaining == 0L) state.completedWork + itemId else state.completedWork,
                stageOutcomes = if (remaining == 0L) state.stageOutcomes + (itemId to "COMPLETED") else state.stageOutcomes,
                progressFingerprints = state.progressFingerprints + identity,
                noProgressBatches = 0
            )
        )
    }

    suspend fun completedAcquisitionBatch(madeProgress: Boolean) = mutex.withLock {
        commit(state.copy(noProgressBatches = if (madeProgress) 0 else (state.noProgressBatches + 1).coerceAtMost(3)))
    }

    /** Controller-only expansion for concrete failed contract checks or required evidence, not model novelty. */
    suspend fun authorizeRequiredWork(item: CombinedWorkItem, quota: CombinedResourceQuota, reason: String, additional: Boolean = false, ownerKey: String? = null) = mutex.withLock {
        require(item.required && item.remainingUnits > 0 && item.id.isNotBlank())
        require(reason in setOf("EXPLICIT_SOURCE_REQUIREMENT", "MATERIAL_CONTRADICTION", "ANSWER_CONTRACT_FAILURE", "REQUIRED_COVERAGE_GAP"))
        check(!state.cancelled && !state.closed)
        require(item.id !in state.pendingWork && item.id !in state.completedWork) { "Required work must have a new versioned identity." }
        val owner = ownerKey ?: if (item.role.finalization) "finalization" else "verification"
        require(if (item.role.finalization) owner == "finalization" else owner == "verification" || item.role == CombinedRunRole.CONTRIBUTOR && owner in state.plan.requested.participantUids.map { "contributor:$it" })
        val pool = if (item.role.finalization) CombinedBudgetPool.FINALIZATION else CombinedBudgetPool.BASELINE
        val unspent = allowance(state, pool, owner).minusFloor(charged(state, pool, if (pool == CombinedBudgetPool.BASELINE) owner else null))
        val adjustment = if (additional) quota else quota.minusFloor(unspent)
        hardLimitReason(state, protectedRemaining(state) + adjustment, null, checkCost = false)?.let { throw CombinedBudgetExceeded(it) }
        commit(
            state.copy(
                pendingWork = state.pendingWork + (item.id to item.copy(reason = reason, kind = if (item.role.finalization || item.role == CombinedRunRole.CONTRIBUTOR) CombinedWorkKind.STAGE else CombinedWorkKind.EVIDENCE, assignedOwnerKey = owner)),
                baselineAdjustments = if (adjustment == CombinedResourceQuota()) state.baselineAdjustments else state.baselineAdjustments + CombinedBudgetAdjustment(owner, reason, adjustment)
            )
        )
    }

    suspend fun cancel() = mutex.withLock {
        if (!state.cancelled) commit(terminalSnapshot("CANCELLED", "Cancellation is terminal; retry requires a newly accepted regeneration.").copy(cancelled = true))
    }

    suspend fun close(outcome: String = "COMPLETED", reason: String? = null) = mutex.withLock {
        require(outcome in TERMINAL_OUTCOMES)
        if (!state.closed) commit(terminalSnapshot(outcome, reason))
    }

    private fun terminalSnapshot(outcome: String, reason: String?): CombinedBudgetSnapshot {
        val remaining = state.pendingWork.values.filter { it.required }
        val finalOutcome = if (outcome == "COMPLETED" && remaining.isNotEmpty()) "PARTIAL" else outcome
        val unresolved = state.pendingWork.keys.associateWith { id ->
            val status = when {
                finalOutcome == "CANCELLED" -> "CANCELLED"
                id in state.plan.unfundedRequiredWorkIds -> "BUDGET_BLOCKED"
                else -> "PARTIAL"
            }
            "$status:" + if (state.pendingWork[id]?.required == false) "UNUSED_OPTIONAL_CAPACITY" else "ROOT_CLOSED_WITH_UNFINISHED_WORK"
        }
        return state.copy(
            closed = true,
            terminalOutcome = finalOutcome,
            terminalReason = (reason ?: if (remaining.isNotEmpty()) "Required work remained unfinished when the root closed." else null)?.take(500),
            pendingWork = emptyMap(),
            completedWork = state.completedWork + state.pendingWork.keys,
            stageOutcomes = unresolved + state.stageOutcomes,
            deliveryStatus = finalOutcome,
            retryEligible = false,
            firstFailure = state.firstFailure ?: reason.takeIf { finalOutcome != "COMPLETED" },
            deliveryFailure = reason.takeIf { finalOutcome != "COMPLETED" }
        )
    }

    suspend fun recordDeliverySnapshot(snapshotId: String) = mutex.withLock {
        if (state.deliverySnapshotId != snapshotId) commit(state.copy(deliverySnapshotId = snapshotId))
    }

    private suspend fun commit(next: CombinedBudgetSnapshot) {
        val revised = next.copy(revision = state.revision + 1).withWorkAccounting()
        withContext(NonCancellable) {
            // Stopping physical work must survive a disk-full or journal failure. Admission still
            // persists before publication, while closure fails closed locally and reports the error.
            if (revised.closed || revised.cancelled) {
                state = revised
                childJobs.keys.forEach { it.cancel(CancellationException("Combined turn is closed")) }
                childJobs.clear()
                persist(revised)
            } else {
                persist(revised)
                state = revised
            }
        }
    }

    /** A child working on a sealed assignment uses its funding without creating another allowance. */
    private fun budgetOwner(snapshot: CombinedBudgetSnapshot, context: CombinedExecutionContext): String {
        if (context.role.finalization) return "finalization"
        val item = context.workItemId?.let { snapshot.pendingWork[it] ?: snapshot.plan.workItems.firstOrNull { item -> item.id == it } }
        return item?.assignedOwnerKey?.takeIf { it in snapshot.plan.baselineByOwner } ?: context.ownerKey
    }

    private fun allowance(snapshot: CombinedBudgetSnapshot, pool: CombinedBudgetPool, owner: String): CombinedResourceQuota = when (pool) {
        CombinedBudgetPool.OPTIONAL -> snapshot.plan.optionalAllowance.scale(snapshot.optionalTranches)
        CombinedBudgetPool.FINALIZATION -> snapshot.plan.finalizationReserve + snapshot.baselineAdjustments.filter { it.ownerKey == "finalization" }.fold(CombinedResourceQuota()) { sum, adjustment -> sum + adjustment.resources }
        CombinedBudgetPool.BASELINE -> (snapshot.plan.baselineByOwner[owner] ?: CombinedResourceQuota()) + snapshot.baselineAdjustments.filter { it.ownerKey == owner }.fold(CombinedResourceQuota()) { sum, adjustment -> sum + adjustment.resources }
    }

    private fun charged(snapshot: CombinedBudgetSnapshot, pool: CombinedBudgetPool, owner: String?): CombinedResourceQuota = snapshot.reservations.values
        .filter { it.pool == pool && (owner == null || it.ownerKey == owner) }
        .fold(CombinedResourceQuota()) { sum, reservation -> sum + reservation.charged }

    private fun protectedRemaining(snapshot: CombinedBudgetSnapshot): CombinedResourceQuota {
        val initial = snapshot.plan.baselineByOwner.keys.filter { it !in snapshot.completedWork }.fold(CombinedResourceQuota()) { sum, owner ->
            sum + allowance(snapshot, CombinedBudgetPool.BASELINE, owner).minusFloor(charged(snapshot, CombinedBudgetPool.BASELINE, owner))
        }
        return initial + if ("finalization" in snapshot.completedWork) CombinedResourceQuota() else allowance(snapshot, CombinedBudgetPool.FINALIZATION, "finalization").minusFloor(charged(snapshot, CombinedBudgetPool.FINALIZATION, null))
    }

    private fun remainingProtectedCost(snapshot: CombinedBudgetSnapshot, owner: String): Long {
        val planned = (snapshot.plan.costReservesByOwner[owner] ?: 0) + snapshot.baselineAdjustments.filter { it.ownerKey == owner }.sumOf { it.costMicros }
        val charged = snapshot.reservations.values.filter { it.ownerKey == owner && it.pool != CombinedBudgetPool.OPTIONAL }.sumOf { it.chargedCost ?: 0 }
        return (planned - charged).coerceAtLeast(0)
    }

    private fun protectedCost(snapshot: CombinedBudgetSnapshot): Long = snapshot.plan.costReservesByOwner.keys
        .filter { it !in snapshot.completedWork }.sumOf { remainingProtectedCost(snapshot, it) }

    private fun hardLimitReason(snapshot: CombinedBudgetSnapshot, request: CombinedResourceQuota, cost: Long?, checkCost: Boolean = true): String? {
        val used = snapshot.charged
        val hard = snapshot.plan.hardLimits
        if (hard.inputTokens?.let { request.inputTokens > it - used.inputTokens } == true) return "Whole-turn input token ceiling reached."
        if (hard.generatedTokens?.let { request.generatedTokens > it - used.generatedTokens } == true) return "Whole-turn generated token ceiling reached."
        if (hard.totalTokens?.let { request.inputTokens + request.generatedTokens > it - used.inputTokens - used.generatedTokens } == true) return "Whole-turn total token ceiling reached."
        if (hard.toolOperations?.let { request.toolOperations > it - used.toolOperations } == true) return "Whole-turn tool-operation ceiling reached."
        if (checkCost && hard.costMicros != null) {
            if (cost == null || snapshot.costMicros == null) return "A monetary ceiling requires a reliable upper cost bound; this attempt has unknown cost."
            if (cost > hard.costMicros - requireNotNull(snapshot.costMicros)) return "Whole-turn monetary ceiling reached."
        }
        return null
    }

    private fun renewFor(snapshot: CombinedBudgetSnapshot, context: CombinedExecutionContext, request: CombinedResourceQuota): CombinedBudgetSnapshot? {
        if (!snapshot.plan.renewable || snapshot.cancelled || snapshot.closed) return null
        val explicit = context.workItemId?.let(snapshot.pendingWork::get)
        val item = explicit?.takeIf { it.kind == CombinedWorkKind.EVIDENCE || context.role.finalization }
            ?: snapshot.workAssignments[context.ownerKey]?.let(snapshot.pendingWork::get)
            ?: (explicit ?: snapshot.pendingWork[context.ownerKey])?.takeIf { context.role.finalization || snapshot.reservations.values.none { reservation -> reservation.ownerKey == context.ownerKey } }
            ?: return null
        val meaningfulProgress = snapshot.progressFingerprints.size > snapshot.progressAtRenewal
        val necessaryStageUnstarted = item.required && snapshot.reservations.values.none { it.ownerKey == context.ownerKey }
        if (!meaningfulProgress && !necessaryStageUnstarted) return null
        if (snapshot.noProgressBatches >= 3 && !item.required) return null
        // One concrete request must fit one tranche. Renewal is not an unbounded allocation loop.
        if (!request.fits(snapshot.plan.optionalAllowance)) return null
        return snapshot.copy(optionalTranches = Math.addExact(snapshot.optionalTranches, 1), progressAtRenewal = snapshot.progressFingerprints.size)
    }

    companion object {
        private val TERMINAL_OUTCOMES = setOf("COMPLETED", "FAILED", "CANCELLED", "BUDGET_BLOCKED", "INTERRUPTED", "PARTIAL")
    }
}
