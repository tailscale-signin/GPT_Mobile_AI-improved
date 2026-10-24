package dev.chungjungsoo.gptmobile.data.benchmark

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BenchmarkStoreTest {
    private fun rejectSnapshotWrites(context: Context) {
        android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("benchmark_v2.db").path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("CREATE TRIGGER reject_score_snapshot BEFORE INSERT ON benchmark_snapshots BEGIN SELECT RAISE(FAIL, 'Injected derived snapshot failure'); END")
        }
    }

    @Before
    fun resetDatabase() {
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase("benchmark_v2.db")
    }

    @Test fun corruptOptionalMirrorAndFailedRecoveryCommitCannotBlockCanonicalHistory() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val prefs = application.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val first = BenchmarkRun("canonical", "profile", "Name", "OPENAI", "model", "key", false, BenchmarkMode.QUICK, 1)
        val initial = BenchmarkStore(application)
        initial.save(first)
        initial.close()
        val corrupt = "[{broken optional mirror"
        prefs.edit().putString("history", corrupt).commit()
        val failing = object : android.content.SharedPreferences by prefs {
            override fun edit(): android.content.SharedPreferences.Editor {
                val editor = prefs.edit()
                return object : android.content.SharedPreferences.Editor by editor {
                    override fun putString(key: String, value: String?): android.content.SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun commit() = false
                }
            }
        }
        val context = object : android.content.ContextWrapper(application) {
            override fun getSharedPreferences(name: String, mode: Int) = if (name == "profile_benchmarks_v1") failing else super.getSharedPreferences(name, mode)
        }
        val store = BenchmarkStore(context)
        try {
            store.load()
            assertEquals(listOf(first), store.history.value)
            assertTrue(store.warning.value.orEmpty().contains("recovery copy could not be saved"))
            store.save(first.copy(id = "next", startedAt = 2))
            assertEquals(setOf("canonical", "next"), store.history.value.map { it.id }.toSet())
            assertEquals(corrupt, prefs.getString("history", null))
        } finally {
            store.close()
        }
        val reopened = BenchmarkStore(application)
        try {
            reopened.load()
            assertEquals(setOf("canonical", "next"), reopened.history.value.map { it.id }.toSet())
        } finally {
            reopened.close()
        }
    }

    @Test fun derivedSnapshotFailureIsObservableAndCannotLoseCommittedQueueOrLaterRuns() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val store = BenchmarkStore(context)
        try {
            store.load()
            rejectSnapshotWrites(context)
            val profile = dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2(uid = "profile", name = "Name", model = "model")
            val batch = BenchmarkBatch("batch", BenchmarkMode.QUICK, 1, listOf(BenchmarkQueueItem(0, profile, "run", "key", BenchmarkQueueState.COMPLETED)), state = BenchmarkBatchState.COMPLETED)
            val sample = BenchmarkSample("instruction", "Instruction", "task", BenchmarkOutcome.PASSED, preview = "READY", canonicalAnswer = "READY", outputCharacters = 5, requestDispatched = true)
            val run = BenchmarkRun("run", profile.uid, profile.name, "OPENAI", "model", "key", false, BenchmarkMode.QUICK, System.currentTimeMillis(), listOf(sample), suiteVersion = 8, measurementVersion = 3, batchId = batch.id)
            store.checkpoint(batch, run)
            kotlinx.coroutines.withTimeout(5000) { store.warning.first { it?.contains("Derived scores could not refresh") == true } }
            assertEquals(BenchmarkBatchState.COMPLETED, store.batch(batch.id)?.state)
            assertEquals(listOf("run"), store.history.value.map { it.id })
            store.save(run.copy(id = "next", startedAt = run.startedAt + 1))
            assertEquals(setOf("run", "next"), store.history.value.map { it.id }.toSet())
            assertTrue(store.snapshot.value?.rows.orEmpty().isEmpty())
        } finally {
            store.close()
        }
        val reopened = BenchmarkStore(context)
        try {
            reopened.load()
            assertEquals(setOf("run", "next"), reopened.history.value.map { it.id }.toSet())
            assertTrue(reopened.warning.value.orEmpty().contains("Derived scores could not refresh"))
        } finally {
            reopened.close()
        }
    }

    @Test fun failedFirstSnapshotCannotResurrectDeletedHistoryFromAnOldMirror() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val initial = androidx.room.Room.databaseBuilder(context, BenchmarkDatabase::class.java, "benchmark_v2.db").build()
        initial.openHelper.writableDatabase
        initial.close()
        rejectSnapshotWrites(context)
        val run = BenchmarkRun("legacy", "profile", "Name", "OPENAI", "model", "key", false, BenchmarkMode.QUICK, 1)
        val prefs = context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("history", kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(BenchmarkRun.serializer()), listOf(run))).commit()
        val store = BenchmarkStore(context)
        try {
            store.load()
            assertEquals(listOf(run), store.history.value)
            store.delete(run.id)
            assertTrue(store.history.value.isEmpty())
        } finally {
            store.close()
        }
        val reopened = BenchmarkStore(context)
        try {
            reopened.load()
            assertTrue(reopened.history.value.isEmpty())
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `bad records do not disable benchmarks or discard recoverable history`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE)
        val valid = BenchmarkRun("valid", "profile", "Name", "OPENAI", "model", "key", false, BenchmarkMode.QUICK, 100)
        val original = "[" + kotlinx.serialization.json.Json.encodeToString(BenchmarkRun.serializer(), valid) + ", {\"mode\":\"UNKNOWN_MODE\"}]"
        prefs.edit().clear().putString("history", original).commit()
        val store = BenchmarkStore(context)
        store.load()
        assertEquals(listOf(valid), store.history.value)
        assertTrue(store.loadWarning != null)
        assertEquals(original, prefs.getString("recovery_history", null))
        store.save(valid.copy(id = "new"))
        assertEquals(2, store.history.value.size)
        assertEquals(original, prefs.getString("recovery_history", null))
        store.close()
    }

    @Test
    fun `checkpoints survive recreation replace same run and delete permanently`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val store = BenchmarkStore(context)
        val run = BenchmarkRun("id", "profile", "Name", "OPENAI", "model", "key", false, BenchmarkMode.QUICK, 100, finished = false)
        store.save(run)
        val restored = BenchmarkStore(context)
        restored.load()
        assertEquals(listOf(run), restored.history.value)
        restored.save(run.copy(finished = true))
        assertEquals(1, restored.history.value.size)
        assertTrue(restored.history.value.single().finished)
        restored.delete(run.id)
        val deleted = BenchmarkStore(context)
        deleted.load()
        assertTrue(deleted.history.value.isEmpty())
        store.close()
        restored.close()
        deleted.close()
    }

    @Test fun canonicalQueueAndAttemptSurviveAStalePreferenceMirrorAndProcessInterruption() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val profile = dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2(uid = "A", name = "A", model = "model")
        val batch = BenchmarkBatch(
            "batch",
            BenchmarkMode.QUICK,
            1,
            listOf(
                BenchmarkQueueItem(0, profile, "A-run", "key", state = BenchmarkQueueState.RUNNING),
                BenchmarkQueueItem(1, profile.copy(uid = "B"), "B-run", "key")
            ),
            state = BenchmarkBatchState.RUNNING,
            activeOrdinal = 0
        )
        val run = BenchmarkRun("A-run", "A", "A", "OPENAI", "model", "key", false, BenchmarkMode.QUICK, 1, finished = false, batchId = batch.id, queueOrdinal = 0)
        val attempt = BenchmarkAttempt("attempt", batch.id, 0, run.id, "instruction", 0, 1, 90_001)
        val store = BenchmarkStore(context)
        store.checkpoint(batch, run, listOf(attempt))
        store.close()
        prefs.edit().putString("history", "[]").putLong("snapshot_generation", Long.MAX_VALUE).commit()
        val restored = BenchmarkStore(context)
        try {
            restored.load()
            assertEquals(run.id, restored.history.value.single().id)
            restored.markInterruptedRuns()
            restored.recoverBatches()
            val interrupted = checkNotNull(restored.batch(batch.id))
            assertEquals(BenchmarkBatchState.INTERRUPTED, interrupted.state)
            assertEquals(BenchmarkQueueState.INTERRUPTED, interrupted.items.first().state)
            assertEquals(listOf("B-run"), interrupted.pending.map { it.runId })
            assertTrue(interrupted.canResume)
            assertTrue(restored.history.value.single().finished)
            assertTrue(!restored.history.value.single().canceled)
            val exported = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<BenchmarkBackupPayload>(restored.exportJson())
            assertEquals("INTERRUPTED", exported.attempts.single().state)
            assertTrue(exported.attempts.single().dispatchUncertain)
        } finally {
            restored.close()
        }
    }

    @Test fun completeBackupRestoresQueueOrderAndDoesNotResumeDispatchedAttempts() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val profile = dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2(uid = "profile", name = "Name", model = "model")
        val batch = BenchmarkBatch("batch", BenchmarkMode.QUICK, 1, listOf(BenchmarkQueueItem(0, profile, "run", "key", state = BenchmarkQueueState.RUNNING), BenchmarkQueueItem(1, profile.copy(uid = "next"), "next-run", "key")), state = BenchmarkBatchState.RUNNING, activeOrdinal = 0)
        val store = BenchmarkStore(context)
        try {
            store.checkpoint(batch, attempts = listOf(BenchmarkAttempt("attempt", batch.id, 0, "run", "case", 0, 1, 90_001)))
            val exported = benchmarkExport(store.exportJson(), false)
            store.importBackupJson(exported)
            val restored = checkNotNull(store.batch(batch.id))
            assertEquals(BenchmarkBatchState.INTERRUPTED, restored.state)
            assertEquals(listOf("profile", "next"), restored.items.map { it.profile.uid })
            assertEquals(listOf("next"), restored.pending.map { it.profile.uid })
            assertTrue(restored.canResume)
        } finally {
            store.close()
        }
    }

    @Test fun mirrorCommitFailureCannotRollBackCanonicalResultsOrPoisonTheNextSave() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val original = application.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE)
        original.edit().clear().commit()
        val attempted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val failing = object : android.content.SharedPreferences by original {
            override fun edit(): android.content.SharedPreferences.Editor {
                val editor = original.edit()
                return object : android.content.SharedPreferences.Editor by editor {
                    override fun putString(key: String, value: String?): android.content.SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun putLong(key: String, value: Long): android.content.SharedPreferences.Editor {
                        editor.putLong(key, value)
                        return this
                    }
                    override fun commit(): Boolean {
                        attempted.complete(Unit)
                        return false
                    }
                }
            }
        }
        val context = object : android.content.ContextWrapper(application) {
            override fun getSharedPreferences(name: String, mode: Int) = if (name == "profile_benchmarks_v1") failing else super.getSharedPreferences(name, mode)
        }
        val store = BenchmarkStore(context)
        try {
            val first = BenchmarkRun("first", "profile", "Name", "OPENAI", "model", "key", false, BenchmarkMode.QUICK, 1)
            store.save(first)
            kotlinx.coroutines.withTimeout(5000) { attempted.await() }
            assertEquals(listOf("first"), store.history.value.map { it.id })
            store.save(first.copy(id = "second", startedAt = 2))
            assertEquals(setOf("first", "second"), store.history.value.map { it.id }.toSet())
            val reopened = BenchmarkStore(application)
            try {
                reopened.load()
                assertEquals(setOf("first", "second"), reopened.history.value.map { it.id }.toSet())
            } finally {
                reopened.close()
            }
        } finally {
            store.close()
        }
    }

    @Test fun finalCaseCommitIsRecoveredAsCompletedWithoutReplayingTheProfile() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val profile = dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2(uid = "A", name = "A", model = "model")
        val suite = benchmarkSuite(BenchmarkMode.QUICK)
        val batch = BenchmarkBatch("batch", BenchmarkMode.QUICK, 1, listOf(BenchmarkQueueItem(0, profile, "A-run", "key", state = BenchmarkQueueState.RUNNING), BenchmarkQueueItem(1, profile.copy(uid = "B"), "B-run", "key")), state = BenchmarkBatchState.RUNNING, activeOrdinal = 0)
        val run = BenchmarkRun("A-run", "A", "A", "OPENAI", "model", "key", false, BenchmarkMode.QUICK, 1, samples = suite.map { BenchmarkSample(it.id, it.label, it.category, BenchmarkOutcome.PASSED, workload = it.workload, requestDispatched = true, outputCharacters = 20, cleanupReceipt = "not_required") }, suiteVersion = 8, plannedTrials = suite.size, finished = false, batchId = batch.id, queueOrdinal = 0)
        val store = BenchmarkStore(context)
        try {
            store.checkpoint(batch, run, event = "CASE_FINISHED")
            store.markInterruptedRuns()
            store.recoverBatches()
            val recovered = checkNotNull(store.batch(batch.id))
            assertEquals(BenchmarkQueueState.COMPLETED, recovered.items[0].state)
            assertEquals(listOf("B"), recovered.pending.map { it.profile.uid })
            assertEquals("COMPLETED", store.history.value.single().state)
            assertTrue(store.history.value.single().finished)
        } finally {
            store.close()
        }
    }
}
