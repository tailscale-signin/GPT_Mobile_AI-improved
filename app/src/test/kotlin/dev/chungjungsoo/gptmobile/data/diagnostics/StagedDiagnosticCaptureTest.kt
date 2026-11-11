package dev.chungjungsoo.gptmobile.data.diagnostics

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StagedDiagnosticCaptureTest {
    private val available = DiagnosticCaptureOptions(minimumHeadroomBytes = 0)

    @Test fun missingUnrecordedLogsRequireNewCaptureInsteadOfImpossibleResume() = temporary { directory ->
        runBlocking {
            val result = StagedDiagnosticCapture.capture(directory, "logs-disabled", listOf(stage("logs") { it.omit("TRACKING_DISABLED_NO_RETAINED_LOGS") }), available)
            assertEquals(JsonPrimitive(false), result.manifest["continuationAvailable"])
            assertEquals(JsonPrimitive(false), result.manifest["resumable"])
            assertEquals(JsonPrimitive("NEW_CAPTURE_REQUIRED"), result.manifest["recoveryAction"])
            assertTrue(result.manifest["recovery"].toString().contains("reproduce"))
        }
    }

    @Test fun stageTraceClosesOnFailureAndIsNotReopenedForReusedEvidence() = temporary { directory ->
        runBlocking {
            val observations = mutableListOf<String>()
            val options = available.copy(traceStage = { stage ->
                observations += "start:$stage"
                val end: () -> Unit = { observations += "end:$stage" }
                end
            })
            var retry = false
            val stages = listOf(
                stage("retained") { it.record("valid") },
                stage("failed") {
                    if (!retry) throw IOException("interrupted")
                    it.record("recovered")
                }
            )
            val first = StagedDiagnosticCapture.capture(directory, "trace", stages, options)
            assertEquals(JsonPrimitive(false), first.manifest["continuationAvailable"])
            assertEquals(JsonPrimitive(true), first.manifest["resumable"])
            assertEquals(JsonPrimitive("RESUME_UNFINISHED_COLLECTION"), first.manifest["recoveryAction"])
            assertEquals(listOf("start:retained", "end:retained", "start:failed", "end:failed"), observations)
            observations.clear()
            retry = true
            StagedDiagnosticCapture.capture(directory, "trace", stages, options, first.sessionId)
            assertEquals(listOf("start:failed", "end:failed"), observations)
        }
    }

    @Test fun availabilityAndMissingEvidenceSeparateEmptyFromFailedCapture() = temporary { directory ->
        runBlocking {
            val result = StagedDiagnosticCapture.capture(
                directory,
                "availability",
                listOf(
                    stage("empty") { it.note("metadata") },
                    stage("failed") { throw IOException("private source") },
                    stage("partial") {
                        it.record("retained")
                        it.omit("EVIDENCE_MISSING")
                    },
                    stage("complete") { it.record("retained") }
                ),
                available
            )
            assertEquals("METADATA_ONLY", receipt(result, "empty").text("availability"))
            assertEquals("UNAVAILABLE", receipt(result, "failed").text("availability"))
            assertEquals("PARTIAL", receipt(result, "partial").text("availability"))
            assertEquals("AVAILABLE", receipt(result, "complete").text("availability"))
            assertEquals(JsonPrimitive(50), result.manifest["captureCompletionPercent"])
            val missing = result.manifest["missingEvidence"] as JsonArray
            assertEquals(setOf("failed", "partial"), missing.map { it.jsonObject.text("stage") }.toSet())
            assertFalse(result.manifest.toString().contains("private source"))
        }
    }

    @Test fun configurationAndAbsentRecordsHaveDifferentManifestLabels() = temporary { directory ->
        runBlocking {
            val result = StagedDiagnosticCapture.capture(
                directory,
                "configuration",
                listOf(
                    DiagnosticCaptureStage("settings", "settings.jsonl", content = DiagnosticStageContent.CONFIGURATION) { it.note("configuration") },
                    stage("empty") { }
                ),
                available
            )
            assertEquals("CONFIGURATION_ONLY", receipt(result, "settings").text("availability"))
            assertEquals(JsonPrimitive(1L), receipt(result, "settings")["metadataRecords"])
            assertEquals(JsonPrimitive(0L), receipt(result, "settings")["evidenceRecords"])
            assertEquals("EMPTY_RETAINED", receipt(result, "empty").text("availability"))
            assertEquals(result.manifest, StagedDiagnosticCapture.verify(result.file))
        }
    }

    @Test fun failedStageFileOpenStillPublishesOtherSections() = temporary { directory ->
        runBlocking {
            val result = StagedDiagnosticCapture.capture(
                directory,
                "damaged-working-file",
                listOf(
                    stage("logs", critical = true) { it.record("retained-critical-failure") },
                    stage("conversation") { error("The blocked file must fail before source capture") },
                    stage("profiles") { it.record("retained-profile") }
                ),
                available.copy(onSessionReady = { session ->
                    check(File(session, "conversation.jsonl.partial").mkdir())
                })
            )
            assertFalse(result.complete)
            assertEquals(2L, result.usefulRecords)
            assertEquals("FAILED", receipt(result, "conversation").text("status"))
            assertEquals("SOURCE_IO_FAILURE", receipt(result, "conversation").text("omissionReason"))
            ZipFile(result.file).use { zip ->
                assertEquals(0L, zip.getEntry("conversation.jsonl").size)
                assertEquals("retained-critical-failure\n", zip.getInputStream(zip.getEntry("logs.jsonl")).reader().readText())
                assertEquals("retained-profile\n", zip.getInputStream(zip.getEntry("profiles.jsonl")).reader().readText())
            }
            assertEquals(result.manifest, StagedDiagnosticCapture.verify(result.file))
        }
    }

    @Test fun failedConversationRetainsCriticalEvidenceAndIndependentStages() = temporary { directory ->
        runBlocking {
            val result = StagedDiagnosticCapture.capture(
                directory,
                "chat-6-revision-1",
                listOf(
                    stage("conversation") {
                        it.record("message-one")
                        throw IOException("private-error-canary")
                    },
                    stage("profiles") { it.record("redacted-profile") },
                    stage("logs", critical = true) { it.record("recorded-tool-failure") }
                ),
                available
            )
            assertFalse(result.complete)
            assertTrue(result.retryRecommended)
            assertEquals(3L, result.usefulRecords)
            assertEquals("PARTIAL", receipt(result, "conversation").text("status"))
            assertEquals("SOURCE_IO_FAILURE", receipt(result, "conversation").text("omissionReason"))
            assertFalse(result.manifest.toString().contains("private-error-canary"))
            ZipFile(result.file).use { zip ->
                assertEquals("logs.jsonl", zip.entries().nextElement().name)
                assertEquals("message-one\n", zip.getInputStream(zip.getEntry("conversation.jsonl")).reader().readText())
            }
            assertEquals(result.manifest, StagedDiagnosticCapture.verify(result.file))
        }
    }

    @Test fun emptyLogsRemainEmptyEvenWhenAnExplanationWasWritten() = temporary { directory ->
        runBlocking {
            val result = StagedDiagnosticCapture.capture(
                directory,
                "empty",
                listOf(stage("logs", critical = true) { it.note("capture disabled; no retained log records") }),
                available
            )
            assertEquals(0L, result.usefulRecords)
            assertTrue(result.retryRecommended)
            assertEquals("EMPTY", receipt(result, "logs").text("status"))
            assertEquals("0", receipt(result, "logs").text("evidenceRecords"))
            assertEquals("1", receipt(result, "logs").text("records"))
            assertEquals(JsonPrimitive(false), result.manifest["criticalEvidenceAvailable"])
            assertEquals(JsonPrimitive(false), result.manifest["capturedFully"])
            assertEquals(result.sessionId, StagedDiagnosticCapture.findResumableSession(directory, "empty"))
            StagedDiagnosticCapture.verify(result.file)
        }
    }

    @Test fun emptyStageIsRetriedInsteadOfPermanentlyReusingEmptyEvidence() = temporary { directory ->
        runBlocking {
            var availableLog = false
            val stages = listOf(stage("logs", critical = true) { if (availableLog) it.record("newly-available-log") })
            val initial = StagedDiagnosticCapture.capture(directory, "same-source", stages, available)
            availableLog = true
            val retry = StagedDiagnosticCapture.capture(directory, "same-source", stages, available, initial.sessionId)
            assertEquals(1L, retry.usefulRecords)
            assertFalse(retry.retryRecommended)
            assertNotEquals(initial.file, retry.file)
        }
    }

    @Test fun omissionIsExplicitAndDoesNotStopLaterStages() = temporary { directory ->
        runBlocking {
            val result = StagedDiagnosticCapture.capture(
                directory,
                "omissions",
                listOf(
                    stage("tools") {
                        it.record("retained-receipt")
                        it.omit("OVERSIZED_SOURCE_ROW", 2)
                    },
                    stage("research") { it.record("retained-research") }
                ),
                available
            )
            assertFalse(result.complete)
            assertEquals("PARTIAL", receipt(result, "tools").text("status"))
            assertEquals(JsonPrimitive(2L), receipt(result, "tools")["omissions"]!!.jsonObject["OVERSIZED_SOURCE_ROW"])
            assertEquals("COMPLETED", receipt(result, "research").text("status"))
        }
    }

    @Test fun largeHistoryIsConsumedOneRecordAtATime() = temporary { directory ->
        runBlocking {
            var produced = 0
            val result = StagedDiagnosticCapture.capture(
                directory,
                "large",
                listOf(
                    stage("conversation") { sink ->
                        repeat(40_000) { index ->
                            sink.record("$index:" + "bounded record ".repeat(10))
                            produced++
                        }
                    }
                ),
                available
            )
            assertTrue(result.complete)
            assertEquals(40_000, produced)
            assertEquals(40_000L, result.usefulRecords)
            assertEquals("40000", receipt(result, "conversation").text("records"))
            StagedDiagnosticCapture.verify(result.file)
        }
    }

    @Test fun recordLimitRetainsCompletePrecedingRecords() = temporary { directory ->
        runBlocking {
            val result = StagedDiagnosticCapture.capture(
                directory,
                "record-limit",
                listOf(
                    stage("conversation") {
                        it.record("first")
                        it.record("x".repeat(300))
                    },
                    stage("profiles") { it.record("last") }
                ),
                available.copy(maxRecordBytes = 128)
            )
            assertEquals("RECORD_BYTE_LIMIT", receipt(result, "conversation").text("omissionReason"))
            assertEquals("1", receipt(result, "conversation").text("records"))
            assertEquals(2L, result.usefulRecords)
            StagedDiagnosticCapture.verify(result.file)
            assertFalse(directory.walk().any { it.isFile && it.name.endsWith(".partial") })
        }
    }

    @Test fun heapPressureDefersExpensiveStageAndStillCapturesSmallStages() = temporary { directory ->
        runBlocking {
            val constrained = DiagnosticCaptureOptions(memorySampler = { memory(headroom = 2L * 1024 * 1024) })
            var expensiveCalled = false
            val result = StagedDiagnosticCapture.capture(
                directory,
                "low-memory",
                listOf(
                    stage("logs", critical = true) { it.record("crash") },
                    stage("conversation") {
                        expensiveCalled = true
                        it.record("large")
                    },
                    stage("profiles", lowMemorySafe = true) { it.record("profile") }
                ),
                constrained
            )
            assertFalse(expensiveCalled)
            assertEquals("JAVA_HEAP_PRESSURE", receipt(result, "conversation").text("omissionReason"))
            assertEquals(2L, result.usefulRecords)
            assertTrue(result.retryRecommended)
        }
    }

    @Test fun allocationFailurePreservesEvidenceAndDoesNotSuppressNextStage() = temporary { directory ->
        runBlocking {
            val result = StagedDiagnosticCapture.capture(
                directory,
                "allocation-failure",
                listOf(
                    stage("tools") {
                        it.record("completed receipt")
                        throw OutOfMemoryError("sensitive-canary")
                    },
                    stage("profiles", lowMemorySafe = true) { it.record("profile") }
                ),
                available
            )
            assertEquals("PARTIAL", receipt(result, "tools").text("status"))
            assertEquals("JAVA_HEAP_PRESSURE", receipt(result, "tools").text("omissionReason"))
            assertEquals("COMPLETED", receipt(result, "profiles").text("status"))
            assertFalse(result.manifest.toString().contains("sensitive-canary"))
        }
    }

    @Test fun cancellationLeavesCheckpointAndResumeReusesVerifiedCompletedStage() = temporary { directory ->
        runBlocking {
            var firstCalls = 0
            var interrupted = true
            val stages = listOf(
                stage("logs", critical = true) {
                    firstCalls++
                    it.record("critical")
                },
                stage("conversation") {
                    if (interrupted) throw CancellationException("backgrounded")
                    it.record("resumed-message")
                }
            )
            val failure = runCatching { StagedDiagnosticCapture.capture(directory, "frozen-chat", stages, available) }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertTrue(directory.listFiles().orEmpty().none { it.extension == "zip" })
            val session = StagedDiagnosticCapture.findResumableSession(directory, "frozen-chat")
            assertNotNull(session)
            interrupted = false
            val resumed = StagedDiagnosticCapture.capture(directory, "frozen-chat", stages, available, session)
            assertTrue(resumed.complete)
            assertEquals(1, firstCalls)
            assertEquals(JsonPrimitive(true), receipt(resumed, "logs")["reusedCheckpoint"])
            assertEquals(null, StagedDiagnosticCapture.findResumableSession(directory, "frozen-chat"))
        }
    }

    @Test fun historyByteLimitCannotStarveLaterSmallSections() = temporary { directory ->
        runBlocking {
            val result = StagedDiagnosticCapture.capture(
                directory,
                "source",
                listOf(
                    DiagnosticCaptureStage("history", "history.jsonl") { sink -> repeat(2048) { sink.record("x".repeat(1024)) } },
                    DiagnosticCaptureStage("profiles", "profiles.jsonl", lowMemorySafe = true) { it.record("profile") },
                    DiagnosticCaptureStage("logs", "critical.log", critical = true) { it.record("critical-event") }
                ),
                available.copy(maxRecordBytes = 4096, maxStageBytes = 1024L * 1024, maxArchiveBytes = 1024L * 1024)
            )
            assertFalse(result.complete)
            assertEquals("PARTIAL", receipt(result, "history").text("status"))
            assertEquals("COMPLETED", receipt(result, "profiles").text("status"))
            ZipFile(result.file).use { zip -> assertEquals("critical.log", zip.entries().nextElement().name) }
            StagedDiagnosticCapture.verify(result.file, 1024L * 1024)
        }
    }

    @Test fun resumeRejectsChangedSourceAndPathTraversal() = temporary { directory ->
        runBlocking {
            val stages = listOf(stage("logs", critical = true) { it.record("log") })
            val result = StagedDiagnosticCapture.capture(directory, "chat-1", stages, available)
            assertTrue(runCatching { StagedDiagnosticCapture.capture(directory, "chat-2", stages, available, result.sessionId) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { StagedDiagnosticCapture.capture(directory, "chat-1", stages, available, "../outside") }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun corruptedCompletedCheckpointIsRecaptured() = temporary { directory ->
        runBlocking {
            var calls = 0
            val stages = listOf(
                stage("logs", critical = true) {
                    calls++
                    it.record("authentic-$calls")
                }
            )
            val first = StagedDiagnosticCapture.capture(directory, "same", stages, available)
            File(directory, "diagnostic-capture-checkpoints/${first.sessionId}/logs.jsonl").writeText("corrupt\n")
            val resumed = StagedDiagnosticCapture.capture(directory, "same", stages, available, first.sessionId)
            assertEquals(2, calls)
            assertEquals(JsonPrimitive(false), receipt(resumed, "logs")["reusedCheckpoint"])
            StagedDiagnosticCapture.verify(resumed.file)
        }
    }

    @Test fun concurrentBackgroundExportFailsBusyAndGateIsReleased() = temporary { directory ->
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val active = async(Dispatchers.IO) {
                StagedDiagnosticCapture.capture(
                    directory,
                    "background",
                    listOf(
                        stage("logs") {
                            entered.complete(Unit)
                            release.await()
                            it.record("background-record")
                        }
                    ),
                    available
                )
            }
            entered.await()
            val rejected = runCatching { StagedDiagnosticCapture.capture(directory, "second", listOf(stage("logs") { it.record("second") }), available) }.exceptionOrNull()
            assertTrue(rejected is DiagnosticExportBusy)
            release.complete(Unit)
            assertTrue(active.await().complete)
            assertTrue(StagedDiagnosticCapture.capture(directory, "third", listOf(stage("logs") { it.record("third") }), available).complete)
        }
    }

    @Test fun repeatedExportsKeepDistinctArchivesAndBoundCheckpointCount() = temporary { directory ->
        runBlocking {
            val outputs = hashSetOf<File>()
            repeat(8) {
                outputs += StagedDiagnosticCapture.capture(directory, "repeated", listOf(stage("logs") { it.record("retained") }), available).file
            }
            assertEquals(8, outputs.size)
            assertTrue(outputs.all(File::isFile))
            assertTrue(File(directory, "diagnostic-capture-checkpoints").listFiles().orEmpty().size <= 4)
        }
    }

    @Test fun verifierRejectsChangedPayloadEvenWhenZipIntegrityIsValid() = temporary { directory ->
        runBlocking {
            val result = StagedDiagnosticCapture.capture(directory, "verified", listOf(stage("logs") { it.record("valid") }), available)
            val tampered = File(directory, "tampered.zip")
            ZipFile(result.file).use { source ->
                ZipOutputStream(tampered.outputStream()).use { output ->
                    source.entries().asSequence().forEach { entry ->
                        output.putNextEntry(ZipEntry(entry.name))
                        if (entry.name == "logs.jsonl") output.write("other\n".toByteArray()) else source.getInputStream(entry).use { it.copyTo(output) }
                        output.closeEntry()
                    }
                }
            }
            assertTrue(runCatching { StagedDiagnosticCapture.verify(tampered) }.isFailure)
        }
    }

    @Test fun metadataOnlyCaptureDoesNotClaimUsefulLogRecords() = temporary { directory ->
        runBlocking {
            val result = StagedDiagnosticCapture.capture(directory, "metadata", listOf(stage("metadata") { it.note("version-only") }), available)
            assertEquals(0L, result.usefulRecords)
            assertTrue(result.retryRecommended)
            assertEquals("COMPLETED", receipt(result, "metadata").text("status"))
            StagedDiagnosticCapture.verify(result.file)
        }
    }

    @Test fun utf8CountsMatchNonAsciiAndMalformedSurrogateEncoding() = temporary { directory ->
        runBlocking {
            val line = "Canadian café 🇨🇦 尾 \uD800"
            val result = StagedDiagnosticCapture.capture(directory, "utf8", listOf(stage("logs") { it.record(line) }), available)
            assertEquals((line.toByteArray(Charsets.UTF_8).size + 1).toString(), receipt(result, "logs").text("bytes"))
            StagedDiagnosticCapture.verify(result.file)
        }
    }

    @Test fun reducedRetryRecapturesOversizedCompletedCheckpointWithinNewLimits() = temporary { directory ->
        runBlocking {
            var calls = 0
            val stages = listOf(
                stage("conversation") { sink ->
                    calls++
                    repeat(30) { sink.record("record-$it-" + "x".repeat(40)) }
                }
            )
            val full = StagedDiagnosticCapture.capture(directory, "budget", stages, available)
            val reduced = StagedDiagnosticCapture.capture(directory, "budget", stages, available.copy(maxRecordBytes = 64, maxStageBytes = 128), full.sessionId)
            assertEquals(2, calls)
            assertFalse(reduced.complete)
            assertTrue(receipt(reduced, "conversation").text("bytes").toLong() <= 128)
            assertEquals(JsonPrimitive(false), receipt(reduced, "conversation")["reusedCheckpoint"])
            StagedDiagnosticCapture.verify(reduced.file)
        }
    }

    @Test fun globalGateSpansPreparationAndNestedCapture() = temporary { directory ->
        runBlocking {
            DiagnosticExportGate.run {
                assertTrue(runCatching { DiagnosticExportGate.acquire() }.exceptionOrNull() is DiagnosticExportBusy)
                assertTrue(StagedDiagnosticCapture.capture(directory, "nested", listOf(stage("logs") { it.record("nested") }), available).complete)
            }
            DiagnosticExportGate.acquire().close()
        }
    }

    @Test fun invalidatedCaptureNeverPublishesEvenWhenRowsWereWritten() = temporary { directory ->
        runBlocking {
            var valid = true
            val options = available.copy(guard = { if (!valid) throw CancellationException("deleted") })
            val failure = runCatching {
                StagedDiagnosticCapture.capture(
                    directory,
                    "private",
                    listOf(
                        stage("conversation") {
                            it.record("retained")
                            valid = false
                        }
                    ),
                    options
                )
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertTrue(directory.listFiles().orEmpty().none { it.extension == "zip" })
            DiagnosticExportGate.acquire().close()
        }
    }

    @Test fun stageCannotOverwriteSessionCheckpoint() = temporary { directory ->
        runBlocking {
            val failure = runCatching {
                StagedDiagnosticCapture.capture(directory, "reserved", listOf(DiagnosticCaptureStage("evil", "session.json") { it.record("unexpected") }), available)
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        }
    }

    @Test fun unavailableNativeAndGcStatisticsStayNull() {
        val data = memory(100).json()
        assertEquals("null", data.getValue("nativeAllocatedBytes").toString())
        assertEquals("null", data.getValue("gcCount").toString())
        assertEquals(JsonPrimitive(100L), data["javaHeadroomBytes"])
    }

    private fun stage(id: String, critical: Boolean = false, lowMemorySafe: Boolean = false, block: suspend (DiagnosticStageSink) -> Unit) =
        DiagnosticCaptureStage(id, "$id.jsonl", critical, lowMemorySafe, capture = block)

    private fun receipt(result: DiagnosticCaptureResult, id: String): JsonObject =
        (result.manifest.getValue("stages") as JsonArray).map { it.jsonObject }.single { it.text("id") == id }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
    private fun memory(headroom: Long) = DiagnosticExportMemory(1, 16L * 1024 * 1024, 8L * 1024 * 1024, 8L * 1024 * 1024, headroom)
    private fun temporary(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("staged-diagnostics-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
