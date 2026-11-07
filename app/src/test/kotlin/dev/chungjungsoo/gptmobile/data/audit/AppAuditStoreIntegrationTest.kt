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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun legacyModelBatchStillDecodesWithoutAnAuditPayload() {
        val old = "{\"id\":\"legacy\",\"mode\":\"QUICK\",\"createdAt\":1,\"items\":[]}"
        val decoded = Json.decodeFromString<BenchmarkBatch>(old)
        assertEquals(BenchmarkMode.QUICK, decoded.mode)
        assertEquals(null, decoded.audit)
    }
}
