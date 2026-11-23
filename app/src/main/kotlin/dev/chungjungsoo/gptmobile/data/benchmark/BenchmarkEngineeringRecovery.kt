package dev.chungjungsoo.gptmobile.data.benchmark

internal const val ENGINEERING_RECONCILIATION_REQUIRED = "ENGINEERING_RECONCILIATION_REQUIRED"
internal const val ENGINEERING_RECONCILIATION_MESSAGE = "A previous grading request may still be running. Reconcile its original request before admitting another task."

internal fun BenchmarkSample.engineeringCleanupPending(): Boolean {
    if (evidenceScope !in setOf(BenchmarkEngineeringLane.EXECUTABLE_CODING.evidenceClass, BenchmarkEngineeringLane.LIVE_GITHUB.evidenceClass)) return false
    val confirmed = cleanupReceipt in setOf("gateway:terminal_readback_confirmed", "gateway:no_submission_dispatched")
    return cleanupReceipt in setOf("gateway:shutdown_unconfirmed", "gateway:submission_outcome_unknown") ||
        !confirmed &&
        engineeringAttempts.any { it.outcome == "VERIFICATION_PENDING" }
}

/** One case owns one canonical sample; intermediate receipts must never shadow its final result. */
internal fun BenchmarkRun.withEngineeringSample(sample: BenchmarkSample): BenchmarkRun =
    copy(samples = samples.filterNot { it.testId == sample.testId } + sample)

internal fun benchmarkEngineeringPausedBatch(batch: BenchmarkBatch, run: BenchmarkRun?, reason: String = ENGINEERING_RECONCILIATION_MESSAGE): BenchmarkBatch =
    batch.copy(
        state = BenchmarkBatchState.INTERRUPTED,
        activeOrdinal = null,
        endedAt = null,
        engineeringPauseReason = reason,
        items = batch.items.map { item ->
            if (item.runId != run?.id) {
                item
            } else {
                item.copy(
                    state = BenchmarkQueueState.QUEUED,
                    endedAt = null,
                    completedCases = run.samples.count { it.completed },
                    activeCaseId = run.samples.firstOrNull { it.engineeringCleanupPending() }?.testId,
                    failure = BenchmarkFailure(ENGINEERING_RECONCILIATION_REQUIRED, "reconciliation", reason, owner = "gateway", scope = "batch", failureScope = BenchmarkFailureScope.BATCH, recoveryAction = BenchmarkRecoveryAction.STOP_BATCH)
                )
            }
        }
    )

internal fun BenchmarkRun.awaitingEngineeringReconciliation(reason: String = ENGINEERING_RECONCILIATION_MESSAGE): BenchmarkRun =
    copy(finished = false, endedAt = null, state = "AWAITING_REMOTE_RECONCILIATION", stoppedReason = reason)

/** A read/cancel receipt cannot replace a candidate or erase generation usage. */
internal fun benchmarkEngineeringReconciliationBound(original: BenchmarkSample, updated: BenchmarkSample): Boolean =
    original.testId == updated.testId &&
        original.logicalRequestId == updated.logicalRequestId &&
        original.physicalRequestIds == updated.physicalRequestIds &&
        original.invocationIds == updated.invocationIds &&
        original.inputTokens == updated.inputTokens &&
        original.outputTokens == updated.outputTokens &&
        original.engineeringAttempts.map { Triple(it.index, it.logicalRequestId, it.candidateDigest) } ==
        updated.engineeringAttempts.map { Triple(it.index, it.logicalRequestId, it.candidateDigest) }

/** In-flight model usage is unknown after process death; never silently regenerate that case. */
internal fun benchmarkEngineeringInterruptedRun(run: BenchmarkRun, attempts: List<BenchmarkAttempt>): BenchmarkRun {
    val plan = run.engineeringPlan ?: return run
    val manifest = run.caseManifest.orEmpty().associateBy { it.id }
    var retained = run
    for (attempt in attempts.filter { it.runId == run.id && it.state == "STARTED" }) {
        if (retained.samples.any { it.testId == attempt.caseId }) continue
        val test = manifest[attempt.caseId] ?: continue
        retained = retained.withEngineeringSample(
            BenchmarkSample(
                test.id, test.label, test.category, BenchmarkOutcome.PARTIAL,
                attemptId = attempt.id, logicalRequestId = attempt.logicalRequestId,
                physicalRequestIds = attempt.physicalRequestIds, invocationIds = attempt.invocationIds,
                requestDispatched = attempt.requestDispatched, evidenceScope = plan.lane.evidenceClass,
                failureCategory = "PROCESS_INTERRUPTION", error = "The process stopped during generation; usage and final outcome are unknown. This case is retained without replay.",
                failure = BenchmarkFailure("PROCESS_INTERRUPTION", "generation", "Generation was interrupted before a durable final result.", owner = "harness", scope = "request", failureScope = BenchmarkFailureScope.REQUEST),
                cleanupReceipt = "gateway:no_submission_dispatched"
            )
        )
    }
    return retained.copy(finished = false, endedAt = null, state = "INTERRUPTED", cancellationOrigin = BenchmarkStopOrigin.PROCESS_INTERRUPTION)
}
