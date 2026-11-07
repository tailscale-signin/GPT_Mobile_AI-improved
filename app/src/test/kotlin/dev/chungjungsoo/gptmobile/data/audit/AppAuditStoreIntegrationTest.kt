package dev.chungjungsoo.gptmobile.data.audit

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkBackupPayload
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkBatch
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkBatchRunner
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkBatchState
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkExecutionCoordinator
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStopOrigin
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStorageFailure
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStore
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppAuditStoreIntegrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun reset() {
        context.deleteDatabase("benchmark_v2.db")
        context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("benchmark_execution_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private class Source : AppAuditProbeSource {
        override val sourceId = "integration-fixture"
        var executions = 0
        override suspend fun plan(options: AppAuditOptions) = listOf(
            AppAuditPlanEntry("local-fixture", sourceId, "local-fixture", "benchmark", "Local integration fixture", sourceAreaIds = listOf("benchmark"), evidenceKind = AppAuditEvidenceKind.LOCAL_CONTRACT)
        )
        override suspend fun execute(entry: AppAuditPlanEntry, context: AppAuditProbeContext): AppAuditObservation {
            executions++
            return AppAuditObservation(AppAuditOutcome.PASS, "FIXTURE_PASSED", evidenceKind = AppAuditEvidenceKind.LOCAL_CONTRACT, observationSource = AppAuditObservationSource.FIXTURE)
        }
    }

    @Test fun existingForegroundOwnerRunsWithoutAnyProfileAndRetainsAuditAcrossReopen() = runBlocking {
        val source = Source()
        val catalog = AppAuditCatalog(setOf(source))
        var store = BenchmarkStore(context)
        try {
            val auditRunner = AppAuditRunner(store, catalog)
            // These strict mocks reject any attempt to enter the model/profile execution path.
            val batchRunner = BenchmarkBatchRunner(store, mockk(), mockk(), mockk(), context, auditRunner)
            val coordinator = BenchmarkExecutionCoordinator(context, store, batchRunner, catalog)
            assertTrue(coordinator.startAudit())
            val id = checkNotNull(coordinator.sessionId.value)
            assertFalse(coordinator.startAudit())
            assertTrue(coordinator.serviceReady(id))
            withTimeout(10_000) { coordinator.sessionId.first { it == null } }
            assertEquals(1, source.executions)
            assertTrue(store.history.value.isEmpty())
            val saved = checkNotNull(store.batch(id))
            assertEquals(BenchmarkMode.APP_AUDIT, saved.mode)
            assertTrue(saved.items.isEmpty())
            assertEquals(BenchmarkBatchState.COMPLETED, saved.state)
            assertEquals(1, appAuditScore(checkNotNull(saved.audit)).measuredWeight)
            store.close()
            store = BenchmarkStore(context)
            store.load()
            assertEquals(saved, store.batch(id))
            val backup = Json { ignoreUnknownKeys = true }.decodeFromString<BenchmarkBackupPayload>(store.exportJson(includeScoreHistory = true))
            assertTrue(backup.runs.isEmpty())
            assertEquals(saved.audit, backup.batches.single().audit)
            assertTrue(backup.batchEvents.any { it.type == "AUDIT_PROBE_FINISHED" })
        } finally {
            store.close()
        }
    }

    @Test fun processRecoveryRetainsAnUnknownProbeWithoutPuttingItBackInTheQueue() = runBlocking {
        val source = Source()
        val catalog = AppAuditCatalog(setOf(source))
        var store = BenchmarkStore(context)
        try {
            val audit = catalog.create(id = "interrupted-audit")
            val active = audit.copy(activeProbeId = "local-fixture", activeStartedAtEpochMs = audit.startedAtEpochMs, activeDispatchEvidence = AppAuditDispatchEvidence.ATTEMPTED, executedProbes = 1, networkProbesDispatched = 1)
            store.checkpoint(BenchmarkBatch(audit.id, BenchmarkMode.APP_AUDIT, audit.startedAtEpochMs, emptyList(), state = BenchmarkBatchState.RUNNING, audit = active), event = "MANIFEST_SAVED")
            store.close()
            store = BenchmarkStore(context)
            store.recoverBatches()
            val recovered = checkNotNull(store.batch(audit.id))
            assertEquals(BenchmarkBatchState.INTERRUPTED, recovered.state)
            assertTrue(recovered.canResume)
            val result = checkNotNull(recovered.audit).results.single()
            assertEquals(AppAuditOutcome.CANCELED, result.outcome)
            assertEquals(AppAuditDispatchEvidence.UNKNOWN, result.dispatchEvidence)
            store.checkpoint(recovered.copy(state = BenchmarkBatchState.QUEUED, stopOrigin = null), event = "RESUMED")
            AppAuditRunner(store, catalog).run(audit.id) {}
            assertEquals(0, source.executions)
            val completed = checkNotNull(store.batch(audit.id))
            assertEquals(result, checkNotNull(completed.audit).results.first())
            assertEquals(1, completed.audit?.executedProbes)
            assertFalse(completed.canResume)
        } finally {
            store.close()
        }
    }

    @Test fun aConcurrentStopAndAcceptedManifestCannotBeOverwrittenByAProbeCheckpoint() = runBlocking {
        val store = BenchmarkStore(context)
        try {
            val audit = AppAuditCatalog(setOf(Source())).create(id = "frozen-audit")
            val batch = BenchmarkBatch(audit.id, BenchmarkMode.APP_AUDIT, audit.startedAtEpochMs, emptyList(), state = BenchmarkBatchState.RUNNING, audit = audit)
            store.checkpoint(batch, event = "MANIFEST_SAVED")
            assertTrue(store.recordStop(batch.id, BenchmarkStopOrigin.USER_STOP, null))
            store.checkpoint(batch.copy(audit = audit.copy(updatedAtEpochMs = audit.updatedAtEpochMs + 1)), event = "AUDIT_PROBE_STARTED")
            assertEquals(BenchmarkStopOrigin.USER_STOP, store.batch(batch.id)?.stopOrigin)
            val changes = listOf(
                audit.copy(limits = audit.limits.copy(maxExecutedProbes = 1)),
                audit.copy(buildVersion = "different-build"),
                audit.copy(buildVariant = "different-variant"),
                audit.copy(startedAtEpochMs = audit.startedAtEpochMs + 1)
            )
            for (changed in changes) {
                try {
                    store.checkpoint(batch.copy(audit = changed), event = "AUDIT_PROBE_FINISHED")
                    error("Accepted manifest mutation should be rejected")
                } catch (_: BenchmarkStorageFailure) {
                    assertEquals(audit.limits, store.batch(batch.id)?.audit?.limits)
                    assertEquals(audit.buildVersion, store.batch(batch.id)?.audit?.buildVersion)
                    assertEquals(audit.buildVariant, store.batch(batch.id)?.audit?.buildVariant)
                    assertEquals(audit.startedAtEpochMs, store.batch(batch.id)?.audit?.startedAtEpochMs)
                }
            }
        } finally {
            store.close()
        }
    }

    private class PromotionSource : AppAuditProbeSource {
        override val sourceId = "promotion-fixture"
        val executions = mutableListOf<String>()
        override suspend fun plan(options: AppAuditOptions) = listOf("passed", "unknown", "pending").map { id ->
            AppAuditPlanEntry(
                id,
                sourceId,
                id,
                "benchmark",
                "Promotion fixture",
                sourceAreaIds = AppAuditSourceInventory.areas.map { it.id },
                evidenceKind = if (id == "unknown") AppAuditEvidenceKind.LIVE_METADATA else AppAuditEvidenceKind.LOCAL_CONTRACT,
                safetyClass = if (id == "unknown") AppAuditSafetyClass.NETWORK_READ else AppAuditSafetyClass.LOCAL_READ
            )
        }

        override suspend fun execute(entry: AppAuditPlanEntry, context: AppAuditProbeContext): AppAuditObservation {
            check(entry.id == "pending") { "Settled or unknown work must not execute again." }
            executions += entry.id
            return AppAuditObservation(AppAuditOutcome.PASS, "FIXTURE_PASSED", evidenceKind = AppAuditEvidenceKind.LOCAL_CONTRACT, observationSource = AppAuditObservationSource.FIXTURE)
        }
    }

    private suspend fun retainedPromotionAudit(catalog: AppAuditCatalog, id: String): AppAuditRun {
        val audit = catalog.create(AppAuditOptions(allowNetworkReads = true), id = id)
        return audit.copy(
            results = listOf(
                AppAuditResult("passed", AppAuditObservation(AppAuditOutcome.PASS, "FIXTURE_PASSED", evidenceKind = AppAuditEvidenceKind.LOCAL_CONTRACT, observationSource = AppAuditObservationSource.FIXTURE), audit.startedAtEpochMs, audit.startedAtEpochMs, 0, attempted = true),
                AppAuditResult("unknown", AppAuditObservation(AppAuditOutcome.CANCELED, "PROCESS_INTERRUPTION", stage = "interruption", evidenceKind = AppAuditEvidenceKind.LIVE_METADATA, observationSource = AppAuditObservationSource.LIVE, dispatchEvidence = AppAuditDispatchEvidence.UNKNOWN), audit.startedAtEpochMs, audit.startedAtEpochMs, null, attempted = true)
            ),
            executedProbes = 2,
            networkProbesDispatched = 1
        )
    }

    @Test fun foregroundPromotionTimeoutRetainsReceiptsAndResumesOnlyUntouchedChecks() = runBlocking {
        val source = PromotionSource()
        val catalog = AppAuditCatalog(setOf(source))
        var store = BenchmarkStore(context)
        try {
            val audit = retainedPromotionAudit(catalog, "promotion-timeout")
            val batch = BenchmarkBatch(audit.id, BenchmarkMode.APP_AUDIT, audit.startedAtEpochMs, emptyList(), audit = audit)
            val batchRunner = BenchmarkBatchRunner(store, mockk(), mockk(), mockk(), context, AppAuditRunner(store, catalog))
            val coordinator = BenchmarkExecutionCoordinator(context, store, batchRunner, catalog)
            val first = checkNotNull(coordinator.startBatch(batch))
            // Leave the real ten-second foreground readiness bound unchanged.
            withTimeout(20_000) {
                first.join()
                coordinator.sessionId.first { it == null }
            }
            val interrupted = checkNotNull(store.batch(audit.id))
            assertEquals(BenchmarkBatchState.INTERRUPTED, interrupted.state)
            assertEquals(BenchmarkStopOrigin.SERVICE_INTERRUPTION, interrupted.stopOrigin)
            assertEquals("SERVICE_INTERRUPTION", interrupted.audit?.stopReasonCode)
            assertEquals(audit.plan, interrupted.audit?.plan)
            assertEquals(audit.results, interrupted.audit?.results)
            assertEquals(2, interrupted.audit?.executedProbes)
            assertEquals(1, interrupted.audit?.networkProbesDispatched)
            assertTrue(interrupted.canResume)
            assertTrue(source.executions.isEmpty())
            assertTrue(store.history.value.isEmpty())
            assertFalse(coordinator.isRunning)
            assertFalse(coordinator.serviceReady(audit.id))
            assertEquals("INTERRUPTED", context.getSharedPreferences("benchmark_execution_v1", Context.MODE_PRIVATE).getString("status", null))
            assertTrue(coordinator.errorMutable.value.orEmpty().contains("did not acknowledge"))
            val backup = Json { ignoreUnknownKeys = true }.decodeFromString<BenchmarkBackupPayload>(store.exportJson(includeScoreHistory = false))
            assertEquals(1, backup.batchEvents.count { it.type == "FOREGROUND_READINESS_TIMEOUT" })

            val unacknowledgedResume = checkNotNull(coordinator.resumeBatch(audit.id))
            withTimeout(20_000) {
                unacknowledgedResume.join()
                coordinator.sessionId.first { it == null }
            }
            assertEquals(interrupted, store.batch(audit.id))
            assertTrue(source.executions.isEmpty())
            assertTrue(coordinator.errorMutable.value.orEmpty().contains("did not acknowledge"))

            val resumed = checkNotNull(coordinator.resumeBatch(audit.id))
            assertTrue(coordinator.serviceReady(audit.id))
            withTimeout(10_000) {
                resumed.join()
                coordinator.sessionId.first { it == null }
            }
            val completed = checkNotNull(store.batch(audit.id))
            assertEquals(listOf("pending"), source.executions)
            assertEquals(audit.results, completed.audit?.results?.take(2))
            assertEquals(BenchmarkBatchState.COMPLETED_WITH_FAILURES, completed.state)
            assertFalse(completed.canResume)
            store.close()
            store = BenchmarkStore(context)
            store.load()
            assertEquals(completed, store.batch(audit.id))
        } finally {
            store.close()
        }
    }

    @Test fun foregroundTimeoutCheckpointPreservesLatestStopAndOtherDurableDispositions() = runBlocking {
        val store = BenchmarkStore(context)
        try {
            val catalog = AppAuditCatalog(setOf(PromotionSource()))
            val audit = retainedPromotionAudit(catalog, "promotion-user-stop")
            val batch = BenchmarkBatch(audit.id, BenchmarkMode.APP_AUDIT, audit.startedAtEpochMs, emptyList(), audit = audit)
            store.checkpoint(batch, event = "MANIFEST_SAVED")
            assertTrue(store.recordStop(batch.id, BenchmarkStopOrigin.USER_STOP, null))
            val stopped = checkNotNull(store.interruptBeforeExecution(batch.id, "FOREGROUND_READINESS_TIMEOUT"))
            assertEquals(BenchmarkBatchState.STOPPED, stopped.state)
            assertEquals(BenchmarkStopOrigin.USER_STOP, stopped.stopOrigin)
            assertEquals("USER_STOP", stopped.audit?.stopReasonCode)
            assertEquals(audit.results, stopped.audit?.results)
            for (state in BenchmarkBatchState.entries.filter { it != BenchmarkBatchState.QUEUED }) {
                val id = "preserve-${state.name}"
                val retained = batch.copy(id = id, state = state, audit = audit.copy(id = id))
                store.checkpoint(retained, event = "MANIFEST_SAVED")
                assertNull(store.interruptBeforeExecution(id, "FOREGROUND_READINESS_TIMEOUT"))
                assertEquals(retained, store.batch(id))
            }
            val active = batch.copy(id = "preserve-active", audit = audit.copy(id = "preserve-active", activeProbeId = "pending", activeStartedAtEpochMs = audit.startedAtEpochMs, activeDispatchEvidence = AppAuditDispatchEvidence.ATTEMPTED))
            store.checkpoint(active, event = "MANIFEST_SAVED")
            assertNull(store.interruptBeforeExecution(active.id, "FOREGROUND_READINESS_TIMEOUT"))
            assertEquals(active, store.batch(active.id))
        } finally {
            store.close()
        }
    }

    @Test fun preparationTimeoutAfterManifestCommitRetainsItsDistinctCauseAndNoDispatch() = runBlocking {
        val store = BenchmarkStore(context)
        try {
            val audit = retainedPromotionAudit(AppAuditCatalog(setOf(PromotionSource())), "preparation-timeout")
            val batch = BenchmarkBatch(audit.id, BenchmarkMode.APP_AUDIT, audit.startedAtEpochMs, emptyList(), audit = audit)
            val coordinator = BenchmarkExecutionCoordinator(context, store)
            var dispatches = 0
            val job = checkNotNull(
                coordinator.launch(
                    batch.id,
                    prepare = {
                        store.checkpoint(batch, event = "MANIFEST_SAVED")
                        awaitCancellation()
                    }
                ) { dispatches++ }
            )
            // Foreground promotion succeeds; only the unchanged preparation bound expires.
            assertTrue(coordinator.serviceReady(batch.id))
            withTimeout(20_000) {
                job.join()
                coordinator.sessionId.first { it == null }
            }
            val interrupted = checkNotNull(store.batch(batch.id))
            assertEquals(BenchmarkBatchState.INTERRUPTED, interrupted.state)
            assertEquals(BenchmarkStopOrigin.SERVICE_INTERRUPTION, interrupted.stopOrigin)
            assertEquals(audit.results, interrupted.audit?.results)
            assertTrue(interrupted.canResume)
            assertEquals(0, dispatches)
            assertTrue(coordinator.errorMutable.value.orEmpty().contains("preparation exceeded"))
            assertFalse(coordinator.errorMutable.value.orEmpty().contains("did not acknowledge"))
            val backup = Json { ignoreUnknownKeys = true }.decodeFromString<BenchmarkBackupPayload>(store.exportJson(includeScoreHistory = false))
            assertEquals(1, backup.batchEvents.count { it.type == "PREPARATION_TIMEOUT" })
            assertEquals(0, backup.batchEvents.count { it.type == "FOREGROUND_READINESS_TIMEOUT" })
        } finally {
            store.close()
        }
    }

    @Test fun preparationTimeoutWithoutAManifestDoesNotClaimSavedResults() = runBlocking {
        val store = BenchmarkStore(context)
        try {
            val coordinator = BenchmarkExecutionCoordinator(context, store)
            var dispatches = 0
            val job = checkNotNull(coordinator.launch("unsaved-preparation", prepare = { awaitCancellation() }) { dispatches++ })
            assertTrue(coordinator.serviceReady("unsaved-preparation"))
            withTimeout(20_000) {
                job.join()
                coordinator.sessionId.first { it == null }
            }
            assertEquals(0, dispatches)
            assertTrue(store.batches.value.isEmpty())
            assertTrue(store.history.value.isEmpty())
            assertEquals("Benchmark preparation exceeded its time limit.", coordinator.errorMutable.value)
        } finally {
            store.close()
        }
    }

    @Test fun legacyModelBatchStillDecodesWithoutAnAuditPayload() {
        val old = "{\"id\":\"legacy\",\"mode\":\"QUICK\",\"createdAt\":1,\"items\":[]}"
        val decoded = Json.decodeFromString<BenchmarkBatch>(old)
        assertEquals(BenchmarkMode.QUICK, decoded.mode)
        assertEquals(null, decoded.audit)
    }
}
