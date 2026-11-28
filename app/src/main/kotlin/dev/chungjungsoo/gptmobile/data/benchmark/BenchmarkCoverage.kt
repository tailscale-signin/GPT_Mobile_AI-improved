package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.serialization.Serializable

/**
 * Logical-case counts. Passed, failed, partial, blocked, skipped and canceled partition
 * accounted outcomes. Not-started and admission/execution observations are separate axes.
 */
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
    val policyVersion: String = "benchmark-coverage-v2",
    val plannedKnown: Boolean = true,
    val canceled: Int = 0,
    /** Accounted cases with no fixture attempt; pending is separate and case admission rejection is an attempt. */
    val notStarted: Int = 0
)

internal fun BenchmarkSample.knownExecuted(): Boolean = runtimeExecutions.any { it.knownDispatched } ||
    phaseIntervals.any { it.nativeStage == "NATIVE_ENTRY" && (it.nativeOperationId ?: 0) > 0 } ||
    requestAdmitted != false &&
    requestDispatched != false &&
    (outputCharacters > 0 || chunks > 0 || rounds.isNotEmpty() || requestDispatched == true && providerHttpStatus == null)

/** A retained plan fixes the warmup population before any sample is recorded. */
internal fun benchmarkPlannedMeasuredTrials(run: BenchmarkRun): Int? {
    run.caseManifest?.takeIf { it.isNotEmpty() }?.let { manifest ->
        if (manifest.map { it.id }.distinct().size != manifest.size) return null
        return manifest.count { !it.warmup }
    }
    // A partial legacy history cannot reveal the warmups that were never reached.
    val samples = run.samples
    return samples.takeIf {
        run.plannedTrials > 0 && it.size == run.plannedTrials && it.map { sample -> sample.testId }.distinct().size == it.size
    }?.count { !it.warmup }
}

/** Older runners assigned run-level preparation to the first fixture before dispatch. */
internal fun BenchmarkSample.isRunPreparationSample(): Boolean =
    outcome !in setOf(BenchmarkOutcome.SKIPPED, BenchmarkOutcome.UNSUPPORTED) &&
        (runtimeStage in setOf("preflight", "preflight_admission") || failure?.stage in setOf("preflight", "preflight_admission")) &&
        requestAdmitted != true &&
        requestDispatched == false &&
        physicalRequestIds.isEmpty() &&
        providerHttpStatus == null &&
        !knownExecuted() &&
        !(inputTokens > 0 && inputTokenConfidence == "final_reported") &&
        !(outputTokens > 0 && outputTokenConfidence == "final_reported")

fun benchmarkCoverage(run: BenchmarkRun): BenchmarkCoverage {
    val samples = run.samples.distinctBy { it.testId }
    val planned = run.caseManifest?.takeIf { it.isNotEmpty() }?.map { it.id }?.distinct()?.size ?: maxOf(run.plannedTrials, samples.size)
    fun blocked(sample: BenchmarkSample): Boolean = sample.outcome in setOf(BenchmarkOutcome.QUOTA_BLOCKED, BenchmarkOutcome.POLICY_BLOCKED) ||
        sample.requestDispatched == false &&
        !sample.knownExecuted() &&
        sample.outcome == BenchmarkOutcome.ERROR
    return BenchmarkCoverage(
        planned, samples.size,
        samples.count { it.requestAdmitted == true || it.knownExecuted() },
        samples.count { it.knownExecuted() },
        samples.count { it.terminalReceived == true },
        samples.count { it.outcome == BenchmarkOutcome.PASSED },
        samples.count { it.outcome in setOf(BenchmarkOutcome.FAILED, BenchmarkOutcome.ERROR, BenchmarkOutcome.TIMED_OUT) && !blocked(it) },
        samples.count { it.outcome == BenchmarkOutcome.PARTIAL },
        samples.count(::blocked),
        samples.count { it.outcome in setOf(BenchmarkOutcome.SKIPPED, BenchmarkOutcome.UNSUPPORTED) },
        (planned - samples.size).coerceAtLeast(0),
        samples.count { it.requestAdmitted == null && !it.knownExecuted() && it.outcome !in setOf(BenchmarkOutcome.SKIPPED, BenchmarkOutcome.UNSUPPORTED) },
        samples.count { it.requestDispatched != false && it.requestAdmitted != false && !it.knownExecuted() && it.outcome !in setOf(BenchmarkOutcome.SKIPPED, BenchmarkOutcome.UNSUPPORTED) },
        samples.count { it.knownExecuted() && it.terminalReceived == null },
        plannedKnown = !run.caseManifest.isNullOrEmpty() || run.plannedTrials > 0,
        canceled = samples.count { it.outcome == BenchmarkOutcome.CANCELED },
        notStarted = samples.count {
            (it.dispositionOnly || it.isRunPreparationSample() || it.outcome in setOf(BenchmarkOutcome.SKIPPED, BenchmarkOutcome.UNSUPPORTED)) &&
                it.requestAdmitted != true &&
                it.requestDispatched == false &&
                it.physicalRequestIds.isEmpty() &&
                it.providerHttpStatus == null &&
                !it.knownExecuted()
        }
    )
}
