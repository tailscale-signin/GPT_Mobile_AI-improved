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
            val profile = dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2(uid = "profile", name = "Name", model = "model", apiUrl = "https://example.org/private", token = "private-credential")
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

    @Test fun pendingRemoteOwnershipSurvivesRestartAndRejectedHistoryDeletion() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val fixture = BenchmarkEngineeringTestFixtures(lane = BenchmarkEngineeringLane.LIVE_GITHUB, families = 1, repeats = 1)
        val run = pendingRemoteRun(fixture, "remote")
        val batch = BenchmarkBatch(
            fixture.plan.id, BenchmarkMode.GITHUB_INTEGRATION, run.startedAt,
            BenchmarkEngineeringRegistry.queue(fixture.profiles, fixture.plan).mapIndexed { index, item ->
                if (index == 0) item.copy(runId = run.id, state = BenchmarkQueueState.RUNNING) else item
            },
            state = BenchmarkBatchState.RUNNING, activeOrdinal = 0,
            caseManifest = fixture.cases, executionPolicy = fixture.plan.executionPolicy, engineeringPlan = fixture.plan
        )
        val initial = BenchmarkStore(context)
        try {
            initial.checkpoint(batch, run)
        } finally {
            initial.close()
        }
        val restored = BenchmarkStore(context)
        try {
            restored.markInterruptedRuns()
            restored.recoverBatches()
            val retained = restored.history.value.single()
            val retainedBatch = checkNotNull(restored.batch(batch.id))
            assertTrue(retainedBatch.canResume)
            assertEquals("original-remote", retained.samples.single().engineeringAttempts.single().logicalRequestId)
            val failure = runCatching { restored.delete(run.id) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("Remote benchmark work must be reconciled before this saved result can be deleted.", failure?.message)
            assertEquals(retained, restored.history.value.single())
            assertEquals(retainedBatch, restored.batch(batch.id))
        } finally {
            restored.close()
        }
        val reopened = BenchmarkStore(context)
        try {
            reopened.load()
            assertEquals(run.samples, reopened.history.value.single().samples)
            assertTrue(checkNotNull(reopened.batch(batch.id)).canResume)
        } finally {
            reopened.close()
        }
    }

    @Test fun historyDeletionRechecksPersistedOwnershipWhenPublishedHistoryIsStale() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val fixture = BenchmarkEngineeringTestFixtures(lane = BenchmarkEngineeringLane.LIVE_GITHUB, families = 1, repeats = 1)
        val pending = pendingRemoteRun(fixture, "remote")
        val beforeAdmission = pending.copy(samples = emptyList())
        val writer = androidx.room.Room.databaseBuilder(context, BenchmarkDatabase::class.java, "benchmark_v2.db").build()
        val store = BenchmarkStore(context)
        try {
            writer.dao().insertRuns(listOf(BenchmarkRunRecord(beforeAdmission.id, beforeAdmission.startedAt, kotlinx.serialization.json.Json.encodeToString(BenchmarkRun.serializer(), beforeAdmission))))
            store.load()
            writer.dao().insertRuns(listOf(BenchmarkRunRecord(pending.id, pending.startedAt, kotlinx.serialization.json.Json.encodeToString(BenchmarkRun.serializer(), pending))))
            assertEquals(beforeAdmission, store.history.value.single())
            assertTrue(runCatching { store.delete(pending.id) }.exceptionOrNull() is IllegalStateException)
        } finally {
            store.close()
            writer.close()
        }
        val reopened = BenchmarkStore(context)
        try {
            reopened.load()
            assertEquals(pending, reopened.history.value.single())
        } finally {
            reopened.close()
        }
    }

    @Test fun ordinaryAndConfirmedRemoteResultsCanStillBeDeletedPermanently() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val fixture = BenchmarkEngineeringTestFixtures(lane = BenchmarkEngineeringLane.LIVE_GITHUB, families = 1, repeats = 1)
        val pending = pendingRemoteRun(fixture, "remote")
        val ordinary = BenchmarkRun("ordinary", "profile", "Name", "OPENAI", "model", "key", false, BenchmarkMode.QUICK, 1)
        val settled = pending.copy(id = "settled", samples = pending.samples.map { it.copy(cleanupReceipt = "gateway:terminal_readback_confirmed") })
        val neverSubmitted = pending.copy(id = "never-submitted", samples = pending.samples.map { it.copy(cleanupReceipt = "gateway:no_submission_dispatched") })
        val store = BenchmarkStore(context)
        try {
            val runs = listOf(ordinary, settled, neverSubmitted)
            runs.forEach { store.save(it) }
            runs.forEach { store.delete(it.id) }
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

    @Test fun bulkBackupPreflightRefusesWhileDirectImportPreservesPendingJournal() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val fixture = BenchmarkEngineeringTestFixtures(lane = BenchmarkEngineeringLane.LIVE_GITHUB, families = 1, repeats = 1)
        val pending = pendingRemoteRun(fixture, "pending-backup")
        val batch = BenchmarkBatch(
            fixture.plan.id,
            BenchmarkMode.GITHUB_INTEGRATION,
            pending.startedAt,
            listOf(BenchmarkQueueItem(0, fixture.profiles.first(), pending.id, pending.configKey, state = BenchmarkQueueState.RUNNING)),
            state = BenchmarkBatchState.RUNNING,
            activeOrdinal = 0,
            engineeringPlan = fixture.plan,
            caseManifest = fixture.cases
        )
        val attempt = BenchmarkAttempt("owned-attempt", batch.id, 0, pending.id, fixture.cases.first().id, 0, 1, 90_001, requestDispatched = true, logicalRequestId = "original-pending-backup")
        val canonical = androidx.room.Room.databaseBuilder(context, BenchmarkDatabase::class.java, "benchmark_v2.db").build()
        val store = BenchmarkStore(context)
        try {
            store.checkpoint(batch, pending, listOf(attempt))
            val before = ownershipJournal(canonical)
            val stale = pending.copy(samples = emptyList(), finished = true, state = "COMPLETED")
            val payloads = listOf("[]", "{\"schemaVersion\":3,\"runs\":[]}", kotlinx.serialization.json.Json.encodeToString(BenchmarkBackupPayload.serializer(), BenchmarkBackupPayload(runs = listOf(stale))))
            for (payload in payloads) {
                val failure = runCatching { store.ensureBackupReplacementAllowed() }.exceptionOrNull()
                assertTrue(failure is IllegalStateException)
                assertEquals("Remote benchmark work must be reconciled before saved results can be replaced.", failure?.message)
                store.importBackupJson(payload)
                assertEquals(before, ownershipJournal(canonical))
                assertEquals(pending, store.history.value.single())
                assertEquals(batch, store.batch(batch.id))
            }
        } finally {
            store.close()
            canonical.close()
        }
        val reopened = BenchmarkStore(context)
        try {
            reopened.load()
            assertEquals(pending, reopened.history.value.single())
            assertEquals(batch, reopened.batch(batch.id))
        } finally {
            reopened.close()
        }
    }

    @Test fun bulkPreflightReadsLatestCanonicalOwnerAndDirectImportRetainsIt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val fixture = BenchmarkEngineeringTestFixtures(lane = BenchmarkEngineeringLane.LIVE_GITHUB, families = 1, repeats = 1)
        val pending = pendingRemoteRun(fixture, "late-ownership")
        val beforeAdmission = pending.copy(samples = emptyList(), finished = true)
        val payload = kotlinx.serialization.json.Json.encodeToString(BenchmarkBackupPayload.serializer(), BenchmarkBackupPayload(runs = listOf(beforeAdmission)))
        val writer = androidx.room.Room.databaseBuilder(context, BenchmarkDatabase::class.java, "benchmark_v2.db").build()
        val store = BenchmarkStore(context)
        try {
            store.importBackupJson(payload)
            writer.dao().insertRuns(listOf(BenchmarkRunRecord(pending.id, pending.startedAt, kotlinx.serialization.json.Json.encodeToString(BenchmarkRun.serializer(), pending))))
            assertEquals(beforeAdmission, store.history.value.single())
            val canonical = ownershipJournal(writer)
            for (replacement in listOf("[]", payload)) {
                assertTrue(runCatching { store.ensureBackupReplacementAllowed() }.exceptionOrNull() is IllegalStateException)
                store.importBackupJson(replacement)
                assertEquals(canonical, ownershipJournal(writer))
                assertEquals(pending, store.history.value.single())
            }
        } finally {
            store.close()
            writer.close()
        }
    }

    @Test fun backupReplacementCannotSilentlyDiscardUnreadableCanonicalOwnership() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val writer = androidx.room.Room.databaseBuilder(context, BenchmarkDatabase::class.java, "benchmark_v2.db").build()
        val store = BenchmarkStore(context)
        try {
            store.load()
            val unreadable = BenchmarkRunRecord("unreadable-owner", 1, "{\"id\":\"unreadable-owner\",\"private\":\"credential-canary\"}")
            writer.dao().insertRuns(listOf(unreadable))
            assertTrue(runCatching { store.ensureBackupReplacementAllowed() }.exceptionOrNull() is IllegalStateException)
            val failure = runCatching { store.importBackupJson("[]") }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("Saved benchmark ownership could not be read. Recover the saved results before replacing them.", failure?.message)
            assertEquals(null, failure?.cause)
            assertEquals(listOf(unreadable), writer.dao().runs())
        } finally {
            store.close()
            writer.close()
        }
    }

    @Test fun restoredPendingOwnershipRejectsBulkPreflightButDirectImportKeepsIt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val store = BenchmarkStore(context)
        try {
            for (lane in listOf(BenchmarkEngineeringLane.LIVE_GITHUB, BenchmarkEngineeringLane.EXECUTABLE_CODING)) {
                val fixture = BenchmarkEngineeringTestFixtures(lane = lane, families = 1, repeats = 1)
                val pending = pendingRemoteRun(fixture, "restored-${lane.name}")
                store.importBackupJson(kotlinx.serialization.json.Json.encodeToString(BenchmarkBackupPayload.serializer(), BenchmarkBackupPayload(runs = listOf(pending))))
                val restored = store.history.value.single()
                assertEquals("INTERRUPTED", restored.state)
                assertEquals(pending.samples, restored.samples)
                assertTrue(runCatching { store.ensureBackupReplacementAllowed() }.exceptionOrNull() is IllegalStateException)
                store.importBackupJson("[]")
                assertEquals(restored, store.history.value.single())
                store.save(restored.copy(samples = restored.samples.map { it.copy(cleanupReceipt = "gateway:terminal_readback_confirmed") }))
                store.importBackupJson("[]")
            }
        } finally {
            store.close()
        }
    }

    @Test fun ordinaryAndSettledRemoteResultsStillAllowBackupReplacement() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val fixture = BenchmarkEngineeringTestFixtures(lane = BenchmarkEngineeringLane.LIVE_GITHUB, families = 1, repeats = 1)
        val pending = pendingRemoteRun(fixture, "settled-backup")
        val ordinary = BenchmarkRun("ordinary-backup", "profile", "Name", "OPENAI", "model", "key", false, BenchmarkMode.QUICK, 1)
        val store = BenchmarkStore(context)
        try {
            for (receipt in listOf("gateway:terminal_readback_confirmed", "gateway:no_submission_dispatched")) {
                store.save(pending.copy(samples = pending.samples.map { it.copy(cleanupReceipt = receipt) }))
                store.ensureBackupReplacementAllowed()
                store.importBackupJson(kotlinx.serialization.json.Json.encodeToString(BenchmarkBackupPayload.serializer(), BenchmarkBackupPayload(runs = listOf(ordinary))))
                assertEquals(listOf(ordinary), store.history.value)
                store.importBackupJson("[]")
                assertTrue(store.history.value.isEmpty())
            }
        } finally {
            store.close()
        }
    }

    @Test fun coldCommittedRestoreRetainsEveryCanonicalTableAndRefreshesPendingHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val fixture = BenchmarkEngineeringTestFixtures(lane = BenchmarkEngineeringLane.LIVE_GITHUB, families = 1, repeats = 1)
        val pending = pendingRemoteRun(fixture, "committed-owner")
        val ordinary = BenchmarkRun("retained-ordinary", "profile", "Name", "OPENAI", "model", "key", false, BenchmarkMode.QUICK, 2)
        val batch = BenchmarkBatch(fixture.plan.id, BenchmarkMode.GITHUB_INTEGRATION, pending.startedAt, listOf(BenchmarkQueueItem(0, fixture.profiles.first(), pending.id, pending.configKey, state = BenchmarkQueueState.RUNNING)), state = BenchmarkBatchState.RUNNING, activeOrdinal = 0, engineeringPlan = fixture.plan, caseManifest = fixture.cases)
        val attempt = BenchmarkAttempt("committed-attempt", batch.id, 0, pending.id, fixture.cases.first().id, 0, 1, 90_001, requestDispatched = true)
        val initial = BenchmarkStore(context)
        try {
            initial.checkpoint(batch, pending, listOf(attempt))
            initial.save(ordinary)
        } finally {
            initial.close()
        }
        val canonical = androidx.room.Room.databaseBuilder(context, BenchmarkDatabase::class.java, "benchmark_v2.db").build()
        val restored = BenchmarkStore(context)
        try {
            // Optional display damage must neither discard raw evidence nor block safe retention.
            canonical.dao().insertRuntimeTrials(listOf(RuntimeTrialRecord("optional-runtime", 3, "{unreadable runtime display")))
            canonical.dao().insertQualifications(listOf(RuntimeQualificationRecord("optional-qualification", "{unreadable qualification display")))
            canonical.dao().insertSnapshot(BenchmarkSnapshotRecord(99, 3, "{unreadable score display"))
            canonical.dao().pointTo(BenchmarkCurrentRecord(generation = 99))
            val before = canonicalBenchmarkSection(canonical)
            val stale = pending.copy(samples = emptyList(), finished = true, state = "COMPLETED")
            val payloads = listOf("[]", kotlinx.serialization.json.Json.encodeToString(BenchmarkBackupPayload.serializer(), BenchmarkBackupPayload(runs = listOf(stale))))
            for (payload in payloads) {
                assertEquals(BenchmarkBackupImportOutcome.RETAINED_PENDING_OWNERSHIP, restored.importCommittedBackupJson(payload))
                assertEquals(before, canonicalBenchmarkSection(canonical))
                assertEquals(setOf(pending, ordinary), restored.history.value.toSet())
                assertEquals(batch, restored.batch(batch.id))
                assertTrue(restored.warning.value.orEmpty().startsWith(BENCHMARK_BACKUP_RETAINED_NOTICE))
                assertTrue(restored.warning.value.orEmpty().contains("display data is unavailable"))
                assertEquals(null, restored.snapshot.value)
                assertTrue(restored.runtimeTrials.value.isEmpty())
                assertTrue(restored.runtimeQualifications.value.isEmpty())
            }
        } finally {
            restored.close()
            canonical.close()
        }
    }

    @Test fun committedRestoreRetryAfterFirstImportRetainsIncomingPendingOwnership() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val fixture = BenchmarkEngineeringTestFixtures(lane = BenchmarkEngineeringLane.LIVE_GITHUB, families = 1, repeats = 1)
        val pending = pendingRemoteRun(fixture, "incoming-owner")
        val batch = BenchmarkBatch(fixture.plan.id, BenchmarkMode.GITHUB_INTEGRATION, pending.startedAt, listOf(BenchmarkQueueItem(0, fixture.profiles.first(), pending.id, pending.configKey, state = BenchmarkQueueState.RUNNING)), state = BenchmarkBatchState.RUNNING, activeOrdinal = 0, engineeringPlan = fixture.plan, caseManifest = fixture.cases)
        val attempt = BenchmarkAttempt("incoming-attempt", batch.id, 0, pending.id, fixture.cases.first().id, 0, 1, 90_001, requestDispatched = true)
        val payload = kotlinx.serialization.json.Json.encodeToString(BenchmarkBackupPayload.serializer(), BenchmarkBackupPayload(runs = listOf(pending), batches = listOf(batch), attempts = listOf(attempt), batchEvents = listOf(BenchmarkBatchEvent(batch.id, 1, "CHECKPOINT", 1, pending.id, attempt.id))))
        val initial = BenchmarkStore(context)
        try {
            assertEquals(BenchmarkBackupImportOutcome.REPLACED, initial.importCommittedBackupJson(payload))
        } finally {
            initial.close()
        }
        val canonical = androidx.room.Room.databaseBuilder(context, BenchmarkDatabase::class.java, "benchmark_v2.db").build()
        val restored = BenchmarkStore(context)
        try {
            val before = canonicalBenchmarkSection(canonical)
            repeat(2) {
                assertEquals(BenchmarkBackupImportOutcome.RETAINED_PENDING_OWNERSHIP, restored.importCommittedBackupJson(payload))
                assertEquals(before, canonicalBenchmarkSection(canonical))
                assertEquals(pending.samples, restored.history.value.single().samples)
                assertEquals(BenchmarkBatchState.INTERRUPTED, restored.batch(batch.id)?.state)
                assertEquals(BENCHMARK_BACKUP_RETAINED_NOTICE, restored.warning.value)
            }
            assertTrue(runCatching { restored.ensureBackupReplacementAllowed() }.exceptionOrNull() is IllegalStateException)
            assertEquals(before, canonicalBenchmarkSection(canonical))
            val retained = restored.history.value.single()
            restored.save(retained.copy(samples = retained.samples.map { it.copy(cleanupReceipt = "gateway:terminal_readback_confirmed") }))
            assertEquals(BenchmarkBackupImportOutcome.REPLACED, restored.importCommittedBackupJson("[]"))
            assertTrue(restored.history.value.isEmpty())
            assertEquals(null, restored.warning.value)
        } finally {
            restored.close()
            canonical.close()
        }
    }

    @Test fun committedRestoreDoesNotTurnUnreadableOwnershipIntoPartialSuccess() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val writer = androidx.room.Room.databaseBuilder(context, BenchmarkDatabase::class.java, "benchmark_v2.db").build()
        val store = BenchmarkStore(context)
        try {
            writer.dao().insertRuns(listOf(BenchmarkRunRecord("unknown-owner", 1, "{unreadable ownership")))
            val before = canonicalBenchmarkSection(writer)
            val failure = runCatching { store.importCommittedBackupJson("[]") }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("Saved benchmark ownership could not be read. Recover the saved results before replacing them.", failure?.message)
            assertEquals(before, canonicalBenchmarkSection(writer))
        } finally {
            store.close()
            writer.close()
        }
    }

    @Test fun legacyMirrorInitializationCannotOverwriteAConcurrentPendingOwner() = assertColdInitializationPreservesPendingOwner { it.importBackupJson("[]") }

    @Test fun publicLoadCannotOverwriteAConcurrentPendingOwnerDuringLegacyInitialization() = assertColdInitializationPreservesPendingOwner { it.load() }

    private fun assertColdInitializationPreservesPendingOwner(initialize: suspend (BenchmarkStore) -> Unit) = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val preferences = application.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val fixture = BenchmarkEngineeringTestFixtures(lane = BenchmarkEngineeringLane.LIVE_GITHUB, families = 1, repeats = 1)
        val pending = pendingRemoteRun(fixture, "legacy-race-owner")
        val legacy = pending.copy(samples = emptyList(), finished = true, state = "COMPLETED")
        preferences.edit().putString("history", kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(BenchmarkRun.serializer()), listOf(legacy))).commit()
        val schema = androidx.room.Room.databaseBuilder(application, BenchmarkDatabase::class.java, "benchmark_v2.db").build()
        schema.openHelper.writableDatabase
        schema.close()
        val writer = android.database.sqlite.SQLiteDatabase.openDatabase(application.getDatabasePath("benchmark_v2.db").path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
        writer.rawQuery("PRAGMA busy_timeout=0", null).use { it.moveToFirst() }
        val payload = kotlinx.serialization.json.Json.encodeToString(BenchmarkRun.serializer(), pending)
        fun admitOwner() = writer.execSQL("INSERT OR REPLACE INTO benchmark_runs(id, startedAt, payload) VALUES (?, ?, ?)", arrayOf<Any>(pending.id, pending.startedAt, payload))
        val attempted = java.util.concurrent.atomic.AtomicBoolean()
        var blockedByInitialization = false
        val hooked = object : android.content.SharedPreferences by preferences {
            override fun getString(key: String?, default: String?): String? {
                if (key == "history" && attempted.compareAndSet(false, true)) {
                    try {
                        admitOwner()
                    } catch (_: android.database.sqlite.SQLiteDatabaseLockedException) {
                        blockedByInitialization = true
                    }
                }
                return preferences.getString(key, default)
            }
        }
        val context = object : android.content.ContextWrapper(application) {
            override fun getSharedPreferences(name: String, mode: Int) = if (name == "profile_benchmarks_v1") hooked else super.getSharedPreferences(name, mode)
        }
        val store = BenchmarkStore(context)
        try {
            initialize(store)
            assertTrue(attempted.get())
            assertTrue("The pending writer must not commit between the empty canonical read and legacy publication.", blockedByInitialization)
            // Room may still be updating its invalidation log after the protected transaction returns.
            writer.rawQuery("PRAGMA busy_timeout=2000", null).use { it.moveToFirst() }
            admitOwner()
            val retained = writer.rawQuery("SELECT payload FROM benchmark_runs WHERE id = ?", arrayOf(pending.id)).use { cursor ->
                assertTrue(cursor.moveToFirst())
                kotlinx.serialization.json.Json.decodeFromString(BenchmarkRun.serializer(), cursor.getString(0))
            }
            assertEquals(pending, retained)
        } finally {
            store.close()
            writer.close()
        }
    }

    private fun canonicalBenchmarkSection(database: BenchmarkDatabase): Map<String, List<List<String?>>> =
        listOf("benchmark_runs", "benchmark_batches", "benchmark_queue", "benchmark_attempts", "benchmark_batch_events", "benchmark_snapshots", "benchmark_current", "runtime_trials", "runtime_qualifications", "runtime_decisions").associateWith { table ->
            database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY rowid").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(List(cursor.columnCount) { column -> if (cursor.isNull(column)) null else cursor.getString(column) })
                }
            }
        }

    private fun ownershipJournal(database: BenchmarkDatabase): Map<String, List<String>> =
        listOf("benchmark_runs", "benchmark_batches", "benchmark_queue", "benchmark_attempts", "benchmark_batch_events").associateWith { table ->
            database.openHelper.readableDatabase.query("SELECT payload FROM $table ORDER BY rowid").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
        }

    private fun pendingRemoteRun(fixture: BenchmarkEngineeringTestFixtures, id: String): BenchmarkRun {
        val test = fixture.cases.first()
        val sample = fixture.sample(test, false).copy(
            outcome = BenchmarkOutcome.PARTIAL,
            attemptId = "$id:${test.id}:0", logicalRequestId = "logical-$id", physicalRequestIds = listOf("generation-$id"),
            inputTokens = 17, outputTokens = 23, engineeringVerification = null,
            cleanupReceipt = "gateway:submission_outcome_unknown",
            engineeringAttempts = listOf(BenchmarkEngineeringAttemptReceipt(0, "original-$id", benchmarkHash("candidate"), null, "VERIFICATION_PENDING", false, 17, 23, 100))
        )
        return fixture.runs().first().copy(id = id, samples = listOf(sample), finished = false, endedAt = null, state = "RUNNING", queueOrdinal = 0)
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
            assertTrue(restored.items.all { it.profile.apiUrl.isEmpty() && it.profile.token == null })
            assertEquals(BenchmarkQueueState.INTERRUPTED, restored.items.first().state)
            assertEquals(listOf("next"), restored.pending.map { it.profile.uid })
            assertTrue(restored.canResume)
        } finally {
            store.close()
        }
    }

    @Test fun canonicalBackupRetainsEndpointsAndInterruptsDispatchedAttempts() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val profile = dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2(uid = "profile", name = "Name", model = "model", apiUrl = "https://private.example/custom-provider", batchMode = true)
        val primary = profile.copy(uid = "primary", systemPrompt = "Private saved instructions")
        val reviewer = profile.copy(uid = "reviewer", batchApiUrl = "https://private.example/batch")
        val batch = BenchmarkBatch("canonical-backup", BenchmarkMode.QUICK, 1, listOf(BenchmarkQueueItem(0, profile, "run", "key", state = BenchmarkQueueState.RUNNING), BenchmarkQueueItem(1, profile.copy(uid = "next"), "next-run", "key")), state = BenchmarkBatchState.RUNNING, activeOrdinal = 0, delegationPrimary = primary, delegationReviewer = reviewer)
        val store = BenchmarkStore(context)
        try {
            store.checkpoint(batch, attempts = listOf(BenchmarkAttempt("attempt", batch.id, 0, "run", "case", 0, 1, 90_001, requestDispatched = true)))
            store.importBackupJson(store.exportJson())
            val restored = checkNotNull(store.batch(batch.id))
            assertEquals(BenchmarkBatchState.INTERRUPTED, restored.state)
            assertEquals(listOf("profile", "next"), restored.items.map { it.profile.uid })
            assertEquals(profile, restored.items.first().profile)
            assertEquals(primary, restored.delegationPrimary)
            assertEquals(reviewer, restored.delegationReviewer)
            assertTrue(restored.redactedEndpointProfileUids.isEmpty())
            assertEquals(listOf("next"), restored.pending.map { it.profile.uid })
            val restoredPayload = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<BenchmarkBackupPayload>(store.exportJson())
            assertEquals("INTERRUPTED", restoredPayload.attempts.single().state)
            assertTrue(restoredPayload.attempts.single().dispatchUncertain)
        } finally {
            store.close()
        }
    }

    @Test fun redactedQueueAndDelegationEndpointsStayMarkedThroughCompleteBackup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val profile = dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2(uid = "target", name = "Name", model = "model", apiUrl = "https://private.example/custom-provider", batchMode = true, batchApiUrl = "https://private.example/batch", token = "private-token", secretRef = "private-vault-reference", systemPrompt = "Private saved instructions")
        val batch = BenchmarkBatch("redacted-backup", BenchmarkMode.QUICK, 1, listOf(BenchmarkQueueItem(0, profile, "run", "key")), delegationPrimary = profile.copy(uid = "primary"), delegationReviewer = profile.copy(uid = "reviewer"))
        val store = BenchmarkStore(context)
        try {
            store.checkpoint(batch)
            val report = benchmarkExport(store.exportJson(), false)
            listOf("private.example", "private-token", "private-vault-reference", "Private saved instructions").forEach { assertTrue(!report.contains(it)) }
            store.importBackupJson(report)
            val restored = checkNotNull(store.batch(batch.id))
            assertEquals(setOf("target", "primary", "reviewer"), restored.redactedEndpointProfileUids)
            assertEquals(BenchmarkBatchState.INTERRUPTED, restored.state)
            val restoredProfiles = listOf(restored.items.single().profile, checkNotNull(restored.delegationPrimary), checkNotNull(restored.delegationReviewer))
            assertTrue(restoredProfiles.all { it.apiUrl.isEmpty() && it.batchApiUrl == null && it.token == null && it.secretRef == null && it.systemPrompt == null })
            store.importBackupJson(store.exportJson())
            assertEquals(restored.redactedEndpointProfileUids, checkNotNull(store.batch(batch.id)).redactedEndpointProfileUids)
        } finally {
            store.close()
        }
    }

    @Test fun malformedBackupsCannotMutateHistoryOrExposeTheirPayloadInErrors() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val profile = dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2(uid = "profile", name = "Name", model = "model")
        val batch = BenchmarkBatch("preserved", BenchmarkMode.QUICK, 1, listOf(BenchmarkQueueItem(0, profile, "run", "key")))
        val run = BenchmarkRun("run", profile.uid, profile.name, "OPENAI", profile.model, "key", false, BenchmarkMode.QUICK, 1)
        val store = BenchmarkStore(context)
        try {
            store.checkpoint(batch, run)
            val malformed = listOf("{\"private-diagnostic-marker\":", "{\"runs\":[{\"id\":null,\"profileUid\":\"private-diagnostic-marker\"}]}", "[{\"id\":null,\"profileUid\":\"private-diagnostic-marker\"}]")
            for (payload in malformed) {
                val failures = listOf(runCatching { store.validateBackupJson(payload) }.exceptionOrNull(), runCatching { store.importBackupJson(payload) }.exceptionOrNull())
                for (failure in failures) {
                    assertTrue(failure is IllegalArgumentException)
                    // Coroutine stack-trace recovery can retain the already-sanitized
                    // exception as a cause when import crosses withContext(IO).
                    // Every reachable error must still be sanitized, including that cause.
                    val visited = mutableSetOf<Throwable>()
                    var current = failure
                    while (current != null) {
                        assertTrue("Backup errors must not contain causal cycles", visited.add(current))
                        assertEquals(IllegalArgumentException::class.java, current.javaClass)
                        assertEquals("Invalid benchmark backup data. The JSON structure does not match a supported benchmark backup.", current.message)
                        assertTrue("Backup errors must not attach suppressed failures", current.suppressed.isEmpty())
                        current = current.cause
                    }
                    assertTrue(!checkNotNull(failure).stackTraceToString().contains("private-diagnostic-marker"))
                }
                assertEquals(listOf(run), store.history.value)
                assertEquals(batch, store.batch(batch.id))
            }
        } finally {
            store.close()
        }
    }

    @Test fun redactedActiveBatchEndpointIsMarkedWhenThePrimaryEndpointIsRetained() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val report = """{"format":"gptmobile.benchmarks.v2","schemaVersion":2,"storageSchemaVersion":3,"batches":[{"id":"batch-route","mode":"QUICK","createdAt":1,"items":[{"ordinal":0,"profile":{"uid":"profile","name":"Name","apiUrl":"https://example.org/v1","batchMode":true,"batchApiUrl":null},"runId":"run","configKey":"key"}]}]}"""
        val store = BenchmarkStore(context)
        try {
            store.importBackupJson(report)
            val restored = checkNotNull(store.batch("batch-route"))
            assertEquals("https://example.org/v1", restored.items.single().profile.apiUrl)
            assertEquals(setOf("profile"), restored.redactedEndpointProfileUids)
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

    @Test fun duplicateCaseRowsDoNotInflateCanonicalHistoryOrAttempts() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = BenchmarkStore(context)
        try {
            val batch = BenchmarkBatch("duplicate-logical-case", BenchmarkMode.QUICK, 1, emptyList())
            store.checkpoint(batch)
            val original = BenchmarkSample(
                "fixture-case",
                "Fixture case",
                "tools",
                BenchmarkOutcome.FAILED,
                requestDispatched = true,
                failureCategory = "MODEL_QUALITY"
            )
            val accidentalDuplicate = original.copy(
                outcome = BenchmarkOutcome.SKIPPED,
                failureCategory = "SKIPPED_DEPENDENCY"
            )
            val run = BenchmarkRun(
                "duplicate-case-run",
                "profile",
                "Profile",
                "OPENAI",
                "model",
                "config",
                false,
                BenchmarkMode.QUICK,
                1,
                listOf(original, accidentalDuplicate),
                batchId = batch.id,
                plannedTrials = 1
            )
            store.checkpoint(batch, run, event = "PROFILE_COMPLETED")
            val saved = store.history.value.single { it.id == run.id }
            assertEquals(1, saved.samples.size)
            assertEquals(BenchmarkOutcome.FAILED, saved.samples.single().outcome)
            assertEquals(1, benchmarkCoverage(saved).accounted)
            val attempts = kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
            }.decodeFromString<BenchmarkBackupPayload>(store.exportJson()).attempts
            assertEquals(1, attempts.count { it.runId == run.id })
        } finally {
            store.close()
        }
    }
}
