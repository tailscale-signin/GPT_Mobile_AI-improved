package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.security.DiagnosticRedactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/** Typed pre-dispatch denial; its message must never enter fixture answer grading. */
internal class BenchmarkPolicyBlockedException(message: String) : IllegalStateException(message)

/** An availability failure stops one profile; a completed wrong answer never does. */
internal fun benchmarkFailure(error: Throwable, stage: String = "request"): BenchmarkFailure {
    val message = DiagnosticRedactor.redact(error.message ?: error.javaClass.simpleName).take(500)
    val causes = generateSequence(error) { it.cause }.toList()
    val effectiveStage = if (error is BenchmarkPolicyBlockedException) "admission" else causes.filterIsInstance<dev.chungjungsoo.gptmobile.data.localruntime.QnnRuntimeFailure>().firstOrNull()?.stage?.name?.lowercase() ?: stage
    val text = causes.joinToString(" ") { "${it.javaClass.simpleName} ${it.message.orEmpty()}" }.lowercase()
    val code = when {
        error is BenchmarkPolicyBlockedException -> "POLICY_BLOCKED"
        error is TimeoutCancellationException -> "${effectiveStage.uppercase()}_DEADLINE_EXCEEDED"
        error is CancellationException -> "CHILD_OPERATION_ABORTED"
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
    val next = Regex("next eligible retry: ([^ ]+)", RegexOption.IGNORE_CASE).find(message)?.groupValues?.getOrNull(1)?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
    return BenchmarkFailure(code, effectiveStage, message, retryable = code !in setOf("UNSUPPORTED_CAPABILITY", "FIXTURE_INVALID", "POLICY_BLOCKED"), nextEligibleAt = next)
}

internal fun BenchmarkFailure.outcome(): BenchmarkOutcome = when {
    code == "POLICY_BLOCKED" -> BenchmarkOutcome.POLICY_BLOCKED
    code == "QUOTA_BLOCKED" || code == "COOLDOWN_ACTIVE" -> BenchmarkOutcome.QUOTA_BLOCKED
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
