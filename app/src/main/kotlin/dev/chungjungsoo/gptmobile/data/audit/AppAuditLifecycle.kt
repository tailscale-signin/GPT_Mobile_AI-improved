package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkBatch
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkBatchState
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStopOrigin
import dev.chungjungsoo.gptmobile.data.benchmark.completionState

internal fun recoverAppAuditBatch(batch: BenchmarkBatch, now: Long): BenchmarkBatch {
    val audit = batch.audit
    if (batch.mode != BenchmarkMode.APP_AUDIT || audit == null || audit.id != batch.id || batch.items.isNotEmpty()) {
        return batch.copy(state = BenchmarkBatchState.COMPLETED_WITH_FAILURES, endedAt = now, storageWarning = "App audit manifest is missing or invalid.")
    }
    val origin = batch.stopOrigin ?: BenchmarkStopOrigin.PROCESS_INTERRUPTION
    val retained = audit.interrupted(now, origin.name)
    val recovered = batch.copy(
        audit = retained,
        activeOrdinal = null,
        endedAt = now,
        stopOrigin = origin,
        state = when {
            batch.state == BenchmarkBatchState.STORAGE_PAUSED -> BenchmarkBatchState.STORAGE_PAUSED
            origin == BenchmarkStopOrigin.USER_STOP -> BenchmarkBatchState.STOPPED
            else -> BenchmarkBatchState.INTERRUPTED
        }
    )
    return recovered.copy(state = recovered.completionState())
}

/** A started probe is retained as interrupted and never becomes pending again. */
internal fun AppAuditRun.interrupted(now: Long, reasonCode: String): AppAuditRun {
    val active = plan.firstOrNull { it.id == activeProbeId }
    val retained = if (active == null || results.any { it.entryId == active.id }) {
        results
    } else {
        results + AppAuditResult(
            entryId = active.id,
            observation = AppAuditObservation(
                outcome = AppAuditOutcome.CANCELED,
                reasonCode = reasonCode,
                stage = "interruption",
                evidenceKind = active.evidenceKind,
                configurationRevision = active.configurationRevision,
                dispatchEvidence = when (activeDispatchEvidence) {
                    AppAuditDispatchEvidence.ATTEMPTED, AppAuditDispatchEvidence.CONFIRMED, AppAuditDispatchEvidence.UNKNOWN -> AppAuditDispatchEvidence.UNKNOWN
                    else -> AppAuditDispatchEvidence.NOT_DISPATCHED
                }
            ),
            startedAtEpochMs = activeStartedAtEpochMs ?: now,
            finishedAtEpochMs = now,
            durationMs = null,
            attempted = true,
            networkAdmission = when {
                active.safetyClass != AppAuditSafetyClass.NETWORK_READ -> AppAuditNetworkAdmission.NOT_APPLICABLE
                activeDispatchEvidence in setOf(AppAuditDispatchEvidence.ATTEMPTED, AppAuditDispatchEvidence.CONFIRMED, AppAuditDispatchEvidence.UNKNOWN) -> AppAuditNetworkAdmission.ADMITTED
                else -> AppAuditNetworkAdmission.NOT_ADMITTED
            }
        )
    }
    return copy(
        results = retained,
        activeProbeId = null,
        activeStartedAtEpochMs = null,
        activeDispatchEvidence = AppAuditDispatchEvidence.NOT_ATTEMPTED,
        updatedAtEpochMs = now,
        finishedAtEpochMs = now,
        stopReasonCode = reasonCode
    )
}

internal fun AppAuditRun.accountRemaining(now: Long, outcome: AppAuditOutcome, reasonCode: String): AppAuditRun = copy(
    results = results + pendingEntries.map { entry ->
        AppAuditResult(
            entryId = entry.id,
            observation = AppAuditObservation(outcome, reasonCode, stage = "admission", evidenceKind = entry.evidenceKind, configurationRevision = entry.configurationRevision),
            startedAtEpochMs = now,
            finishedAtEpochMs = now,
            durationMs = 0,
            networkAdmission = if (entry.safetyClass == AppAuditSafetyClass.NETWORK_READ) AppAuditNetworkAdmission.NOT_ADMITTED else AppAuditNetworkAdmission.NOT_APPLICABLE
        )
    },
    updatedAtEpochMs = now,
    finishedAtEpochMs = now,
    stopReasonCode = reasonCode
)
