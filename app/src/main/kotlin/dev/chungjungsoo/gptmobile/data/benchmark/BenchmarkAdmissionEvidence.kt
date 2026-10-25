package dev.chungjungsoo.gptmobile.data.benchmark

/** No provider/native timing is fabricated when a case ends while waiting for admission. */
internal fun benchmarkAdmissionOnlySample(test: BenchmarkCase, failure: BenchmarkFailure, elapsedMs: Long, interrupted: Boolean = false): BenchmarkSample {
    val elapsed = elapsedMs.coerceAtLeast(0)
    return BenchmarkSample(
        test.id, test.label, test.category, if (interrupted) BenchmarkOutcome.CANCELED else failure.outcome(),
        durationMs = elapsed, error = failure.message, failure = failure, failureCategory = failure.code,
        runtimeStage = failure.stage, workload = test.workload, warmup = test.warmup,
        requestDispatched = false, requestAdmitted = false, terminalReceived = false,
        endToEndDurationMs = elapsed, admissionWaitMs = elapsed, generationDurationMs = null,
        phaseIntervals = listOf(BenchmarkPhaseInterval(failure.stage, 0, elapsed)), phaseElapsedMs = mapOf(failure.stage to elapsed),
        timingEligible = false, throughputExclusion = failure.code,
        validatorVersion = test.validatorVersion, fixtureContentHash = test.contentHash, repeatIndex = test.repeatIndex
    )
}
