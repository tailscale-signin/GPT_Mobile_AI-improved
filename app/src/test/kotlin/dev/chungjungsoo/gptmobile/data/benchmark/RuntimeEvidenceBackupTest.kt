package dev.chungjungsoo.gptmobile.data.benchmark

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.localruntime.LocalExecutionPlan
import dev.chungjungsoo.gptmobile.data.localruntime.QualificationStatus
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeQualificationKey
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeQualificationResult
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeTrial
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RuntimeEvidenceBackupTest {
    private val key = RuntimeQualificationKey("device", "firmware", "runtime", "dispatch", "artifact", "gpu", contextTokens = 2048, samplerIdentity = "default")
    private val plan = LocalExecutionPlan("plan", "artifact", "runtime", "gpu", contextTokens = 2048, tokenizerTemplateIdentity = "artifact")

    @Before fun reset() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("benchmark_v2.db")
        context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `restored native evidence remains historical and loses authority`() = runBlocking {
        val store = BenchmarkStore(ApplicationProvider.getApplicationContext())
        try {
            store.saveRuntimeTrial(RuntimeTrial("run", key, plan, 1, true, durationMs = 100))
            store.saveQualification(RuntimeQualificationResult(key, plan, QualificationStatus.MEASURED, listOf("run")))
            val backup = store.exportJson()
            store.clearRuntimeEvidence()
            store.importBackupJson(backup)
            assertTrue(store.runtimeTrials.value.single().historical)
            assertEquals(QualificationStatus.HISTORICAL, store.runtimeQualifications.value.single().status)
            assertFalse(store.runtimeQualifications.value.single().actualBackendConfirmed)
        } finally {
            store.close()
        }
    }

    @Test fun `unfinished runtime trial survives restart as an interruption`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val before = BenchmarkStore(context)
        before.saveRuntimeTrial(RuntimeTrial("interrupted", key, plan, 1, completed = false, finished = false, durationMs = 0))
        before.close()
        val after = BenchmarkStore(context)
        try {
            after.markInterruptedRuns()
            val trial = after.runtimeTrials.value.single()
            assertTrue(trial.finished)
            assertFalse(trial.completed)
            assertTrue(trial.failure!!.contains("interrupted"))
        } finally {
            after.close()
        }
    }

    @Test fun `version one database migrates without losing history`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "benchmark-migration-test.db"
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE benchmark_runs (id TEXT NOT NULL PRIMARY KEY, startedAt INTEGER NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE benchmark_snapshots (generation INTEGER NOT NULL PRIMARY KEY, createdAt INTEGER NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE benchmark_current (id INTEGER NOT NULL PRIMARY KEY, generation INTEGER NOT NULL)")
            db.execSQL("INSERT INTO benchmark_runs VALUES ('retained', 123, '{}')")
            db.version = 1
        }
        val database = androidx.room.Room.databaseBuilder(context, BenchmarkDatabase::class.java, name).addMigrations(BenchmarkDatabase.MIGRATION_1_2, BenchmarkDatabase.MIGRATION_2_3).build()
        try {
            assertEquals("retained", database.dao().runs().single().id)
            assertTrue(database.dao().runtimeTrials().isEmpty())
            assertTrue(database.dao().runtimeQualifications().isEmpty())
            assertTrue(database.dao().runtimeDecisions().isEmpty())
            assertEquals(3, database.openHelper.writableDatabase.version)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun `legacy list and version one backups remain readable`() = runBlocking {
        val store = BenchmarkStore(ApplicationProvider.getApplicationContext())
        try {
            store.importBackupJson("[]")
            store.importBackupJson("{\"schemaVersion\":1,\"runs\":[]}")
            store.importBackupJson("{\"format\":\"gptmobile.benchmarks.v2\",\"schemaVersion\":2,\"storageSchemaVersion\":3,\"runs\":[]}")
            assertTrue(runCatching { store.importBackupJson("{\"schemaVersion\":2,\"storageSchemaVersion\":4,\"runs\":[]}") }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(store.history.value.isEmpty())
            assertTrue(store.runtimeTrials.value.isEmpty())
        } finally {
            store.close()
        }
    }
}
