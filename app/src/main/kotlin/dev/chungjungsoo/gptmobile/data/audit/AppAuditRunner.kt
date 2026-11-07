package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.data.agent.ToolFailureMetadata
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkBatch
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkBatchState
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkProgress
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStopOrigin
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStorageFailure
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStore
import dev.chungjungsoo.gptmobile.data.benchmark.completionState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Runs allowlisted probes inside the existing benchmark owner and canonical checkpoints. */
@Singleton
class AppAuditRunner internal constructor(
    private val catalog: AppAuditCatalog,
    private val read: suspend (String) -> BenchmarkBatch?,
    private val persist: suspend (BenchmarkBatch, String) -> Unit,
    private val epochMs: () -> Long,
    private val monotonicMs: () -> Long
) {
    @Inject constructor(store: BenchmarkStore, catalog: AppAuditCatalog) : this(
        catalog,
        read = { store.batch(it) },
        persist = { batch, event -> store.checkpoint(batch, event = event) },
        epochMs = System::currentTimeMillis,
        monotonicMs = { System.nanoTime() / 1_000_000 }
    )

    internal suspend fun run(id: String, progress: (BenchmarkProgress) -> Unit) {
        var batch = checkNotNull(read(id))
        val initial = batch.audit
        if (batch.mode != BenchmarkMode.APP_AUDIT || initial == null || initial.id != id || batch.items.isNotEmpty()) {
            checkpoint(batch.copy(state = BenchmarkBatchState.COMPLETED_WITH_FAILURES, endedAt = epochMs(), storageWarning = "App audit manifest is missing or invalid."), "AUDIT_INVALID_MANIFEST")
            return
        }
        val compatibility = catalog.incompatibility(initial)
        if (compatibility != null) {
            val blocked = initial.interrupted(epochMs(), compatibility).accountRemaining(epochMs(), AppAuditOutcome.BLOCKED, compatibility)
            checkpoint(batch.copy(audit = blocked, state = BenchmarkBatchState.COMPLETED_WITH_FAILURES, endedAt = epochMs()), "AUDIT_INCOMPATIBLE")
            return
        }
        try {
            // A previously started probe can never be replayed, including after a storage pause.
            val recovered = initial.takeIf { it.activeProbeId != null }?.interrupted(epochMs(), "PROCESS_INTERRUPTION") ?: initial
            batch = batch.copy(state = BenchmarkBatchState.RUNNING, audit = recovered.copy(finishedAtEpochMs = null, stopReasonCode = null), endedAt = null)
            checkpoint(batch, "AUDIT_STARTED")
            while (true) {
                currentCoroutineContext().ensureActive()
                batch = checkNotNull(read(id))
                val run = checkNotNull(batch.audit)
                if (batch.stopOrigin != null) {
                    interrupt(batch, batch.stopOrigin)
                    return
                }
                val entry = run.pendingEntries.firstOrNull() ?: break
                if (run.executedProbes >= run.limits.maxExecutedProbes || epochMs() >= run.deadlineAtEpochMs) {
                    val reason = if (run.executedProbes >= run.limits.maxExecutedProbes) "RUN_BUDGET_EXHAUSTED" else "RUN_DEADLINE_EXCEEDED"
                    checkpoint(batch.copy(audit = run.accountRemaining(epochMs(), AppAuditOutcome.UNTESTED, reason)), "AUDIT_BUDGET_EXHAUSTED")
                    break
                }
                progress(BenchmarkProgress("App audit", entry.label, run.results.size, run.plan.size))
                val notAdmitted = precheck(run, entry)
                if (notAdmitted != null) {
                    retain(batch, entry, notAdmitted, epochMs(), 0, attempted = false)
                    continue
                }
                execute(batch, entry)
            }
            batch = checkNotNull(read(id))
            val now = epochMs()
            checkpoint(batch.copy(state = batch.completionState(), endedAt = now, audit = batch.audit?.copy(updatedAtEpochMs = now, finishedAtEpochMs = now)), "AUDIT_FINISHED")
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                withTimeoutOrNull(5_000) {
                    read(id)?.let { latest -> interrupt(latest, latest.stopOrigin ?: BenchmarkStopOrigin.SERVICE_INTERRUPTION) }
                }
            }
            throw cancel
        }
    }

    private fun precheck(run: AppAuditRun, entry: AppAuditPlanEntry): AppAuditObservation? {
        val disposition = when {
            !entry.selected -> AppAuditOutcome.SKIPPED to "NOT_SELECTED"
            entry.prerequisite == AppAuditPrerequisite.NOT_CONFIGURED -> AppAuditOutcome.UNAVAILABLE to (entry.reasonCode ?: "NOT_CONFIGURED")
            entry.prerequisite == AppAuditPrerequisite.UNSUPPORTED -> AppAuditOutcome.UNAVAILABLE to (entry.reasonCode ?: "UNSUPPORTED")
            entry.prerequisite == AppAuditPrerequisite.PERMISSION_REQUIRED -> AppAuditOutcome.BLOCKED to (entry.reasonCode ?: "PERMISSION_REQUIRED")
            entry.prerequisite == AppAuditPrerequisite.EXTERNAL_HARNESS_REQUIRED || entry.safetyClass == AppAuditSafetyClass.EXTERNAL_ONLY -> AppAuditOutcome.UNTESTED to (entry.reasonCode ?: "EXTERNAL_HARNESS_REQUIRED")
            entry.safetyClass == AppAuditSafetyClass.MODEL_REQUEST -> AppAuditOutcome.UNTESTED to "MODEL_EXECUTION_NOT_SELECTED"
            entry.safetyClass == AppAuditSafetyClass.NETWORK_READ && !run.options.allowNetworkReads -> AppAuditOutcome.SKIPPED to "NETWORK_READS_DISABLED"
            entry.safetyClass == AppAuditSafetyClass.NETWORK_READ && run.networkAttemptBudgetUsed >= run.limits.maxNetworkProbes -> AppAuditOutcome.SKIPPED to "NETWORK_BUDGET_EXHAUSTED"
            catalog.source(entry.sourceId) == null -> AppAuditOutcome.UNAVAILABLE to "PROBE_SOURCE_UNAVAILABLE"
            else -> null
        } ?: return null
        return AppAuditObservation(disposition.first, disposition.second, stage = "admission", evidenceKind = entry.evidenceKind)
    }

    private suspend fun execute(batch: BenchmarkBatch, entry: AppAuditPlanEntry) {
        val run = checkNotNull(batch.audit)
        val started = epochMs()
        val monotonicStart = monotonicMs()
        checkpoint(
            batch.copy(audit = run.copy(activeProbeId = entry.id, activeStartedAtEpochMs = started, activeDispatchEvidence = AppAuditDispatchEvidence.NOT_DISPATCHED, executedProbes = run.executedProbes + 1, updatedAtEpochMs = started)),
            "AUDIT_PROBE_STARTED"
        )
        val context = AppAuditProbeContext(run.limits) {
            val latest = checkNotNull(read(batch.id))
            val current = checkNotNull(latest.audit)
            check(current.activeProbeId == entry.id)
            if (latest.stopOrigin != null) throw CancellationException("App audit stopped before dispatch.")
            if (entry.safetyClass != AppAuditSafetyClass.NETWORK_READ || !current.options.allowNetworkReads) throw AppAuditAdmissionRejected("DISPATCH_NOT_ALLOWED")
            if (epochMs() >= current.deadlineAtEpochMs) throw AppAuditAdmissionRejected("RUN_DEADLINE_EXCEEDED")
            if (current.activeDispatchEvidence != AppAuditDispatchEvidence.ATTEMPTED) {
                if (current.networkAttemptBudgetUsed >= current.limits.maxNetworkProbes) throw AppAuditAdmissionRejected("NETWORK_BUDGET_EXHAUSTED")
                checkpoint(latest.copy(audit = current.copy(activeDispatchEvidence = AppAuditDispatchEvidence.ATTEMPTED, networkProbesDispatched = current.networkAttemptBudgetUsed + 1, networkProbesAttempted = current.networkAttemptBudgetUsed + 1, updatedAtEpochMs = epochMs())), "AUDIT_DISPATCH_STARTED")
            }
        }
        val observation = try {
            val remaining = (run.deadlineAtEpochMs - epochMs()).coerceAtLeast(1)
            withTimeout(minOf(run.limits.perProbeTimeoutMs, remaining)) {
                if (epochMs() >= run.deadlineAtEpochMs) throw AppAuditAdmissionRejected("RUN_DEADLINE_EXCEEDED")
                checkNotNull(catalog.source(entry.sourceId)).execute(entry, context)
            }
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            if (failure is BenchmarkStorageFailure) throw failure
            val current = checkNotNull(read(batch.id)?.audit)
            val dispatched = current.activeDispatchEvidence == AppAuditDispatchEvidence.ATTEMPTED
            val authority = ToolFailureMetadata.authorityCode(failure)
            val outcome = when {
                authority != null || failure is AppAuditAdmissionRejected -> AppAuditOutcome.BLOCKED
                failure is CancellationException && failure !is TimeoutCancellationException -> AppAuditOutcome.CANCELED
                else -> AppAuditOutcome.FAIL
            }
            AppAuditObservation(
                outcome = outcome,
                reasonCode = authority ?: when (failure) {
                    is AppAuditAdmissionRejected -> failure.reasonCode
                    is TimeoutCancellationException -> "PROBE_TIMEOUT"
                    is CancellationException -> "CHILD_OPERATION_ABORTED"
                    else -> "PROBE_FAILED"
                },
                stage = if (outcome == AppAuditOutcome.BLOCKED && !dispatched) "admission" else "execution",
                evidenceKind = entry.evidenceKind,
                observationSource = if (dispatched) AppAuditObservationSource.LIVE else AppAuditObservationSource.NONE,
                dispatchEvidence = if (dispatched) AppAuditDispatchEvidence.UNKNOWN else AppAuditDispatchEvidence.NOT_DISPATCHED,
                configChanged = authority != null
            )
        }
        val latest = checkNotNull(read(batch.id))
        val current = checkNotNull(latest.audit)
        fun contractFailure(code: String) = AppAuditObservation(
            AppAuditOutcome.FAIL,
            code,
            evidenceKind = entry.evidenceKind,
            dispatchEvidence = if (current.activeDispatchEvidence == AppAuditDispatchEvidence.ATTEMPTED || entry.safetyClass == AppAuditSafetyClass.NETWORK_READ) AppAuditDispatchEvidence.UNKNOWN else AppAuditDispatchEvidence.NOT_DISPATCHED,
            configChanged = observation.configChanged
        )
        val normalized = when {
            observation.evidenceKind != entry.evidenceKind -> contractFailure("PROBE_EVIDENCE_MISMATCH")
            entry.safetyClass == AppAuditSafetyClass.NETWORK_READ && observation.observationSource == AppAuditObservationSource.LIVE && current.activeDispatchEvidence != AppAuditDispatchEvidence.ATTEMPTED -> contractFailure("DISPATCH_RECEIPT_MISSING")
            entry.safetyClass == AppAuditSafetyClass.NETWORK_READ && observation.observationSource == AppAuditObservationSource.LIVE && observation.outcome == AppAuditOutcome.PASS && observation.dispatchEvidence != AppAuditDispatchEvidence.CONFIRMED -> contractFailure("DISPATCH_CONFIRMATION_MISSING")
            else -> observation.copy(metrics = observation.metrics.take(run.limits.maxMetricsPerProbe))
        }
        retain(latest, entry, normalized, started, (monotonicMs() - monotonicStart).coerceAtLeast(0), attempted = true)
        if (normalized.configChanged || normalized.reasonCode in setOf("PROBE_EVIDENCE_MISMATCH", "DISPATCH_RECEIPT_MISSING", "DISPATCH_CONFIRMATION_MISSING")) {
            val stopped = checkNotNull(read(batch.id))
            val reason = if (normalized.configChanged) "CONFIGURATION_CHANGED" else "PROBE_CONTRACT_INVALID"
            checkpoint(stopped.copy(audit = checkNotNull(stopped.audit).accountRemaining(epochMs(), AppAuditOutcome.BLOCKED, reason)), "AUDIT_AUTHORITY_CHANGED")
        }
    }

    private suspend fun retain(batch: BenchmarkBatch, entry: AppAuditPlanEntry, observation: AppAuditObservation, started: Long, durationMs: Long, attempted: Boolean) {
        val run = checkNotNull(batch.audit)
        val now = epochMs()
        check(run.results.none { it.entryId == entry.id })
        val result = AppAuditResult(entry.id, observation, started, now, durationMs, attempted)
        checkpoint(batch.copy(audit = run.copy(results = run.results + result, activeProbeId = null, activeStartedAtEpochMs = null, activeDispatchEvidence = AppAuditDispatchEvidence.NOT_ATTEMPTED, updatedAtEpochMs = now)), "AUDIT_PROBE_FINISHED")
    }

    private suspend fun interrupt(batch: BenchmarkBatch, origin: BenchmarkStopOrigin) {
        val now = epochMs()
        checkpoint(batch.copy(state = if (origin == BenchmarkStopOrigin.USER_STOP) BenchmarkBatchState.STOPPED else BenchmarkBatchState.INTERRUPTED, stopOrigin = origin, endedAt = now, audit = batch.audit?.interrupted(now, origin.name)), "AUDIT_INTERRUPTED")
    }

    private suspend fun checkpoint(batch: BenchmarkBatch, event: String) {
        try {
            withTimeout(5_000) { persist(batch, event) }
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            if (failure is BenchmarkStorageFailure) throw failure
            throw BenchmarkStorageFailure(failure)
        }
    }
}

private class AppAuditAdmissionRejected(val reasonCode: String) : IllegalStateException("App audit admission rejected.")
