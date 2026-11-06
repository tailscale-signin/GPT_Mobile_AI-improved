package dev.chungjungsoo.gptmobile.data.diagnostics

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticCaptureLogSnapshotTest {
    @Test fun frozenSnapshotIsRedactedAndSurvivesOriginalRingRemoval() = temporary { root ->
        runBlocking {
            var captures = 0
            fun capture(directory: File): DiagnosticLogSnapshot {
                captures++
                directory.mkdirs()
                val source = File(directory, "critical.log").apply { writeText("failure private-secret\nlifecycle\n") }
                return DiagnosticLogSnapshot(listOf(DiagnosticLogSource("critical.log", source, 2, source.length())), 123, false, 5, snapshotDirectory = directory, droppedCriticalRecords = 2)
            }
            val snapshot = DiagnosticCaptureLogSnapshot.open(root, {}, { text, _ -> text.replace("private-secret", "[redacted]") }, ::capture)
            assertFalse(File(root, "frozen-critical-logs/source-copies").exists())
            snapshot.close()
            val restored = DiagnosticCaptureLogSnapshot.open(root, {}, { _, _ -> error("Retained bytes are already redacted") }, { error("Do not replace retained logs with a later ring") })
            assertEquals(1, captures)
            assertEquals(2L, restored.recordCount)
            assertEquals(2L, restored.droppedCriticalRecords)
            assertEquals(123L, restored.capturedAtMillis)
            assertEquals("failure [redacted]\nlifecycle\n", restored.sources.single().file.readText())
            assertTrue(root.walkTopDown().filter(File::isFile).none { "private-secret" in it.readText() })
        }
    }

    @Test fun cancelledPartialLogStageReplaysItsFrozenEvidence() = temporary { root ->
        runBlocking {
            var session: File? = null
            var captured = 0
            var interrupt = true
            val options = DiagnosticCaptureOptions(minimumHeadroomBytes = 0, onSessionReady = { session = it })
            val stages = listOf(
                DiagnosticCaptureStage("critical_logs", "critical-app-logs.jsonl", critical = true) { sink ->
                    val logs = DiagnosticCaptureLogSnapshot.open(checkNotNull(session), { sink.checkBudget() }, { value, _ -> value }, { directory ->
                        captured++
                        directory.mkdirs()
                        val source = File(directory, "critical.log").apply { writeText("first failure\nsecond failure\n") }
                        DiagnosticLogSnapshot(listOf(DiagnosticLogSource("critical.log", source, 2, source.length())), 1, true, 0, snapshotDirectory = directory)
                    })
                    DiagnosticCaptureLogSnapshot.lines(logs.sources.single().file, { sink.checkBudget() }) { line ->
                        sink.record(line)
                        if (interrupt) throw CancellationException("Interrupted after the first retained record")
                    }
                }
            )
            assertTrue(runCatching { StagedDiagnosticCapture.capture(root, "chat", stages, options) }.exceptionOrNull() is CancellationException)
            val retained = checkNotNull(StagedDiagnosticCapture.findResumableSession(root, "chat"))
            interrupt = false
            val result = StagedDiagnosticCapture.capture(root, "chat", stages, options, retained)
            assertEquals(1, captured)
            assertEquals(2L, result.usefulRecords)
            ZipFile(result.file).use { zip ->
                assertEquals("first failure\nsecond failure\n", zip.getInputStream(zip.getEntry("critical-app-logs.jsonl")).reader().readText())
            }
        }
    }

    @Test fun recentFileFailureCannotDiscardFrozenCriticalEvidence() = temporary { root ->
        runBlocking {
            fun capture(directory: File): DiagnosticLogSnapshot {
                directory.mkdirs()
                val critical = File(directory, "critical.log").apply { writeText("critical evidence\n") }
                val missingRecent = File(directory, "current.log")
                return DiagnosticLogSnapshot(
                    listOf(
                        DiagnosticLogSource("critical.log", critical, 1, critical.length()),
                        DiagnosticLogSource("current.log", missingRecent, 1, 10)
                    ),
                    1,
                    true,
                    0,
                    snapshotDirectory = directory
                )
            }
            val critical = DiagnosticCaptureLogSnapshot.open(root, {}, { value, _ -> value }, ::capture)
            assertEquals(1L, critical.recordCount)
            val recent = DiagnosticCaptureLogSnapshot.open(root, {}, { value, _ -> value }, ::capture, critical = false)
            assertEquals(0L, recent.recordCount)
            assertEquals("SOURCE_READ_FAILED", recent.sourceFailures["current.log"])
            assertEquals("critical evidence\n", critical.sources.single().file.readText())
        }
    }

    @Test fun budgetAndCancellationCallbacksAreNotSourceIoOmissions() = temporary { root ->
        runBlocking {
            for (failure in listOf(java.io.IOException("sink budget"), CancellationException("authorization changed"))) {
                var checks = 0
                val actual = runCatching {
                    DiagnosticCaptureLogSnapshot.open(root, {
                        if (++checks >= 3) throw failure
                    }, { value, _ -> value }, { directory ->
                        directory.mkdirs()
                        val source = File(directory, "critical.log").apply { writeText("failure evidence\n") }
                        DiagnosticLogSnapshot(listOf(DiagnosticLogSource("critical.log", source, 1, source.length())), 1, true, 0, snapshotDirectory = directory)
                    })
                }.exceptionOrNull()
                assertSame(failure, actual)
            }
        }
    }

    private fun temporary(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("frozen-diagnostic-logs").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
