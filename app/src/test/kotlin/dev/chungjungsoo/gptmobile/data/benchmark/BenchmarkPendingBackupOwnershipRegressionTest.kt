package dev.chungjungsoo.gptmobile.data.benchmark

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual Store/Room regression; kept separate from the reserved BenchmarkStoreTest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BenchmarkPendingBackupOwnershipRegressionTest {
    @Before
    fun resetDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("benchmark_v2.db")
        context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun backupReplacementCannotEraseAnUnresolvedLocalRequestOwner() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val (run, batch) = pendingFixture()
        val sample = run.samples.single()
        val store = BenchmarkStore(context)
        try {
            store.checkpoint(batch, run)
            assertTrue(store.history.value.single().samples.single().engineeringCleanupPending())
            // Import succeeds without relying on a late refusal after wider backup writes.
            store.importBackupJson("{\"schemaVersion\":3,\"runs\":[]}")
            assertEquals(run, store.history.value.single())
        } finally {
            store.close()
        }

        val reopened = BenchmarkStore(context)
        val canonical = Room.databaseBuilder(context, BenchmarkDatabase::class.java, "benchmark_v2.db").build()
        try {
            reopened.load()
            val retained = checkNotNull(reopened.history.value.singleOrNull { it.id == run.id })
            assertEquals(run.engineeringPlan, retained.engineeringPlan)
            assertEquals(sample, retained.samples.single())
            assertEquals(batch, reopened.batch(batch.id))
            val attempts = canonical.dao().attempts(batch.id).map { Json.decodeFromString<BenchmarkAttempt>(it.payload) }
            assertTrue(attempts.any { it.runId == run.id && it.logicalRequestId == sample.logicalRequestId && it.sample?.engineeringCleanupPending() == true })
        } finally {
            reopened.close()
            canonical.close()
        }
    }

    @Test fun stalePublishedHistoryAndConflictingBackupCannotReplaceCanonicalReceiptBytes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val (run, batch) = pendingFixture()
        val sample = run.samples.single()
        val attempt = BenchmarkAttempt(checkNotNull(sample.attemptId), batch.id, 0, run.id, sample.testId, 0, 1, 100, sample = sample, logicalRequestId = sample.logicalRequestId)
        val event = BenchmarkBatchEvent(batch.id, 1, "GRADE_PENDING", 2, run.id, attempt.id)
        val store = BenchmarkStore(context)
        val writer = Room.databaseBuilder(context, BenchmarkDatabase::class.java, "benchmark_v2.db").build()
        try {
            store.save(run.copy(samples = emptyList()))
            writer.dao().checkpoint(
                BenchmarkBatchRecord(batch.id, batch.createdAt, batch.state.name, futureReceipt(Json.encodeToString(BenchmarkBatch.serializer(), batch))),
                batch.items.map { BenchmarkQueueRecord(batch.id, it.ordinal, it.runId, it.state.name, futureReceipt(Json.encodeToString(BenchmarkQueueItem.serializer(), it))) },
                BenchmarkRunRecord(run.id, run.startedAt, futureReceipt(Json.encodeToString(BenchmarkRun.serializer(), run))),
                listOf(BenchmarkAttemptRecord(attempt.id, batch.id, 0, sample.testId, futureReceipt(Json.encodeToString(BenchmarkAttempt.serializer(), attempt)))),
                BenchmarkEventRecord(batch.id, event.sequence, futureReceipt(Json.encodeToString(BenchmarkBatchEvent.serializer(), event)))
            )
            val before = ownerPayloads(writer, run.id, batch.id)
            assertTrue(store.history.value.single().samples.isEmpty())
            val ordinary = BenchmarkRun("restored-ordinary", "profile", "Name", "OPENAI", "model", "key", false, BenchmarkMode.QUICK, 1)
            val incoming = BenchmarkBackupPayload(
                runs = listOf(run.copy(samples = emptyList(), finished = true, model = "replacement-model"), ordinary),
                batches = listOf(batch.copy(engineeringPlan = null, items = emptyList())),
                attempts = listOf(attempt.copy(logicalRequestId = "replacement-request", sample = null)),
                batchEvents = listOf(event.copy(type = "IMPORT"))
            )
            store.importBackupJson(Json.encodeToString(BenchmarkBackupPayload.serializer(), incoming))
            assertEquals(before, ownerPayloads(writer, run.id, batch.id))
            assertEquals(run, store.history.value.single { it.id == run.id })
            assertTrue(store.history.value.any { it.id == ordinary.id })
            assertEquals(batch, store.batch(batch.id))
        } finally {
            store.close()
            writer.close()
        }
    }

    @Test fun initialImportDoesNotReserializeCanonicalPayloadsDuringScoreRefresh() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val (run, batch) = pendingFixture()
        val writer = Room.databaseBuilder(context, BenchmarkDatabase::class.java, "benchmark_v2.db").build()
        val store = BenchmarkStore(context)
        try {
            val payload = futureReceipt(Json.encodeToString(BenchmarkRun.serializer(), run))
            writer.dao().insertRuns(listOf(BenchmarkRunRecord(run.id, run.startedAt, payload)))
            writer.dao().upsertBatch(BenchmarkBatchRecord(batch.id, batch.createdAt, batch.state.name, futureReceipt(Json.encodeToString(BenchmarkBatch.serializer(), batch))))
            store.importBackupJson("{\"schemaVersion\":3,\"runs\":[]}")
            assertEquals(payload, writer.dao().runs().single().payload)
            assertEquals(run, store.history.value.single())
        } finally {
            store.close()
            writer.close()
        }
    }

    @Test fun confirmedRemoteHistoryRetainsOrdinaryBackupReplacementBehavior() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val (run, batch) = pendingFixture()
        val settled = run.copy(samples = run.samples.map { it.copy(cleanupReceipt = "gateway:terminal_readback_confirmed") })
        val store = BenchmarkStore(context)
        try {
            store.checkpoint(batch, settled)
            store.importBackupJson("{\"schemaVersion\":3,\"runs\":[]}")
            assertTrue(store.history.value.isEmpty())
            assertTrue(store.batches.value.isEmpty())
        } finally {
            store.close()
        }
        val reopened = BenchmarkStore(context)
        try {
            reopened.load()
            assertTrue(reopened.history.value.isEmpty())
            assertTrue(reopened.batches.value.isEmpty())
        } finally {
            reopened.close()
        }
    }

    private fun futureReceipt(payload: String): String = payload.dropLast(1) + ", \"futureReceipt\" : {\"opaque\":\"  unchanged  \"} }"

    private fun ownerPayloads(database: BenchmarkDatabase, runId: String, batchId: String): Map<String, List<String>> {
        fun read(sql: String, id: String): List<String> = database.openHelper.readableDatabase.query(sql, arrayOf<Any>(id)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        return mapOf(
            "runs" to read("SELECT payload FROM benchmark_runs WHERE id = ?", runId),
            "batches" to read("SELECT payload FROM benchmark_batches WHERE id = ?", batchId),
            "queue" to read("SELECT payload FROM benchmark_queue WHERE batchId = ? ORDER BY ordinal", batchId),
            "attempts" to read("SELECT payload FROM benchmark_attempts WHERE batchId = ? ORDER BY id", batchId),
            "events" to read("SELECT payload FROM benchmark_batch_events WHERE batchId = ? ORDER BY sequence", batchId)
        )
    }

    private fun pendingFixture(): Pair<BenchmarkRun, BenchmarkBatch> {
        val fixture = BenchmarkEngineeringTestFixtures(lane = BenchmarkEngineeringLane.LIVE_GITHUB, families = 1, repeats = 1)
        val test = fixture.cases.first()
        val runId = "owned-remote-run"
        val requestId = "original-unresolved-request"
        val sample = fixture.sample(test, false).copy(
            outcome = BenchmarkOutcome.PARTIAL,
            attemptId = "$runId:${test.id}:0",
            logicalRequestId = requestId,
            physicalRequestIds = listOf("original-generation"),
            engineeringVerification = null,
            cleanupReceipt = "gateway:submission_outcome_unknown",
            engineeringAttempts = listOf(BenchmarkEngineeringAttemptReceipt(0, requestId, benchmarkHash("candidate"), null, "VERIFICATION_PENDING", false, 17, 23, 100))
        )
        val run = fixture.runs().first().copy(
            id = runId,
            samples = listOf(sample),
            finished = false,
            endedAt = null,
            state = "AWAITING_REMOTE_RECONCILIATION",
            queueOrdinal = 0
        )
        val batch = BenchmarkBatch(
            fixture.plan.id,
            BenchmarkMode.GITHUB_INTEGRATION,
            run.startedAt,
            BenchmarkEngineeringRegistry.queue(fixture.profiles, fixture.plan).mapIndexed { index, item ->
                if (index == 0) item.copy(runId = runId) else item
            },
            state = BenchmarkBatchState.INTERRUPTED,
            caseManifest = fixture.cases,
            executionPolicy = fixture.plan.executionPolicy,
            engineeringPlan = fixture.plan
        )
        return run to batch
    }
}
