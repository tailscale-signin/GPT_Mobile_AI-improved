package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.serialization.Serializable

/** Logical-case counts. Native generation closure has its own per-generation metric. */
@Serializable
data class BenchmarkCoverage(
    val planned: Int,
    val accounted: Int,
    val admitted: Int,
    val executed: Int,
    val terminalized: Int,
    val passed: Int,
    val failed: Int,
    val partial: Int,
    val blocked: Int,
    val skipped: Int,
    val pending: Int,
    val admissionUnknown: Int = 0,
    val executionUnknown: Int = 0,
    val terminalUnknown: Int = 0,
    val policyVersion: String = "benchmark-coverage-v1",
    val plannedKnown: Boolean = true
)

internal fun BenchmarkSample.knownExecuted(): Boolean = requestDispatched == true ||
    runtimeExecutions.any { it.knownDispatched } ||
    phaseIntervals.any { it.nativeStage == "NATIVE_ENTRY" && (it.nativeOperationId ?: 0) > 0 } ||
    requestDispatched == null &&
    (outputCharacters > 0 || chunks > 0 || rounds.isNotEmpty())

fun benchmarkCoverage(run: BenchmarkRun): BenchmarkCoverage {
    val samples = run.samples.distinctBy { it.testId }
    val planned = maxOf(run.plannedTrials, samples.size)
    return BenchmarkCoverage(
        planned, samples.size,
        samples.count { it.requestAdmitted == true || it.knownExecuted() },
        samples.count { it.knownExecuted() },
        samples.count { it.terminalReceived == true },
        samples.count { it.outcome == BenchmarkOutcome.PASSED },
        samples.count { it.outcome in setOf(BenchmarkOutcome.FAILED, BenchmarkOutcome.ERROR, BenchmarkOutcome.TIMED_OUT) },
        samples.count { it.outcome == BenchmarkOutcome.PARTIAL },
        samples.count { it.outcome in setOf(BenchmarkOutcome.QUOTA_BLOCKED, BenchmarkOutcome.POLICY_BLOCKED) || it.requestDispatched == false && it.outcome == BenchmarkOutcome.ERROR },
        samples.count { it.outcome in setOf(BenchmarkOutcome.SKIPPED, BenchmarkOutcome.UNSUPPORTED) },
        (planned - samples.size).coerceAtLeast(0),
        samples.count { it.requestAdmitted == null && !it.knownExecuted() && it.outcome !in setOf(BenchmarkOutcome.SKIPPED, BenchmarkOutcome.UNSUPPORTED) },
        samples.count { it.requestDispatched == null && !it.knownExecuted() && it.outcome !in setOf(BenchmarkOutcome.SKIPPED, BenchmarkOutcome.UNSUPPORTED) },
        samples.count { it.knownExecuted() && it.terminalReceived == null },
        plannedKnown = run.plannedTrials > 0
    )
}
