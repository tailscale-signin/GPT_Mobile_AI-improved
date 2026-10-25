package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.network.FreeAiRateLimitException
import dev.chungjungsoo.gptmobile.data.network.FreeProviderException
import dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision
import dev.chungjungsoo.gptmobile.data.security.DiagnosticRedactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/** Typed pre-dispatch denial; its message must never enter fixture answer grading. */
internal class BenchmarkPolicyBlockedException(message: String) : IllegalStateException(message)

/** Keep adapter outcomes typed through stream collection instead of reparsing UI prose. */
internal class BenchmarkProviderFailureException(
    message: String,
    val errorCode: String? = null,
    val providerLimit: ProviderLimitDecision? = null
) : IllegalStateException(message)

internal class BenchmarkUnsupportedConfigurationException(message: String) : IllegalStateException(message)

/** An availability failure stops one profile; a completed wrong answer never does. */
internal fun benchmarkFailure(error: Throwable, stage: String = "request"): BenchmarkFailure {
    val message = DiagnosticRedactor.redact(error.message ?: error.javaClass.simpleName).take(500)
    val causes = generateSequence(error) { it.cause }.toList()
    val providerFailure = causes.filterIsInstance<BenchmarkProviderFailureException>().firstOrNull()
    val freeFailure = causes.filterIsInstance<FreeProviderException>().firstOrNull()
    val limit = providerFailure?.providerLimit ?: freeFailure?.providerLimit
    val rateLimit = causes.filterIsInstance<FreeAiRateLimitException>().firstOrNull()
    val providerCode = (providerFailure?.errorCode ?: freeFailure?.category?.name ?: rateLimit?.let { dev.chungjungsoo.gptmobile.data.network.freeFailureCategory(it).name })?.uppercase()
    val effectiveStage = if (causes.any { it is BenchmarkPolicyBlockedException || it is BenchmarkUnsupportedConfigurationException }) "admission" else causes.filterIsInstance<dev.chungjungsoo.gptmobile.data.localruntime.QnnRuntimeFailure>().firstOrNull()?.stage?.name?.lowercase() ?: stage
    val text = causes.joinToString(" ") { "${it.javaClass.simpleName} ${it.message.orEmpty()}" }.lowercase()
    val code = when {
        causes.any { it is BenchmarkPolicyBlockedException } || providerCode == "POLICY_BLOCKED" -> "POLICY_BLOCKED"
        causes.any { it is BenchmarkUnsupportedConfigurationException } -> "UNSUPPORTED_OUTPUT_CONTRACT"
        error is TimeoutCancellationException -> "${effectiveStage.uppercase()}_DEADLINE_EXCEEDED"
        error is CancellationException -> "CHILD_OPERATION_ABORTED"
        providerCode == "QUEUE_JOB_PENDING" -> "REMOTE_JOB_PENDING"
        providerCode == "LOCAL_COOLDOWN_ACTIVE" -> "COOLDOWN_ACTIVE"
        providerCode == "LOCAL_ALLOWANCE_EXHAUSTED" -> "LOCAL_ALLOWANCE_BLOCKED"
        providerCode == "PROVIDER_RATE_LIMITED" -> "QUOTA_BLOCKED"
        providerCode in setOf("CACHED_UNUSABLE_RESPONSE", "STREAM_DEGRADED", "HOSTED_GENERATION_FAILED", "PROVIDER_PAYLOAD_ERROR", "REASONING_BUDGET_EXHAUSTED", "NO_VISIBLE_ANSWER") -> requireNotNull(providerCode)
        providerFailure?.errorCode?.matches(Regex("[A-Z][A-Z0-9_]{0,79}")) == true -> requireNotNull(providerFailure.errorCode)
        "memory_admission" in text -> "RESOURCE_ADMISSION"
        "quarantin" in text -> "NATIVE_QUARANTINED"
        "binding" in text && ("expired" in text || "unavailable" in text) -> "SERVICE_BINDING_EXPIRED"
        "profile_unavailable" in text -> "PROFILE_UNAVAILABLE"
        "battery" in text || "thermal" in text -> "ENVIRONMENT_BLOCKED"
        "cooldown" in text || "next eligible" in text -> "COOLDOWN_ACTIVE"
        "429" in text || "quota" in text || "rate limit" in text || "free allowance" in text -> "QUOTA_BLOCKED"
        "unknownhost" in text || "unable to resolve host" in text -> "DNS_FAILURE"
        "401" in text || "403" in text || "invalid api key" in text -> "AUTHENTICATION_FAILED"
        "404" in text -> "ENDPOINT_UNAVAILABLE"
        "output_protocol_invalid" in text -> "OUTPUT_PROTOCOL_INVALID"
        "no_visible_answer" in text || "no answer" in text || "reasoning-only" in text -> "NO_VISIBLE_ANSWER"
        "input" in text && ("too large" in text || "limit" in text || "transport" in text) -> "UNSUPPORTED_INPUT_BAND"
        "context" in text && ("token" in text || "window" in text) -> "UNSUPPORTED_INPUT_BAND"
        "unsupported" in text || "disabled" in text || "chat-only" in text -> "UNSUPPORTED_CAPABILITY"
        "timeout" in text || "deadline" in text -> "${effectiveStage.uppercase()}_DEADLINE_EXCEEDED"
        "fixture_invalid" in text -> "FIXTURE_INVALID"
        "native" in text || "load" in text || "qnn" in text -> "NATIVE_EXECUTION_FAILED"
        else -> "EXECUTION_FAILED"
    }
    val next = limit?.retryAt ?: causes.filterIsInstance<FreeAiRateLimitException>().firstOrNull()?.retryAt ?: retryTimestampFromLegacyMessage(message)
    return BenchmarkFailure(code, effectiveStage, message, retryable = !code.startsWith("UNSUPPORTED_") && code !in setOf("FIXTURE_INVALID", "POLICY_BLOCKED", "CACHED_UNUSABLE_RESPONSE", "HOSTED_GENERATION_FAILED"), nextEligibleAt = next)
}

/** Compatibility for older persisted failures; punctuation is never part of an ISO instant. */
internal fun retryTimestampFromLegacyMessage(message: String): Long? =
    Regex("next eligible retry:\\s*(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:\\d{2}))", RegexOption.IGNORE_CASE)
        .find(message)?.groupValues?.getOrNull(1)?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }

internal fun BenchmarkFailure.outcome(): BenchmarkOutcome = when {
    code == "POLICY_BLOCKED" -> BenchmarkOutcome.POLICY_BLOCKED
    code in setOf("QUOTA_BLOCKED", "COOLDOWN_ACTIVE", "LOCAL_ALLOWANCE_BLOCKED") -> BenchmarkOutcome.QUOTA_BLOCKED
    code in setOf("REMOTE_JOB_PENDING", "STREAM_DEGRADED") -> BenchmarkOutcome.PARTIAL
    code.startsWith("UNSUPPORTED_") -> BenchmarkOutcome.UNSUPPORTED
    code.endsWith("DEADLINE_EXCEEDED") -> BenchmarkOutcome.TIMED_OUT
    else -> BenchmarkOutcome.ERROR
}

internal fun BenchmarkSample.executionFailure(): Boolean = outcome in setOf(BenchmarkOutcome.ERROR, BenchmarkOutcome.TIMED_OUT, BenchmarkOutcome.QUOTA_BLOCKED, BenchmarkOutcome.POLICY_BLOCKED, BenchmarkOutcome.CANCELED, BenchmarkOutcome.PARTIAL)

/** Case scheduling depends on positive cleanup facts, not on a model-quality grade. */
internal fun BenchmarkSample.recoveryAction(allowConfirmedRecovery: Boolean = true): BenchmarkRecoveryAction {
    if (runtimeCleanup?.safeForSuccessor == false || cleanupReceipt == "cleanup_unconfirmed_local_blocked") return BenchmarkRecoveryAction.QUARANTINE_RUNTIME
    if (outcome == BenchmarkOutcome.CANCELED || failure?.owner == "user") return BenchmarkRecoveryAction.HONOR_USER_STOP
    if (!executionFailure() || failureCategory == "FIXTURE_INVALID") return BenchmarkRecoveryAction.CONTINUE_INDEPENDENT_CASES
    if (outcome in setOf(BenchmarkOutcome.QUOTA_BLOCKED, BenchmarkOutcome.POLICY_BLOCKED)) return BenchmarkRecoveryAction.STOP_PROFILE
    if (allowConfirmedRecovery && runtimeCleanup?.safeForSuccessor == true) {
        if (outcome == BenchmarkOutcome.PARTIAL && (terminalReceived == true || runtimeExecutions.lastOrNull()?.hasNativeTerminal == true)) return BenchmarkRecoveryAction.CONTINUE_INDEPENDENT_CASES
        if (outcome in setOf(BenchmarkOutcome.TIMED_OUT, BenchmarkOutcome.ERROR) && runtimeExecutions.isNotEmpty()) return BenchmarkRecoveryAction.CONTINUE_INDEPENDENT_CASES
    }
    return BenchmarkRecoveryAction.STOP_PROFILE
}

internal fun BenchmarkSample.withFailureScope(allowConfirmedRecovery: Boolean): BenchmarkSample {
    val action = recoveryAction(allowConfirmedRecovery)
    val scope = when (action) {
        BenchmarkRecoveryAction.CONTINUE_INDEPENDENT_CASES -> BenchmarkFailureScope.REQUEST
        BenchmarkRecoveryAction.SKIP_DEPENDENTS -> BenchmarkFailureScope.DEPENDENCY
        BenchmarkRecoveryAction.QUARANTINE_RUNTIME -> BenchmarkFailureScope.RUNTIME
        BenchmarkRecoveryAction.STOP_BATCH -> BenchmarkFailureScope.BATCH
        BenchmarkRecoveryAction.HONOR_USER_STOP -> BenchmarkFailureScope.USER
        else -> BenchmarkFailureScope.PROFILE
    }
    val original = failure ?: if (outcome in setOf(BenchmarkOutcome.PASSED, BenchmarkOutcome.UNSUPPORTED, BenchmarkOutcome.SKIPPED) && action == BenchmarkRecoveryAction.CONTINUE_INDEPENDENT_CASES) return this else BenchmarkFailure(failureCategory ?: "EXECUTION_FAILED", runtimeStage ?: "request", error ?: "The case did not complete successfully.")
    return copy(failure = original.copy(failureScope = scope, recoveryAction = action, scope = scope.name.lowercase(), owner = if (scope == BenchmarkFailureScope.RUNTIME) "runtime" else original.owner))
}
