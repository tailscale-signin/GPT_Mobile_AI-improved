package dev.chungjungsoo.gptmobile.data.benchmark

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BenchmarkColdStreamingExportHeapTest {
    @Test fun fortyEightMiBOfColdCanonicalRoomHistoryStreamsWithoutHydrationOrMirrorParsing() =
        runBlocking {
            assumeTrue("The cold Room gate requires scripts/heap-stress.init.gradle", java.lang.Boolean.getBoolean("gptmobile.heapStress"))
            assertTrue("This gate must use the constrained heap worker", Runtime.getRuntime().maxMemory() <= 256L * 1024 * 1024)
            val context: Context = ApplicationProvider.getApplicationContext()
            context.deleteDatabase("benchmark_v2.db")
            val preferences = context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE)
            // The existing preferences backing String is baseline memory; canonical export must
            // never parse/copy this deliberately invalid stale mirror, even on its first call.
            preferences
                .edit()
                .clear()
                .putString(
                    "history",
                    "STALE_NOT_JSON_" + "x".repeat(1024 * 1024)
                ).putString("active_revisions", "{}")
                .commit()
            val database =
                Room
                    .databaseBuilder(
                        context,
                        BenchmarkDatabase::class.java,
                        "benchmark_v2.db"
                    ).addMigrations(BenchmarkDatabase.MIGRATION_1_2, BenchmarkDatabase.MIGRATION_2_3)
                    .build()
            val store = BenchmarkStore(ExportPreferenceContext(context, forbidHistory = true))
            try {
                repeat(RUNS) { seed(database, it) }
                database.dao().pointTo(BenchmarkCurrentRecord(generation = 0))
                val expectedRaw = MessageDigest.getInstance("SHA-256")
                repeat(2) {
                    repeat(RUNS) { run -> repeat(CASES) { case -> expectedRaw.update(body(run, case).toByteArray()) } }
                }
                val output = ColdEvidenceDigestStream()
                store.writeExport(output, true)
                assertTrue(output.bytes > 96L * 1024 * 1024)
                assertEquals(RUNS * CASES * 2, output.rawRecords)
                assertEquals(expectedRaw.digest().toList(), output.rawDigest.digest().toList())
                assertEquals(0, output.secretOccurrences)
                assertEquals('}'.code, output.lastByte)
                assertTrue(output.flushed)
                assertFalse(output.closed)
                assertFalse(output.collectingRaw)
                assertTrue(store.history.value.isEmpty())
                assertEquals(null, store.snapshot.value)
                assertFalse(preferences.contains("recovery_history"))
                println(
                    "Cold Room benchmark export: ${output.bytes} bytes, ${output.rawRecords} complete raw bodies, sha256=${output.documentDigest.digest().joinToString(
                        ""
                    ) { "%02x".format(it) }}"
                )
            } finally {
                store.close()
                database.close()
            }
        }

    private suspend fun seed(
        database: BenchmarkDatabase,
        runIndex: Int
    ) {
        val samples =
            (0 until CASES).map { case ->
                val answer = body(runIndex, case)
                BenchmarkSample(
                    "case-$case",
                    "Case",
                    "task",
                    BenchmarkOutcome.PASSED,
                    canonicalAnswer = answer,
                    outputCharacters = answer.length,
                    answerHash = benchmarkHash(answer),
                    requestDispatched = true,
                    terminalReceived = true
                )
            }
        val run =
            BenchmarkRun(
                "run-$runIndex",
                "profile",
                "Profile",
                "CUSTOM",
                "model",
                "config",
                false,
                BenchmarkMode.FULL,
                (
                    RUNS -
                        runIndex
                    ).toLong(),
                samples,
                suiteVersion = 9,
                plannedTrials = CASES
            )
        val original =
            JsonObject(
                benchmarkExportJson.encodeToJsonElement(BenchmarkRun.serializer(), run).jsonObject +
                    (
                        "futureEvidence" to
                            JsonObject(mapOf("retained" to JsonPrimitive(true), "password" to JsonPrimitive("PRIVATE_EXPORT_SENTINEL")))
                        )
            )
        val payload = original.toString()
        assertTrue(payload.toByteArray().size > 2 * 1024 * 1024)
        database.dao().insertRuns(listOf(BenchmarkRunRecord(run.id, run.startedAt, payload)))
        samples.forEachIndexed { index, sample ->
            val attempt =
                BenchmarkAttempt(
                    "${run.id}:$index",
                    "unjoined-batch",
                    runIndex * CASES + index,
                    run.id,
                    sample.testId,
                    index,
                    0,
                    90_000,
                    state = "TERMINAL",
                    sample = sample
                )
            database.dao().upsertAttempts(
                listOf(
                    BenchmarkAttemptRecord(
                        attempt.id,
                        attempt.batchId,
                        attempt.ordinal,
                        attempt.caseId,
                        benchmarkExportJson.encodeToString(BenchmarkAttempt.serializer(), attempt)
                    )
                )
            )
        }
    }

    private fun body(
        run: Int,
        case: Int
    ): String = "BEGIN_RAW_EVIDENCE_${run}_$case:" + "x".repeat(768 * 1024) + "_END_RAW_EVIDENCE"

    private companion object {
        const val RUNS = 8
        const val CASES = 8
    }

    private class ColdEvidenceDigestStream : OutputStream() {
        val documentDigest: MessageDigest = MessageDigest.getInstance("SHA-256")
        val rawDigest: MessageDigest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        var rawRecords = 0
        var secretOccurrences = 0
        var lastByte = -1
        var collectingRaw = false
        var closed = false
        var flushed = false
        private val begin = "BEGIN_RAW_EVIDENCE_".toByteArray()
        private val end = "_END_RAW_EVIDENCE".toByteArray()
        private val secret = "PRIVATE_EXPORT_SENTINEL".toByteArray()
        private var beginOffset = 0
        private var endOffset = 0
        private var secretOffset = 0

        override fun write(value: Int) {
            val byte = value.toByte()
            documentDigest.update(byte)
            observe(byte)
        }

        override fun write(
            buffer: ByteArray,
            offset: Int,
            length: Int
        ) {
            documentDigest.update(buffer, offset, length)
            for (index in offset until offset + length) observe(buffer[index])
        }

        private fun observe(value: Byte) {
            bytes++
            lastByte = value.toInt() and 255
            secretOffset =
                if (value == secret[secretOffset]) {
                    secretOffset + 1
                } else if (value == secret[0]) {
                    1
                } else {
                    0
                }
            if (secretOffset == secret.size) {
                secretOccurrences++
                secretOffset = 0
            }
            if (collectingRaw) {
                rawDigest.update(value)
                endOffset =
                    if (value == end[endOffset]) {
                        endOffset + 1
                    } else if (value == end[0]) {
                        1
                    } else {
                        0
                    }
                if (endOffset == end.size) {
                    rawRecords++
                    collectingRaw = false
                    endOffset = 0
                }
            } else {
                beginOffset =
                    if (value == begin[beginOffset]) {
                        beginOffset + 1
                    } else if (value == begin[0]) {
                        1
                    } else {
                        0
                    }
                if (beginOffset == begin.size) {
                    rawDigest.update(begin)
                    collectingRaw = true
                    beginOffset = 0
                }
            }
        }

        override fun flush() {
            flushed = true
        }

        override fun close() {
            closed = true
        }
    }
}
