package dev.chungjungsoo.gptmobile.data.diagnostics

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturedJournalTimelineTest {
    @Test fun timelineUsesTheCapturedBoundaryOnceEvenWhenTheLiveJournalAdvances() = temporary { directory ->
        runBlocking {
            var liveSequence = 1
            var journalReads = 0
            lateinit var session: File
            val result = StagedDiagnosticCapture.capture(
                directory,
                "frozen-journal",
                listOf(
                    journal { sink ->
                        journalReads++
                        sink.record(event(liveSequence).toString())
                        sink.note(coverage(liveSequence).toString())
                        liveSequence++
                    },
                    timeline { session }
                ),
                options { session = it }
            )
            assertTrue(result.complete)
            assertEquals(1, journalReads)
            assertEquals(2, liveSequence)
            val rows = rows(result, "timeline.jsonl")
            val projected = rows.filter { it.text("kind") == "lifecycle_observation" }
            assertEquals(listOf("event-1"), projected.map { it.text("sourceEventId") })
            assertEquals(listOf("invocation-1"), projected.map { it.text("invocationId") })
            val source = rows.single { it.text("kind") == "timeline_journal_source" }
            assertEquals(receipt(result, "run_journal")["sha256"], source["sourceSha256"])
            assertEquals(JsonPrimitive(2L), source["sourceRecords"])
            assertEquals(JsonPrimitive(1L), source["projectedEvents"])
            assertEquals(JsonPrimitive(false), source["liveJournalReopened"])
            assertEquals(JsonPrimitive(1), rows.single { it.text("kind") == "timeline_journal_coverage" }["eventCount"])
            assertEquals(result.manifest, StagedDiagnosticCapture.verify(result.file))
        }
    }

    @Test fun chunkedJournalMetadataIsReassembledAndPrivatePayloadIsExcludedFromTimeline() = temporary { directory ->
        runBlocking {
            lateinit var session: File
            val large = event(7) + ("providerReceipt" to JsonPrimitive("not-for-the-timeline-漢字".repeat(300)))
            val result = StagedDiagnosticCapture.capture(
                directory,
                "chunked-journal",
                listOf(journal { sink -> DiagnosticRecordChunks.encode(JsonObject(large).toString(), 256).forEach { sink.record(it.toString()) } }, timeline { session }),
                options { session = it }
            )
            assertTrue(result.complete)
            val text = rows(result, "timeline.jsonl").joinToString()
            assertTrue(text.contains("event-7"))
            assertFalse(text.contains("not-for-the-timeline"))
            assertEquals(result.manifest, StagedDiagnosticCapture.verify(result.file))
        }
    }

    @Test fun sourceOmissionsRemainPartialAndKeepTheirExactCounts() = temporary { directory ->
        runBlocking {
            lateinit var session: File
            val result = StagedDiagnosticCapture.capture(
                directory,
                "partial-journal",
                listOf(
                    journal { sink ->
                        sink.record(event(3).toString())
                        sink.omit("RUN_JOURNAL_COVERAGE_GAPS", 2)
                    },
                    timeline { session }
                ),
                options { session = it }
            )
            assertFalse(result.complete)
            assertEquals("PARTIAL", receipt(result, "timeline").text("status"))
            val source = rows(result, "timeline.jsonl").last()
            assertEquals(JsonPrimitive(2L), source["sourceOmissions"]!!.jsonObject["RUN_JOURNAL_COVERAGE_GAPS"])
            assertEquals(JsonPrimitive(false), source["retainedSourceComplete"])
            assertEquals(JsonPrimitive(1L), receipt(result, "timeline")["omissions"]!!.jsonObject["TIMELINE_JOURNAL_COVERAGE_INCOMPLETE"])
            StagedDiagnosticCapture.verify(result.file)
        }
    }

    @Test fun brokenChunkRollsBackAllDerivedJournalEventsAndRetainsEarlierDatabaseEvidence() = temporary { directory ->
        runBlocking {
            lateinit var session: File
            val chunks = DiagnosticRecordChunks.encode(event(2).toString(), 256).toList()
            val damaged = JsonObject(chunks.first() + ("chunkSha256" to JsonPrimitive("0".repeat(64))))
            val result = StagedDiagnosticCapture.capture(
                directory,
                "damaged-chunk",
                listOf(
                    journal { sink ->
                        sink.record(event(1).toString())
                        sink.record(damaged.toString())
                        chunks.drop(1).forEach { sink.record(it.toString()) }
                    },
                    timeline { session }
                ),
                options { session = it }
            )
            val rows = rows(result, "timeline.jsonl")
            assertEquals(listOf("database_evidence"), rows.map { it.text("kind") })
            assertEquals(JsonPrimitive(1L), receipt(result, "timeline")["omissions"]!!.jsonObject["TIMELINE_JOURNAL_CHUNK_INVALID"])
            StagedDiagnosticCapture.verify(result.file)
        }
    }

    @Test fun partialChunkGroupIsAnExplicitIncompleteTimelineSource() = temporary { directory ->
        runBlocking {
            lateinit var session: File
            val record = JsonObject(event(2) + ("extraMetadata" to JsonPrimitive("x".repeat(2000))))
            val result = StagedDiagnosticCapture.capture(
                directory,
                "incomplete-chunk",
                listOf(journal { it.record(DiagnosticRecordChunks.encode(record.toString(), 256).first().toString()) }, timeline { session }),
                options { session = it }
            )
            assertEquals(JsonPrimitive(1L), receipt(result, "timeline")["omissions"]!!.jsonObject["TIMELINE_JOURNAL_INCOMPLETE_RECORD"])
            assertFalse(rows(result, "timeline.jsonl").any { it.text("kind") == "lifecycle_observation" })
        }
    }

    @Test fun sourceIntegrityAndCancellationAreCheckedWithoutReopeningTheLiveStore() = temporary { directory ->
        runBlocking {
            lateinit var session: File
            StagedDiagnosticCapture.capture(directory, "changed-source", listOf(journal { it.record(event(1).toString()) }), options { session = it })
            val source = File(session, "run-journal.jsonl")
            source.writeText(source.readText().replace("event-1", "event-2"))
            val failure = runCatching { CapturedJournalTimeline.stream(session, {}, { _, _ -> }, {}) }.exceptionOrNull()
            assertTrue(failure is CapturedJournalTimelineFailure)
            assertEquals("TIMELINE_JOURNAL_CHECKSUM_MISMATCH", (failure as CapturedJournalTimelineFailure).code)
            val cancelled = CancellationException("export cancelled")
            val actual = runCatching { CapturedJournalTimeline.stream(session, { throw cancelled }, { _, _ -> }, {}) }.exceptionOrNull()
            assertTrue(actual === cancelled)
        }
    }

    @Test fun resumeRefreshesTheDerivedTimelineIfAnIncompleteJournalIsRecaptured() = temporary { directory ->
        runBlocking {
            lateinit var session: File
            var complete = false
            val stages = listOf(
                journal { sink ->
                    sink.record(event(1).toString())
                    if (complete) sink.record(event(2).toString()) else sink.omit("RUN_JOURNAL_COVERAGE_GAPS")
                },
                timeline { session }
            )
            val first = StagedDiagnosticCapture.capture(directory, "resume-journal", stages, options { session = it })
            complete = true
            val second = StagedDiagnosticCapture.capture(directory, "resume-journal", stages, options { session = it }, first.sessionId)
            assertTrue(second.complete)
            assertEquals(listOf("event-1", "event-2"), rows(second, "timeline.jsonl").filter { it.text("kind") == "lifecycle_observation" }.map { it.text("sourceEventId") })
            StagedDiagnosticCapture.verify(second.file)
        }
    }

    private fun event(sequence: Int): JsonObject = buildJsonObject {
        put("eventId", "event-$sequence")
        put("runId", "run")
        put("streamId", "process:run")
        put("sequence", sequence)
        put("invocationId", "invocation-$sequence")
        put("capturedAtMillis", 1_000L + sequence)
        put("kind", "TOOL_RESULT")
    }

    private fun coverage(events: Int): JsonObject = buildJsonObject {
        put("kind", "journal_coverage")
        put("runId", "run")
        put("eventCount", events)
        put("missingSequences", 0)
    }

    private fun journal(capture: suspend (DiagnosticStageSink) -> Unit) = DiagnosticCaptureStage("run_journal", "run-journal.jsonl", capture = capture)

    private fun timeline(session: () -> File) = DiagnosticCaptureStage("timeline", "timeline.jsonl", refreshOnResume = true) { sink ->
        sink.record(buildJsonObject { put("kind", "database_evidence") }.toString())
        try {
            sink.logicalRecord {
                val coverage = CapturedJournalTimeline.stream(session(), sink::checkBudget, { row, evidence -> sink.record(row.toString(), evidence) }, { sink.omit(it) })
                sink.note(coverage.toString())
            }
        } catch (failure: CapturedJournalTimelineFailure) {
            sink.omit(failure.code)
        }
    }

    private fun options(session: (File) -> Unit) = DiagnosticCaptureOptions(minimumHeadroomBytes = 0, onSessionReady = session)
    private fun receipt(result: DiagnosticCaptureResult, id: String): JsonObject = (result.manifest["stages"] as JsonArray).map { it.jsonObject }.single { it.text("id") == id }
    private fun rows(result: DiagnosticCaptureResult, file: String): List<JsonObject> = ZipFile(result.file).use { zip -> zip.getInputStream(zip.getEntry(file)).bufferedReader().useLines { lines -> lines.filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }.toList() } }
    private fun JsonObject.text(key: String): String = get(key)?.jsonPrimitive?.content.orEmpty()
    private fun temporary(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("captured-journal-timeline").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
