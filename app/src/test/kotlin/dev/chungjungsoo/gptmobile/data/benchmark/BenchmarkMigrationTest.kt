package dev.chungjungsoo.gptmobile.data.benchmark

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.localruntime.LocalExecutionPlan
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeQualificationKey
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeTrial
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BenchmarkMigrationTest {
    @Test fun publishedV2DatabasePreservesHistoricalEvidenceAndAddsEmptyQueueTables() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "benchmark-v2-fixture.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val run = BenchmarkRun("retained", "profile", "Name", "OPENAI", "model", "key", false, BenchmarkMode.QUICK, 123)
        val key = RuntimeQualificationKey("device", "firmware", "runtime", "dispatch", "artifact", "cpu", contextTokens = 2048, samplerIdentity = "default")
        val plan = LocalExecutionPlan("plan", "artifact", "runtime", "cpu", contextTokens = 2048, tokenizerTemplateIdentity = "artifact")
        val trial = RuntimeTrial("native-trial", key, plan, 1, true, durationMs = 100)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val schema = javaClass.classLoader!!.getResource("benchmark-schema-v2.sql")!!.readText()
            schema.lineSequence().filterNot { it.startsWith("--") }.joinToString("\n").split(';').filter { it.isNotBlank() }.forEach(old::execSQL)
            old.execSQL("INSERT INTO benchmark_runs VALUES (?, ?, ?)", arrayOf<Any?>(run.id, run.startedAt, Json.encodeToString(run)))
            old.execSQL("INSERT INTO benchmark_snapshots VALUES (7, 123, ?)", arrayOf(Json.encodeToString(BenchmarkScoreSnapshot(7, 123, policy = "benchmark-score-v3", rows = emptyList()))))
            old.execSQL("INSERT INTO benchmark_current VALUES (1, 7)")
            old.execSQL("INSERT INTO runtime_trials VALUES (?, ?, ?)", arrayOf<Any?>(trial.id, trial.timestamp, Json.encodeToString(trial)))
            old.version = 2
        }
        val migrated = Room.databaseBuilder(context, BenchmarkDatabase::class.java, name).addMigrations(BenchmarkDatabase.MIGRATION_2_3).build()
        try {
            assertEquals(run, Json.decodeFromString<BenchmarkRun>(migrated.dao().runs().single().payload))
            assertEquals("LEGACY_IMPORTED", Json.decodeFromString<BenchmarkRun>(migrated.dao().runs().single().payload).state)
            assertEquals(trial, Json.decodeFromString<RuntimeTrial>(migrated.dao().runtimeTrials().single().payload))
            assertEquals(7L, migrated.dao().current()?.generation)
            assertTrue(migrated.dao().batches().isEmpty())
            assertTrue(migrated.dao().attempts("any").isEmpty())
            assertTrue(migrated.dao().events("any").isEmpty())
            assertEquals(3, migrated.openHelper.writableDatabase.version)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
