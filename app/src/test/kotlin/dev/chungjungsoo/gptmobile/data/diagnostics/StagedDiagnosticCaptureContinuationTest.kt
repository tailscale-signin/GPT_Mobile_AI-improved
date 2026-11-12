package dev.chungjungsoo.gptmobile.data.diagnostics

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StagedDiagnosticCaptureContinuationTest {
    private val options = DiagnosticCaptureOptions(minimumHeadroomBytes = 0)

    @Test fun workspaceLargerThanSixteenMiBAutomaticallyStreamsWithinArchiveBudget() = temporary { directory ->
        runBlocking {
            val stages = listOf(cursorStage(280, 64 * 1024))
            val result = StagedDiagnosticCapture.capture(directory, "large-workspace", stages, options)
            assertTrue(result.complete)
            assertEquals(JsonPrimitive(1L), result.manifest["archivePart"])
            assertEquals(JsonPrimitive(false), result.manifest["externalPartsRequired"])
            ZipFile(result.file).use { zip -> assertTrue(zip.getEntry("workspace.jsonl").size > 16L * 1024 * 1024) }
            assertEquals((0 until 280).toSet(), rows(result.file).keys)
            assertEquals(result.manifest, StagedDiagnosticCapture.verify(result.file))
        }
    }

    @Test fun publishedPartialResumesWithNewTailAndDigestReferenceWithoutDuplicatePrefix() = temporary { directory ->
        runBlocking {
            val starts = mutableListOf<Int>()
            val stages = listOf(cursorStage(20, 64 * 1024, starts))
            val bounded = options.copy(maxStageBytes = 64 * 1024L, maxRecordBytes = 32 * 1024, maxArchiveBytes = 1024 * 1024L)
            val first = StagedDiagnosticCapture.capture(directory, "workspace-parts", stages, bounded)
            assertFalse(first.complete)
            val prefix = rows(first.file)
            assertTrue(prefix.isNotEmpty() && prefix.size < 20)
            val firstDigest = sha(first.file.readBytes())
            val second = StagedDiagnosticCapture.capture(directory, "workspace-parts", stages, bounded, first.sessionId)
            val tail = rows(second.file)
            assertTrue(second.complete)
            assertEquals(listOf(0, prefix.size), starts)
            assertTrue(prefix.keys.intersect(tail.keys).isEmpty())
            assertEquals((0 until 20).toSet(), prefix.keys + tail.keys)
            assertEquals(firstDigest, sha(first.file.readBytes()))
            val previous = second.manifest.getValue("previousArchive").jsonObject
            assertEquals(first.file.name, previous.getValue("fileName").jsonPrimitive.content)
            assertEquals(firstDigest, previous.getValue("sha256").jsonPrimitive.content)
            assertEquals(JsonPrimitive(true), second.manifest["externalPartsRequired"])
            assertEquals(second.manifest, StagedDiagnosticCapture.verify(second.file, bounded.maxArchiveBytes))
        }
    }

    @Test fun cancellationInsideFifteenChunksKeepsWholeRowsAndDurablyResumesSamePart() = temporary { directory ->
        runBlocking {
            var interrupted = false
            val starts = mutableListOf<Int>()
            val payload = buildJsonObject {
                put("index", 1)
                put("content", "x".repeat(14 * 1024 + 500))
            }.toString()
            val chunks = DiagnosticRecordChunks.encode(payload, 1024).toList()
            assertEquals(15, chunks.size)
            val stage = DiagnosticCaptureStage("workspace", "workspace.jsonl", resumableRecords = true) { sink ->
                val start = sink.resumeCursor?.get("nextRow")?.jsonPrimitive?.int ?: 0
                starts += start
                if (start == 0) {
                    sink.logicalRecord { sink.record("{\"index\":0,\"content\":\"retained\"}") }
                    sink.checkpoint(cursor(1))
                }
                sink.logicalRecord {
                    chunks.forEachIndexed { index, chunk ->
                        sink.record(chunk.toString())
                        if (index == 7 && !interrupted) {
                            interrupted = true
                            throw CancellationException("pause during chunk group")
                        }
                    }
                }
                sink.checkpoint(cursor(2))
            }
            assertTrue(runCatching { StagedDiagnosticCapture.capture(directory, "chunk-resume", listOf(stage), options) }.exceptionOrNull() is CancellationException)
            val session = requireNotNull(StagedDiagnosticCapture.findResumableSession(directory, "chunk-resume"))
            val retained = File(directory, "diagnostic-capture-checkpoints/$session/workspace.jsonl").readLines()
            assertEquals(1, retained.size)
            assertFalse(retained.single().contains("diagnostic_record_chunk"))
            val resumed = StagedDiagnosticCapture.capture(directory, "chunk-resume", listOf(stage), options, session)
            assertTrue(resumed.complete)
            assertEquals(listOf(0, 1), starts)
            assertEquals(setOf(0, 1), rows(resumed.file).keys)
            assertEquals(JsonPrimitive(1L), resumed.manifest["archivePart"])
            StagedDiagnosticCapture.verify(resumed.file)
        }
    }

    @Test fun completedDatabaseStageIsReferencedInsteadOfConsumingEveryNextPartAgain() = temporary { directory ->
        runBlocking {
            var completedCalls = 0
            val completed = DiagnosticCaptureStage("completed", "completed.jsonl", resumableRecords = true) { sink ->
                completedCalls++
                sink.logicalRecord { sink.record("{\"evidence\":\"" + "x".repeat(300 * 1024) + "\"}") }
                sink.checkpoint(cursor(1))
            }
            val stages = listOf(completed, cursorStage(12, 64 * 1024))
            val bounded = options.copy(maxArchiveBytes = 1024 * 1024L)
            val first = StagedDiagnosticCapture.capture(directory, "completed-reference", stages, bounded)
            assertFalse(first.complete)
            val second = StagedDiagnosticCapture.capture(directory, "completed-reference", stages, bounded, first.sessionId)
            assertTrue(second.complete)
            assertEquals(1, completedCalls)
            ZipFile(second.file).use { zip ->
                assertTrue(zip.getEntry("completed.jsonl").size < 2048)
                val reference = zip.getInputStream(zip.getEntry("completed.jsonl")).reader().use { Json.parseToJsonElement(it.readText()).jsonObject }
                assertEquals("diagnostic_stage_reference", reference.getValue("kind").jsonPrimitive.content)
                assertEquals(first.file.name, reference.getValue("source").jsonObject.getValue("archive").jsonObject.getValue("fileName").jsonPrimitive.content)
            }
            StagedDiagnosticCapture.verify(second.file, bounded.maxArchiveBytes)
        }
    }

    @Test fun completedLatestStateRefreshesAfterHandoffWhileHistoryContinuesFromItsCursor() = temporary { directory ->
        runBlocking {
            var revision = 1
            val latestStarts = mutableListOf<JsonObject?>()
            val historyStarts = mutableListOf<Int>()
            val latest = DiagnosticCaptureStage(
                "latest_state",
                "latest-state.jsonl",
                critical = true,
                lowMemorySafe = true,
                resumableRecords = true,
                refreshOnResume = true
            ) { sink ->
                latestStarts += sink.resumeCursor
                sink.logicalRecord { sink.record("{\"index\":0,\"revision\":$revision}") }
                sink.checkpoint(cursor(1))
            }
            val stages = listOf(latest, cursorStage(20, 64 * 1024, historyStarts))
            val bounded = options.copy(maxArchiveBytes = 1024 * 1024L, deferArchiveHandoff = true)
            val first = StagedDiagnosticCapture.capture(directory, "refreshed-latest", stages, bounded)
            assertFalse(first.complete)
            val prefix = rows(first.file)
            assertEquals(JsonPrimitive(1), rows(first.file, "latest-state.jsonl").getValue(0)["revision"])
            val firstDigest = sha(first.file.readBytes())
            val delivered = handoff(directory, first)

            revision = 2
            val second = StagedDiagnosticCapture.capture(directory, "refreshed-latest", stages, bounded, first.sessionId)
            assertTrue(second.complete)
            assertEquals(listOf(null, null), latestStarts)
            assertEquals(JsonPrimitive(2), rows(second.file, "latest-state.jsonl").getValue(0)["revision"])
            val receipt = (second.manifest.getValue("stages") as JsonArray).single { it.jsonObject["id"] == JsonPrimitive("latest_state") }.jsonObject
            assertEquals(JsonNull, receipt["sourceReference"])
            assertEquals(JsonNull, receipt["continuedFrom"])
            val tail = rows(second.file)
            assertEquals(listOf(0, prefix.size), historyStarts)
            assertTrue(prefix.keys.intersect(tail.keys).isEmpty())
            assertEquals((0 until 20).toSet(), prefix.keys + tail.keys)
            assertEquals(firstDigest, sha(delivered.readBytes()))
            assertEquals(firstDigest, second.manifest.getValue("previousArchive").jsonObject.getValue("sha256").jsonPrimitive.content)
            assertEquals(second.manifest, StagedDiagnosticCapture.verify(second.file, bounded.maxArchiveBytes))
        }
    }

    @Test fun partialLatestStateRestartsItsProjectionAfterHandoffWithoutAnOlderCursor() = temporary { directory ->
        runBlocking {
            var revision = 1
            val starts = mutableListOf<Int>()
            val latest = DiagnosticCaptureStage(
                "latest_state",
                "latest-state.jsonl",
                critical = true,
                lowMemorySafe = true,
                resumableRecords = true,
                refreshOnResume = true
            ) { sink ->
                val start = sink.resumeCursor?.get("nextRow")?.jsonPrimitive?.int ?: 0
                starts += start
                for (index in start until 2) {
                    sink.logicalRecord { sink.record("{\"index\":$index,\"revision\":$revision}") }
                    sink.checkpoint(cursor(index + 1))
                    if (revision == 1) throw IOException("source temporarily unavailable")
                }
            }
            val bounded = options.copy(deferArchiveHandoff = true)
            val first = StagedDiagnosticCapture.capture(directory, "partial-latest", listOf(latest), bounded)
            assertFalse(first.complete)
            assertEquals(setOf(0), rows(first.file, "latest-state.jsonl").keys)
            val firstDigest = sha(first.file.readBytes())
            val delivered = handoff(directory, first)

            revision = 2
            val second = StagedDiagnosticCapture.capture(directory, "partial-latest", listOf(latest), bounded, first.sessionId)
            assertTrue(second.complete)
            assertEquals(listOf(0, 0), starts)
            val refreshed = rows(second.file, "latest-state.jsonl")
            assertEquals(setOf(0, 1), refreshed.keys)
            assertTrue(refreshed.values.all { it["revision"] == JsonPrimitive(2) })
            val receipt = (second.manifest.getValue("stages") as JsonArray).single().jsonObject
            assertEquals(JsonNull, receipt["sourceReference"])
            assertEquals(JsonNull, receipt["continuedFrom"])
            assertEquals(firstDigest, sha(delivered.readBytes()))
            assertEquals(second.manifest, StagedDiagnosticCapture.verify(second.file))
        }
    }

    @Test fun reducedRetryCannotDiscardAnUnpublishedCommittedPrefix() = temporary { directory ->
        runBlocking {
            var interrupted = false
            val starts = mutableListOf<Int>()
            val stage = DiagnosticCaptureStage("workspace", "workspace.jsonl", resumableRecords = true) { sink ->
                val start = sink.resumeCursor?.get("nextRow")?.jsonPrimitive?.int ?: 0
                starts += start
                for (index in start until 20) {
                    sink.logicalRecord {
                        val row = buildJsonObject {
                            put("index", index)
                            put("content", "x".repeat(64 * 1024))
                        }.toString()
                        DiagnosticRecordChunks.encode(row, 12 * 1024).forEach { sink.record(it.toString()) }
                    }
                    sink.checkpoint(cursor(index + 1))
                    if (index == 13 && !interrupted) {
                        interrupted = true
                        throw CancellationException("retain large prefix")
                    }
                }
            }
            assertTrue(runCatching { StagedDiagnosticCapture.capture(directory, "reduced-prefix", listOf(stage), options) }.exceptionOrNull() is CancellationException)
            val session = requireNotNull(StagedDiagnosticCapture.findResumableSession(directory, "reduced-prefix"))
            val prefix = File(directory, "diagnostic-capture-checkpoints/$session/workspace.jsonl")
            val before = sha(prefix.readBytes())
            val failure = runCatching {
                StagedDiagnosticCapture.capture(directory, "reduced-prefix", listOf(stage), options.copy(maxArchiveBytes = 1024 * 1024L), session)
            }.exceptionOrNull()
            assertEquals("DIAGNOSTIC_RESUME_BUDGET_TOO_SMALL", failure?.message)
            assertEquals(before, sha(prefix.readBytes()))
            val resumed = StagedDiagnosticCapture.capture(directory, "reduced-prefix", listOf(stage), options, session)
            assertEquals(listOf(0, 14), starts)
            assertEquals((0 until 20).toSet(), rows(resumed.file).keys)
            assertTrue(resumed.complete)
        }
    }

    @Test fun sourceOmissionsRemainExplicitAfterFollowingArchivePartCompletes() = temporary { directory ->
        runBlocking {
            val producer = cursorStage(20, 64 * 1024)
            val stage = producer.copy(capture = { sink ->
                if (sink.resumeCursor == null) sink.omit("PROTECTED_PAYLOAD_UNAVAILABLE")
                producer.capture(sink)
            })
            val bounded = options.copy(maxArchiveBytes = 1024 * 1024L)
            val first = StagedDiagnosticCapture.capture(directory, "retained-omission", listOf(stage), bounded)
            val second = StagedDiagnosticCapture.capture(directory, "retained-omission", listOf(stage), bounded, first.sessionId)
            assertFalse(second.complete)
            val receipt = (second.manifest.getValue("stages") as kotlinx.serialization.json.JsonArray).single().jsonObject
            assertEquals(JsonPrimitive(true), receipt["collectionCompleted"])
            assertEquals(JsonPrimitive(1L), receipt.getValue("omissions").jsonObject["PROTECTED_PAYLOAD_UNAVAILABLE"])
            assertEquals(JsonPrimitive(false), second.manifest["continuationAvailable"])
            assertEquals((0 until 20).toSet(), rows(first.file).keys + rows(second.file).keys)
        }
    }

    @Test fun damagedPublishedCursorNeverSilentlyRestartsHistory() = temporary { directory ->
        runBlocking {
            val starts = mutableListOf<Int>()
            val stages = listOf(cursorStage(20, 64 * 1024, starts))
            val bounded = options.copy(maxArchiveBytes = 1024 * 1024L)
            val first = StagedDiagnosticCapture.capture(directory, "damaged-cursor", stages, bounded)
            File(directory, "diagnostic-capture-checkpoints/${first.sessionId}/workspace.receipt.json").writeText("damaged")
            val failure = runCatching { StagedDiagnosticCapture.capture(directory, "damaged-cursor", stages, bounded, first.sessionId) }.exceptionOrNull()
            assertEquals("DIAGNOSTIC_CONTINUATION_CHECKPOINT_INVALID", failure?.message)
            assertEquals(listOf(0), starts)
            assertTrue(first.file.isFile)
        }
    }

    @Test fun undeliveredArchiveMustBeHandedOffBeforeAResumeCanAdvanceItsCursor() = temporary { directory ->
        runBlocking {
            val starts = mutableListOf<Int>()
            val stages = listOf(cursorStage(20, 64 * 1024, starts))
            val bounded = options.copy(maxArchiveBytes = 1024 * 1024L, deferArchiveHandoff = true)
            val first = StagedDiagnosticCapture.capture(directory, "pending-delivery", stages, bounded)
            val prefix = rows(first.file).keys
            val firstDigest = sha(first.file.readBytes())
            val failure = runCatching { StagedDiagnosticCapture.capture(directory, "pending-delivery", stages, bounded, first.sessionId) }.exceptionOrNull()
            assertEquals("DIAGNOSTIC_ARCHIVE_HANDOFF_PENDING", failure?.message)
            val pending = requireNotNull(StagedDiagnosticCapture.pendingArchive(directory, "pending-delivery", first.sessionId))
            assertEquals(first.manifest, pending.manifest)
            assertEquals(firstDigest, sha(pending.file.readBytes()))
            assertEquals(listOf(0), starts)
            val delivered = File(directory, "delivered").apply { mkdir() }
            first.file.copyTo(File(delivered, first.file.name))
            StagedDiagnosticCapture.acknowledgeArchiveHandoff(directory, pending)
            assertNull(StagedDiagnosticCapture.pendingArchive(directory, "pending-delivery", first.sessionId))
            val second = StagedDiagnosticCapture.capture(directory, "pending-delivery", stages, bounded, first.sessionId)
            val tail = rows(second.file).keys
            assertTrue(prefix.intersect(tail).isEmpty())
            assertEquals((0 until 20).toSet(), prefix + tail)
            assertEquals(firstDigest, second.manifest.getValue("previousArchive").jsonObject.getValue("sha256").jsonPrimitive.content)
        }
    }

    @Test fun completeArchiveStillOffersResumeUntilItsDestinationHandoffSucceeds() = temporary { directory ->
        runBlocking {
            val result = StagedDiagnosticCapture.capture(directory, "complete-pending", listOf(cursorStage(1, 128)), options.copy(deferArchiveHandoff = true))
            assertTrue(result.complete)
            assertFalse(result.retryRecommended)
            assertEquals(result.sessionId, StagedDiagnosticCapture.findResumableSession(directory, "complete-pending"))
            val delivered = File(directory, "delivered").apply { mkdir() }
            result.file.copyTo(File(delivered, result.file.name))
            StagedDiagnosticCapture.acknowledgeArchiveHandoff(directory, result)
            assertNull(StagedDiagnosticCapture.findResumableSession(directory, "complete-pending"))
        }
    }

    private fun cursorStage(count: Int, characters: Int, starts: MutableList<Int> = mutableListOf()) =
        DiagnosticCaptureStage("workspace", "workspace.jsonl", resumableRecords = true) { sink ->
            val start = sink.resumeCursor?.get("nextRow")?.jsonPrimitive?.int ?: 0
            starts += start
            for (index in start until count) {
                sink.checkBudget()
                sink.logicalRecord {
                    val value = buildJsonObject {
                        put("index", index)
                        put("content", "x".repeat(characters))
                    }.toString()
                    DiagnosticRecordChunks.encode(value, 12 * 1024).forEach { sink.record(it.toString()) }
                }
                sink.checkpoint(cursor(index + 1))
            }
        }

    private fun cursor(next: Int) = buildJsonObject { put("nextRow", next) }

    /** Verifies every chunk set before reconstructing its logical JSON row. */
    private fun rows(file: File, fileName: String = "workspace.jsonl"): Map<Int, JsonObject> = ZipFile(file).use { zip ->
        val result = linkedMapOf<Int, JsonObject>()
        val pending = mutableListOf<JsonObject>()
        fun retain(row: JsonObject) {
            val index = row.getValue("index").jsonPrimitive.int
            check(result.put(index, row) == null) { "Duplicated logical row" }
        }
        zip.getInputStream(zip.getEntry(fileName)).bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val row = Json.parseToJsonElement(line).jsonObject
                if (row["kind"]?.jsonPrimitive?.content != "diagnostic_record_chunk") {
                    retain(row)
                } else {
                    assertEquals(pending.size, row.getValue("chunkIndex").jsonPrimitive.int)
                    assertEquals(DiagnosticRecordChunks.sha256(row.getValue("content").jsonPrimitive.content), row.getValue("chunkSha256").jsonPrimitive.content)
                    pending += row
                    if (pending.size == row.getValue("chunkCount").jsonPrimitive.int) {
                        val value = pending.joinToString("") { it.getValue("content").jsonPrimitive.content }
                        assertEquals(DiagnosticRecordChunks.sha256(value), row.getValue("recordSha256").jsonPrimitive.content)
                        retain(Json.parseToJsonElement(value).jsonObject)
                        pending.clear()
                    }
                }
            }
        }
        assertTrue("An archive must never end with a partial logical record", pending.isEmpty())
        result
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun handoff(directory: File, result: DiagnosticCaptureResult): File {
        val delivered = File(directory, "delivered").apply { mkdir() }
        val archive = result.file.copyTo(File(delivered, result.file.name))
        StagedDiagnosticCapture.acknowledgeArchiveHandoff(directory, result)
        return archive
    }

    private fun temporary(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("diagnostic-continuation").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
