package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Queue waiting has its own budget; generation deadlines begin only after admission. */
internal const val BENCHMARK_ADMISSION_QUEUE_TIMEOUT_MS = 30_000L

internal class BenchmarkDeadlineExceededException(val stage: String) : IllegalStateException("The benchmark $stage deadline expired.")

private data class BenchmarkDeadlineValue<T>(val value: T)

/** Only this timeout's expiry is translated; a nested child timeout keeps its own identity. */
internal suspend fun <T> benchmarkWithinDeadline(stage: String, timeoutMs: Long, block: suspend () -> T): T {
    val completed = withTimeoutOrNull(timeoutMs) { BenchmarkDeadlineValue(block()) } ?: throw BenchmarkDeadlineExceededException(stage)
    return completed.value
}

internal fun benchmarkPreparationStatus(failure: BenchmarkFailure): String = when (failure.outcome()) {
    BenchmarkOutcome.TIMED_OUT -> "TIMED_OUT"
    BenchmarkOutcome.CANCELED -> if (failure.owner == "user") "CANCELED" else "INTERRUPTED"
    BenchmarkOutcome.SKIPPED -> "SKIPPED"
    else -> "FAILED"
}

internal fun benchmarkAdmissionBlockerLabels(ownerIds: List<String>, benchmarkBlocker: Boolean): List<String> =
    ownerIds.distinct().take(8).map { "${if (benchmarkBlocker) "Measurement" else "Inference"} ${benchmarkHash(it).take(12)}" }
        .ifEmpty { listOf(if (benchmarkBlocker) "Earlier queued measurement" else "Earlier queued inference") }

/** Recovery observes interruption but cannot invent when the lost process stopped measuring. */
internal fun BenchmarkRun.withInterruptedPreparation(origin: BenchmarkStopOrigin): BenchmarkRun {
    val receipt = preparation?.takeIf { it.status in setOf("WAITING", "RUNNING") } ?: return this
    val failure = benchmarkTerminationFailure(CancellationException(), receipt.stage, origin, receipt.eventId)
    return copy(
        preparation = receipt.copy(
            status = benchmarkPreparationStatus(failure),
            endedAt = null,
            elapsedMs = null,
            lastObservedElapsedMs = receipt.elapsedMs ?: receipt.lastObservedElapsedMs,
            failure = failure
        ),
        preflightDurationMs = null,
        stoppedReason = failure.message
    )
}

/** Manifest accounting points to one root incident without inventing a fixture execution. */
internal fun benchmarkUnattemptedDisposition(test: BenchmarkCase, cause: BenchmarkFailure): BenchmarkSample {
    val code = when (cause.code) {
        "USER_STOP" -> "RUN_STOPPED_BY_USER"
        "USER_SKIP" -> "USER_SKIP"
        "SERVICE_INTERRUPTION", "PROCESS_INTERRUPTION" -> "RUN_INTERRUPTED"
        else -> "SKIPPED_DEPENDENCY"
    }
    val detail = when (code) {
        "RUN_STOPPED_BY_USER" -> "Not run because the benchmark was stopped by the user."
        "USER_SKIP" -> "Not run because the profile was skipped by the user."
        "RUN_INTERRUPTED" -> "Not run because benchmark execution was interrupted."
        else -> "Not run because a required earlier operation did not finish."
    }
    val disposition = BenchmarkFailure(
        code,
        "not_started",
        detail,
        owner = if (cause.owner == "user") "user" else "benchmark",
        scope = "dependency",
        failureScope = if (cause.owner == "user") BenchmarkFailureScope.USER else BenchmarkFailureScope.DEPENDENCY,
        recoveryAction = if (cause.owner == "user") BenchmarkRecoveryAction.HONOR_USER_STOP else BenchmarkRecoveryAction.SKIP_DEPENDENTS
    )
    return BenchmarkSample(
        test.id, test.label, test.category, BenchmarkOutcome.SKIPPED,
        error = detail, failureCategory = code, failure = disposition,
        runtimeStage = "not_started", requestDispatched = false, requestAdmitted = false,
        workload = test.workload, warmup = test.warmup, tokenBasis = ReferenceTextTokenizer.VERSION,
        validatorVersion = test.validatorVersion, fixtureContentHash = test.contentHash,
        fixtureHash = benchmarkHash(test.id + "\u0000" + test.prompt + "\u0000" + test.expected),
        repeatIndex = test.repeatIndex, dispositionOnly = true, causedByEventId = cause.eventId,
        evidenceScope = "MANIFEST_DISPOSITION_ONLY"
    )
}
