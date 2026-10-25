package dev.chungjungsoo.gptmobile.data.benchmark

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BenchmarkColdStreamingExportStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun reset() {
        context.deleteDatabase("benchmark_v2.db")
        preferences().edit().clear().commit()
    }

    @Test fun coldCanonicalRowsLargerThanCursorWindowPreserveRawUnknownsGradesAndCapturedScores() =
        runBlocking {
            val originalBody = "🧪".repeat(600_000) + " complete original body"
            val legacy = IntelligenceBenchmark.batches.first()
            val answer = requireNotNull(legacy.expected)
            val preview = BenchmarkCase("preview-only", "Preview", "task", "Retained custom prompt", "OK")
            val run =
                BenchmarkRun(
                    "cold-run",
                    "profile",
                    "Profile",
                    "CUSTOM",
                    "model",
                    "config",
                    false,
                    BenchmarkMode.FULL,
                    10,
                    listOf(
                        BenchmarkSample(
                            "large",
                            "Large",
                            "task",
                            BenchmarkOutcome.PARTIAL,
                            canonicalAnswer = originalBody,
                            outputCharacters = originalBody.length,
                            answerHash = benchmarkHash(originalBody)
                        ),
                        BenchmarkSample(
                            legacy.id,
                            legacy.label,
                            legacy.category,
                            BenchmarkOutcome.PASSED,
                            canonicalAnswer = answer,
                            outputCharacters = answer.length,
                            answerHash = benchmarkHash(answer),
                            workload = legacy.workload
                        ),
                        BenchmarkSample(
                            preview.id,
                            preview.label,
                            preview.category,
                            BenchmarkOutcome.PASSED,
                            preview = "OK",
                            outputCharacters = 0
                        )
                    ),
                    finished = false,
                    suiteVersion = 9,
                    plannedTrials = 3,
                    caseManifest = listOf(legacy, preview)
                )
            val raw =
                JsonObject(
                    benchmarkExportJson.encodeToJsonElement(BenchmarkRun.serializer(), run).jsonObject +
                        (
                            "futureEvidence" to
                                JsonObject(mapOf("complete" to JsonPrimitive("unchanged"), "password" to JsonPrimitive("PRIVATE_SECRET")))
                            )
                )
            val payload = raw.toString()
            assertTrue(payload.toByteArray().size > 2 * 1024 * 1024)
            val captured = BenchmarkScoreSnapshot(7, 20, rows = emptyList(), enrollment = mapOf("profile" to "captured-revision"))
            val scoreRaw =
                JsonObject(
                    benchmarkExportJson.encodeToJsonElement(BenchmarkScoreSnapshot.serializer(), captured).jsonObject +
                        ("futureScoreEvidence" to JsonPrimitive("retained"))
                )
            val active = mapOf("profile" to "current-revision")
            val stale = "NOT_JSON_CANONICAL_MIRROR_" + "x".repeat(512 * 1024)
            preferences()
                .edit()
                .putString("history", stale)
                .putString("active_revisions", "{\"profile\":\"current-revision\"}")
                .commit()
            val inspection = database()
            val guarded = ExportPreferenceContext(context, forbidHistory = true)
            val store = BenchmarkStore(guarded)
            try {
                assertEquals(0, guarded.enrollmentReads)
                inspection.dao().insertRuns(listOf(BenchmarkRunRecord(run.id, run.startedAt, payload)))
                inspection.dao().insertSnapshot(BenchmarkSnapshotRecord(captured.generation, captured.createdAt, scoreRaw.toString()))
                inspection.dao().pointTo(BenchmarkCurrentRecord(generation = captured.generation))
                for (detailed in listOf(false, true)) {
                    val expected = compatibility(listOf(raw), captured, scoreRaw, active, detailed)
                    val output = ByteArrayOutputStream()
                    store.writeExport(output, detailed)
                    val actual = benchmarkExportJson.parseToJsonElement(output.toString(Charsets.UTF_8)).jsonObject
                    assertEquals(expected - "exportedAt", actual - "exportedAt")
                    assertEquals("IN_PROGRESS_AT_EXPORT", actual.getValue("snapshotState").jsonPrimitive.content)
                    assertEquals(
                        "captured-revision",
                        actual
                            .getValue("scoreSelection")
                            .jsonArray
                            .single()
                            .jsonObject
                            .getValue("enrolledRevision")
                            .jsonPrimitive.content
                    )
                    assertEquals(
                        originalBody,
                        actual
                            .getValue(
                                "runs"
                            ).jsonArray
                            .single()
                            .jsonObject
                            .getValue(
                                "samples"
                            ).jsonArray
                            .first()
                            .jsonObject
                            .getValue("canonicalAnswer")
                            .jsonPrimitive.content
                    )
                    assertFalse(output.toString(Charsets.UTF_8).contains("PRIVATE_SECRET"))
                }
                assertEquals(1, guarded.enrollmentReads)
                assertTrue(store.history.value.isEmpty())
                assertEquals(null, store.snapshot.value)
                assertEquals(null, store.loadWarning)
                assertEquals(payload, readColdExportPayload(inspection, "benchmark_runs", "id", run.id))
                assertEquals(
                    scoreRaw.toString(),
                    readColdExportPayload(inspection, "benchmark_snapshots", "generation", captured.generation)
                )
                assertEquals(stale, preferences().getString("history", null))
                assertFalse(preferences().contains("recovery_history"))
            } finally {
                store.close()
                inspection.close()
            }
        }

    @Test fun anInitializedEmptyCanonicalStoreNeverResurrectsOrParsesItsStaleMirror() =
        runBlocking {
            preferences()
                .edit()
                .putString("history", "not valid JSON")
                .putString("active_revisions", "{}")
                .commit()
            val inspection = database()
            inspection.dao().pointTo(BenchmarkCurrentRecord(generation = 0))
            val guarded = ExportPreferenceContext(context, forbidHistory = true)
            val store = BenchmarkStore(guarded)
            try {
                val output = ByteArrayOutputStream()
                store.writeExport(output, true)
                val actual = benchmarkExportJson.parseToJsonElement(output.toString(Charsets.UTF_8)).jsonObject
                assertTrue(actual.getValue("runs").jsonArray.isEmpty())
                assertEquals(JsonNull, actual.getValue("scoreTable"))
                assertTrue(actual.getValue("enrollment").jsonObject.isEmpty())
                assertTrue(store.history.value.isEmpty())
                assertEquals(null, inspection.dao().current())
                assertTrue(inspection.dao().canonicalInitialized())
                assertFalse(preferences().contains("recovery_history"))
            } finally {
                store.close()
                inspection.close()
            }
        }

    @Test fun legacyOnlyExportReadsIndividualOriginalRecordsWithoutMigratingOrRegradingThem() =
        runBlocking {
            val legacy = IntelligenceBenchmark.batches.first()
            val answer = requireNotNull(legacy.expected)
            val records =
                listOf(answer, "{malformed", answer).mapIndexed { index, body ->
                    val sample =
                        BenchmarkSample(
                            legacy.id,
                            legacy.label,
                            legacy.category,
                            BenchmarkOutcome.FAILED,
                            canonicalAnswer = body,
                            outputCharacters = body.length,
                            intelligenceAnswers =
                            mapOf(
                                "Math" to false
                            ),
                            answerTruncated = index == 2,
                            answerLength = body.length,
                            answerHash = benchmarkHash(body),
                            workload = legacy.workload
                        )
                    val run =
                        BenchmarkRun(
                            "legacy-$index",
                            "profile",
                            "Profile",
                            "CUSTOM",
                            "model",
                            "revision",
                            false,
                            BenchmarkMode.QUICK,
                            index.toLong(),
                            listOf(sample),
                            suiteVersion = 8,
                            caseManifest = listOf(legacy)
                        )
                    JsonObject(
                        benchmarkExportJson.encodeToJsonElement(BenchmarkRun.serializer(), run).jsonObject +
                            (
                                "futureRecord" to
                                    JsonObject(
                                        mapOf(
                                            "text" to JsonPrimitive("brackets ] [ { } and \"quotes\" 🧪"),
                                            "api_key" to JsonPrimitive("PRIVATE_LEGACY_KEY")
                                        )
                                    )
                                )
                    )
                }
            val raw = JsonArray(records).toString()
            preferences()
                .edit()
                .putString("history", raw)
                .putString("active_revisions", "{\"profile\":\"revision\"}")
                .commit()
            val store = BenchmarkStore(context)
            val inspection = database()
            try {
                val output = ByteArrayOutputStream()
                store.writeExport(output, true)
                val actual = benchmarkExportJson.parseToJsonElement(output.toString(Charsets.UTF_8)).jsonObject
                val expected = compatibility(records, null, JsonNull, mapOf("profile" to "revision"), true)
                assertEquals(expected - "exportedAt", actual - "exportedAt")
                assertEquals(
                    listOf("legacy-0", "legacy-1", "legacy-2"),
                    actual.getValue("runs").jsonArray.map {
                        it.jsonObject
                            .getValue("id")
                            .jsonPrimitive.content
                    }
                )
                assertTrue(store.history.value.isEmpty())
                assertTrue(inspection.dao().runs().isEmpty())
                assertFalse(inspection.dao().canonicalInitialized())
                assertEquals(raw, preferences().getString("history", null))
                assertFalse(preferences().contains("recovery_history"))
                assertFalse(output.toString(Charsets.UTF_8).contains("PRIVATE_LEGACY_KEY"))
            } finally {
                store.close()
                inspection.close()
            }
        }

    @Test fun enrollmentUpdatedBeforeOrdinaryLoadIsNotReplacedByDeferredPreferenceInitialization() =
        runBlocking {
            preferences().edit().putString("active_revisions", "{\"profile\":\"old\"}").commit()
            val guarded = ExportPreferenceContext(context, forbidHistory = false)
            val store = BenchmarkStore(guarded)
            try {
                assertEquals(0, guarded.enrollmentReads)
                store.updateEnrollment(mapOf("profile" to "new"))
                val output = ByteArrayOutputStream()
                store.writeExport(output)
                assertEquals(1, guarded.enrollmentReads)
                val actual = benchmarkExportJson.parseToJsonElement(output.toString(Charsets.UTF_8)).jsonObject
                assertEquals(
                    "new",
                    actual
                        .getValue("enrollment")
                        .jsonObject
                        .getValue("profile")
                        .jsonPrimitive.content
                )
                assertEquals("{\"profile\":\"new\"}", preferences().getString("active_revisions", null))
            } finally {
                store.close()
            }
        }

    private fun preferences(): SharedPreferences = context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE)

    private fun database(): BenchmarkDatabase =
        Room
            .databaseBuilder(
                context,
                BenchmarkDatabase::class.java,
                "benchmark_v2.db"
            ).addMigrations(BenchmarkDatabase.MIGRATION_1_2, BenchmarkDatabase.MIGRATION_2_3)
            .build()

    private fun compatibility(
        records: List<JsonElement>,
        snapshot: BenchmarkScoreSnapshot?,
        rawScore: JsonElement,
        enrollment: Map<String, String>,
        detailed: Boolean
    ): JsonObject {
        val runs = records.map { benchmarkExportJson.decodeFromJsonElement(BenchmarkRun.serializer(), it) }
        val backup =
            benchmarkExportJson
                .encodeToJsonElement(
                    BenchmarkBackupPayload.serializer(),
                    BenchmarkBackupPayload(
                        runs = runs,
                        scoreTable = snapshot,
                        scoreHistory = listOfNotNull(snapshot),
                        enrollment = enrollment
                    )
                ).jsonObject
        val original =
            JsonObject(
                backup +
                    mapOf(
                        "runs" to JsonArray(records),
                        "scoreTable" to rawScore,
                        "scoreHistory" to if (snapshot == null) JsonArray(emptyList()) else JsonArray(listOf(rawScore))
                    )
            )
        return benchmarkExportJson.parseToJsonElement(benchmarkExport(original.toString(), detailed)).jsonObject
    }
}

/** Constructor reads are forbidden; metadata must use the existing IO entry points. */
internal class ExportPreferenceContext(
    base: Context,
    private val forbidHistory: Boolean
) : ContextWrapper(base) {
    private val owner = Thread.currentThread()
    var enrollmentReads = 0
        private set

    override fun getSharedPreferences(
        name: String,
        mode: Int
    ): SharedPreferences {
        val original = super.getSharedPreferences(name, mode)
        if (name != "profile_benchmarks_v1") return original
        return object : SharedPreferences by original {
            override fun getString(
                key: String?,
                default: String?
            ): String? {
                if (key == "active_revisions") {
                    check(Thread.currentThread() != owner) { "Enrollment blocked the constructing thread." }
                    enrollmentReads++
                }
                check(key != "history" || !forbidHistory) { "A canonical export attempted to hydrate the obsolete whole-history mirror." }
                return original.getString(key, default)
            }
        }
    }
}

internal fun readColdExportPayload(
    database: BenchmarkDatabase,
    table: String,
    key: String,
    value: Any
): String =
    buildString {
        var position = 1
        while (true) {
            val chunk =
                database.openHelper.readableDatabase
                    .query(
                        "SELECT substr(payload, ?, 65536) FROM $table WHERE $key = ?",
                        arrayOf(position, value)
                    ).use { cursor ->
                        check(cursor.moveToFirst())
                        cursor.getString(0)
                    }
            append(chunk)
            val count = chunk.codePointCount(0, chunk.length)
            if (count < 65536) break
            position += count
        }
    }
