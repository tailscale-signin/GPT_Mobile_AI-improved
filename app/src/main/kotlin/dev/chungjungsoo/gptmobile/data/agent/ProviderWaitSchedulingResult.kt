package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Closed scheduling evidence. A persistence exception never grants request replay. */
internal data class ProviderWaitSchedulingResult private constructor(
    val persisted: Boolean?,
    val schedulerState: String,
    val noWaitReason: NoWaitReason? = null,
    val failure: Exception? = null
) {
    enum class NoWaitReason {
        DECISION_UNAVAILABLE,
        REJECTION_NOT_REPLAY_SAFE,
        RETRY_LIMIT_REACHED,
        CONNECTIVITY_RESUME_UNSAFE,
        RESEARCH_CHECKPOINT_INVALID,
        ROUTE_MISMATCH,
        RUN_UNAVAILABLE,
        MESSAGE_UNAVAILABLE,
        OWNER_CHANGED,
        CHECKPOINT_UNAVAILABLE,
        CHECKPOINT_CHANGED,
        REMOTE_JOB_PRESENT,
        INITIAL_OUTPUT_PRESENT,
        CONTINUATION_PREFIX_CHANGED,
        COMBINED_SNAPSHOT_INVALID,
        COMBINED_RESUME_UNSAFE,
        AUTHORITY_UNAVAILABLE,
        AUTHORITY_CHANGED,
        PROFILE_UNAVAILABLE,
        INPUT_UNAVAILABLE,
        TASK_SOURCE_UNAVAILABLE,
        TASK_SOURCE_INVALID,
        DEADLINE_EXPIRED,
        PERSISTENCE_REJECTED,
        PERSISTENCE_FAILED,
        PERSISTENCE_OUTCOME_UNKNOWN,
        WAIT_STORE_UNAVAILABLE,
        MESSAGE_CHECKPOINT_FAILED,
        WAIT_PREPARATION_FAILED
    }

    val waiting: Boolean get() = persisted == true
    val decisionState: String get() = when (persisted) {
        true -> "DURABLE_WAIT"
        false -> "NOT_SCHEDULED"
        null -> "PERSISTENCE_UNCERTAIN"
    }

    fun fields(): JsonObject = buildJsonObject {
        put("provider_wait_decision_state", decisionState)
        put("provider_wait_scheduler_state", schedulerState)
        persisted?.let { put("provider_wait_persisted", it) }
        noWaitReason?.let { put("provider_wait_no_wait_reason", it.name) }
        put(
            "provider_wait_state",
            when (persisted) {
                true -> "WAITING_PROVIDER_LIMIT"
                false -> "NOT_SCHEDULED"
                null -> "PERSIST_FAILED"
            }
        )
    }

    fun retain(failure: ProfileFailureRecord): ProfileFailureRecord =
        failure.copy(
            retryAllowed = failure.retryAllowed && waiting,
            requestReplayAllowed = if (!waiting) false else failure.requestReplayAllowed,
            recoveryDetails = JsonObject(failure.recoveryDetails.orEmpty() + fields())
        )

    /** Preserve the historical Boolean API's exception behavior for existing callers. */
    fun valueOrThrow(): Boolean {
        if (!waiting) failure?.let { throw it }
        return waiting
    }

    companion object {
        val decisionStates = setOf("ELIGIBLE", "INELIGIBLE", "ROUTE_MISMATCH", "DURABLE_WAIT", "NOT_SCHEDULED", "PERSISTENCE_UNCERTAIN")
        val schedulerStates = setOf("ENQUEUED", "PENDING_RECONCILIATION", "NOT_SCHEDULED")
        val noWaitReasons = NoWaitReason.entries.mapTo(linkedSetOf()) { it.name }

        fun refused(reason: NoWaitReason, failure: Exception? = null) = ProviderWaitSchedulingResult(false, "NOT_SCHEDULED", reason, failure)
        fun durable(enqueued: Boolean) = ProviderWaitSchedulingResult(true, if (enqueued) "ENQUEUED" else "PENDING_RECONCILIATION")
        fun uncertain(failure: Exception) = ProviderWaitSchedulingResult(null, "NOT_SCHEDULED", NoWaitReason.PERSISTENCE_OUTCOME_UNKNOWN, failure)
    }
}

/** Typed billing/authentication and unknown remote outcomes cannot become quota retries. */
internal fun providerWaitDecisionRequired(code: String?, failure: ProfileFailureRecord?): Boolean {
    if (failure?.category in setOf("BILLING_UNAVAILABLE", "AUTHENTICATION_FAILED", "ACCESS_FORBIDDEN", "PROVIDER_CONTENT_POLICY", "TOOL_OUTCOME_UNKNOWN", GatewayOutcomeRecovery.ERROR_CODE)) return false
    return failure?.category == "RATE_LIMIT" || code in setOf("429", "RATE_LIMIT", "RATE_LIMIT_EXCEEDED", "PROVIDER_RATE_LIMITED", "LOCAL_COOLDOWN_ACTIVE", "LOCAL_ALLOWANCE_EXHAUSTED")
}

/** A saved quota decision establishes its category without inventing an HTTP response. */
internal fun providerWaitFailureRecord(message: String, decision: dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision?): ProfileFailureRecord {
    val failure = ProfileFailureRecord.fromProvider(message, decision?.category)
    return if (decision == null || providerWaitDecisionRequired(decision.category.uppercase(java.util.Locale.ROOT), null)) {
        failure.copy(category = "RATE_LIMIT", retryAllowed = false)
    } else {
        failure
    }
}

/** Resolve a lost commit response from owned storage, never from the thrown text. */
internal suspend fun persistProviderWaitSafely(
    persist: suspend () -> Boolean,
    observeOwned: suspend () -> Boolean?,
    enqueue: suspend () -> Unit
): ProviderWaitSchedulingResult {
    try {
        if (!persist()) return ProviderWaitSchedulingResult.refused(ProviderWaitSchedulingResult.NoWaitReason.PERSISTENCE_REJECTED)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        val observed = try {
            observeOwned()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (observed == false) return ProviderWaitSchedulingResult.refused(ProviderWaitSchedulingResult.NoWaitReason.PERSISTENCE_FAILED, failure)
        if (observed == null) return ProviderWaitSchedulingResult.uncertain(failure)
    }
    val enqueued = try {
        enqueue()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
    return ProviderWaitSchedulingResult.durable(enqueued)
}
