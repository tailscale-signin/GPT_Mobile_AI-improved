package dev.chungjungsoo.gptmobile.data.benchmark

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.localruntime.LocalExecutionPlan
import dev.chungjungsoo.gptmobile.data.localruntime.LocalPlanDecision
import dev.chungjungsoo.gptmobile.data.localruntime.QualificationStatus
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimePlanDecisionRecord
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeQualificationKey
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeQualificationResult
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeTrial
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
class BenchmarkStreamingExportStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun reset() {
        context.deleteDatabase("benchmark_v2.db")
        context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun canonicalStreamingPreservesAllSectionsUnknownFieldsAndUnicodeAcrossSqlChunks() = runBlocking {
        val store = BenchmarkStore(context)
        val inspection = Room.databaseBuilder(context, BenchmarkDatabase::class.java, "benchmark_v2.db").addMigrations(BenchmarkDatabase.MIGRATION_1_2, BenchmarkDatabase.MIGRATION_2_3).build()
        try {
            val body = "🧪".repeat(70_000) + " preserved final evidence"
            val sample = BenchmarkSample("case", "Case", "task", BenchmarkOutcome.PARTIAL, canonicalAnswer = body, outputCharacters = body.length, answerHash = benchmarkHash(body), requestDispatched = true, error = "GET https://example.org?token=PRIVATE_QUERY")
            val profile = PlatformV2(uid = "profile", name = "Profile", model = "model", token = "PRIVATE_TOKEN", secretRef = "PRIVATE_SECRET_REF", apiUrl = "https://user:PRIVATE_PASSWORD@example.org/v1?api_key=PRIVATE_QUERY")
            val batch = BenchmarkBatch("batch", BenchmarkMode.QUICK, 1, listOf(BenchmarkQueueItem(0, profile, "run", "config", state = BenchmarkQueueState.RUNNING)), state = BenchmarkBatchState.RUNNING)
            val run = BenchmarkRun("run", profile.uid, profile.name, "CUSTOM", profile.model, "config", false, BenchmarkMode.QUICK, 1, listOf(sample), finished = false, suiteVersion = 9, batchId = batch.id)
            store.checkpoint(batch, run, event = "CASE_FINISHED")
            val key = RuntimeQualificationKey("device", "firmware", "runtime", "dispatch", "artifact", "gpu", contextTokens = 2048, samplerIdentity = "default")
            val plan = LocalExecutionPlan("plan", "artifact", "runtime", "gpu", contextTokens = 2048, tokenizerTemplateIdentity = "artifact")
            store.saveRuntimeTrial(RuntimeTrial("trial", key, plan, 1, true, durationMs = 100))
            store.saveQualification(RuntimeQualificationResult(key, plan, QualificationStatus.MEASURED, listOf("trial")))
            store.saveRuntimeDecision(RuntimePlanDecisionRecord("decision", 1, key, LocalPlanDecision(plan, QualificationStatus.MEASURED, reason = "Observed"), "gpu"))

            val dao = inspection.dao()
            val record = dao.runs().single()
            val original = Json.parseToJsonElement(record.payload).jsonObject
            val unknown = JsonObject(mapOf("futureRunField" to JsonPrimitive("retained"), "futureUnicode" to JsonPrimitive(body), "password" to JsonPrimitive("PRIVATE_FUTURE_PASSWORD")))
            dao.insertRuns(listOf(record.copy(payload = JsonObject(original + ("futureEvidence" to unknown)).toString())))
            val attempt = dao.attempts(batch.id).single()
            val attemptOriginal = Json.parseToJsonElement(attempt.payload).jsonObject
            dao.upsertAttempts(listOf(attempt.copy(payload = JsonObject(attemptOriginal + ("futureAttemptField" to JsonPrimitive("also retained"))).toString())))

            for (detailed in listOf(false, true)) {
                val legacy = Json.parseToJsonElement(store.exportJson(detailed)).jsonObject
                val withUnknown = JsonObject(
                    legacy + mapOf(
                        "runs" to JsonArray(legacy.getValue("runs").jsonArray.map { JsonObject(it.jsonObject + ("futureEvidence" to unknown)) }),
                        "attempts" to JsonArray(legacy.getValue("attempts").jsonArray.map { JsonObject(it.jsonObject + ("futureAttemptField" to JsonPrimitive("also retained"))) })
                    )
                )
                val expected = Json.parseToJsonElement(benchmarkExport(withUnknown.toString(), detailed)).jsonObject
                val output = TrackingOutput()
                store.writeExport(output, detailed)
                assertFalse(output.closed)
                assertTrue(output.flushed)
                val actual = Json.parseToJsonElement(output.toString(Charsets.UTF_8)).jsonObject
                assertEquals(expected - "exportedAt", actual - "exportedAt")
                assertEquals("IN_PROGRESS_AT_EXPORT", actual.getValue("snapshotState").jsonPrimitive.content)
                val restored = actual.getValue("runs").jsonArray.single().jsonObject.getValue("samples").jsonArray.single().jsonObject
                assertEquals(body, restored.getValue("canonicalAnswer").jsonPrimitive.content)
                assertEquals(benchmarkHash(body), restored.getValue("answerHash").jsonPrimitive.content)
                assertEquals(detailed, actual.containsKey("scoreHistory"))
                assertEquals(detailed, actual.containsKey("batchEvents"))
                for (field in listOf("attempts", "runtimeTrials", "runtimeQualifications", "runtimeDecisions", "batches")) assertEquals(1, actual.getValue(field).jsonArray.size)
                assertFalse(output.toString(Charsets.UTF_8).contains("PRIVATE_"))
            }
            assertEquals(BenchmarkBatchState.RUNNING, store.batch(batch.id)?.state)
            assertFalse(store.history.value.single().finished)
        } finally {
            inspection.close()
            store.close()
        }
    }

    @Test fun writeFailureDoesNotCloseTheCallerStreamOrPoisonTheNextExport() = runBlocking {
        val store = BenchmarkStore(context)
        try {
            val run = BenchmarkRun("run", "profile", "Profile", "CUSTOM", "model", "config", false, BenchmarkMode.QUICK, 1)
            store.save(run)
            var closed = false
            val failed = object : OutputStream() {
                private var remaining = 100
                override fun write(value: Int) {
                    if (--remaining < 0) throw IOException("synthetic destination failure")
                }
                override fun close() {
                    closed = true
                }
            }
            val failure = runCatching { store.writeExport(failed, true) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertFalse(closed)
            val retry = TrackingOutput()
            store.writeExport(retry, true)
            assertEquals("run", Json.parseToJsonElement(retry.toString(Charsets.UTF_8)).jsonObject.getValue("runs").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals(listOf(run), store.history.value)
        } finally {
            store.close()
        }
    }

    private class TrackingOutput : ByteArrayOutputStream() {
        var closed = false
        var flushed = false
        override fun close() {
            closed = true
            super.close()
        }
        override fun flush() {
            flushed = true
            super.flush()
        }
    }
}
