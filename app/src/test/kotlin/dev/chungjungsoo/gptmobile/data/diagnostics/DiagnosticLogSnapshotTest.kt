package dev.chungjungsoo.gptmobile.data.diagnostics

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogSnapshotTest {
    @Test fun ringRemainsBoundedAndKeepsNewestRecordsAcrossRepeatedRotation() = withDirectory { directory ->
        val store = BoundedDiagnosticLogStore(directory, "current.log", "previous.log", 1024, 256)
        repeat(2000) { store.append("event=$it " + "x".repeat(80)) }
        assertTrue(File(directory, "current.log").length() <= 1024)
        assertTrue(File(directory, "previous.log").length() <= 1024)
        assertTrue(File(directory, "current.log").readLines().last().startsWith("event=1999 "))
    }

    @Test fun oversizedUnicodeRecordIsBoundedAndExplicitlyMarked() = withDirectory { directory ->
        val store = BoundedDiagnosticLogStore(directory, "current.log", "previous.log", 1024, 256)
        store.append("Important error " + "漢".repeat(100_000))
        val current = File(directory, "current.log")
        assertTrue(current.length() <= 257)
        val line = current.readText()
        assertTrue(line.startsWith("Important error "))
        assertTrue(line.contains("truncatedAtRing=true"))
        assertFalse(line.contains('\uFFFD'))
    }

    @Test fun reducedCopyRetainsCompleteTailRecordsAndExactCounts() = withDirectory { directory ->
        val source = File(directory, "current.log")
        source.bufferedWriter().use { output -> repeat(100_000) { output.appendLine("record=$it") } }
        val destination = File(directory, "snapshot.log")
        val capture = DiagnosticLogFiles.copyTail(source, destination, 1024)
        assertTrue(capture.skippedSourceBytes > 0)
        assertTrue(capture.byteCount <= 1024)
        assertEquals(destination.length(), capture.byteCount)
        assertEquals(destination.readLines().size.toLong(), capture.recordCount)
        assertEquals("record=99999", destination.readLines().last())
        assertTrue(destination.readLines().first().startsWith("record="))
    }

    @Test fun hugeLegacyLineCannotGrowSnapshotBufferWithoutBound() = withDirectory { directory ->
        val source = File(directory, "legacy.log")
        source.bufferedWriter().use { output ->
            repeat(1024) { output.write("x".repeat(8192)) }
            output.appendLine()
            output.appendLine("after-overflow")
        }
        val destination = File(directory, "snapshot.log")
        val capture = DiagnosticLogFiles.copyTail(source, destination, source.length())
        assertEquals(2L, capture.recordCount)
        assertEquals(1L, capture.truncatedRecords)
        assertTrue(capture.byteCount < DiagnosticLogFiles.MAX_RECORD_BYTES + 128)
        assertTrue(destination.readText().contains("truncatedAtSnapshot=true"))
        assertEquals("after-overflow", destination.readLines().last())
    }

    @Test fun aTailBeginningOnARecordBoundaryKeepsThatRecord() = withDirectory { directory ->
        val source = File(directory, "current.log").apply { writeText("old\nretained\n") }
        val destination = File(directory, "snapshot.log")
        val capture = DiagnosticLogFiles.copyTail(source, destination, "retained\n".toByteArray().size.toLong())
        assertEquals("retained\n", destination.readText())
        assertEquals(1L, capture.recordCount)
        assertEquals(4L, capture.skippedSourceBytes)
    }

    @Test fun snapshotTruncationDoesNotSplitUtf8Characters() = withDirectory { directory ->
        val source = File(directory, "legacy.log").apply { writeText("漢".repeat(10_000) + "\n") }
        val destination = File(directory, "snapshot.log")
        val capture = DiagnosticLogFiles.copyTail(source, destination, source.length())
        val text = destination.readBytes().decodeToString(throwOnInvalidSequence = true)
        assertFalse(text.contains('\uFFFD'))
        assertTrue(text.contains("truncatedAtSnapshot=true"))
        assertEquals(1L, capture.truncatedRecords)
        assertEquals(destination.length(), capture.byteCount)
    }

    @Test fun interruptedLastRecordIsRetainedWithExplicitTruncation() = withDirectory { directory ->
        val source = File(directory, "current.log").apply { writeBytes("crash frame ".toByteArray() + byteArrayOf(0xe6.toByte(), 0xbc.toByte())) }
        val destination = File(directory, "snapshot.log")
        val capture = DiagnosticLogFiles.copyTail(source, destination, source.length())
        val text = destination.readBytes().decodeToString(throwOnInvalidSequence = true)
        assertTrue(text.startsWith("crash frame "))
        assertTrue(text.contains("truncatedAtSnapshot=true"))
        assertEquals(1L, capture.recordCount)
        assertEquals(1L, capture.truncatedRecords)
    }

    @Test fun frozenSnapshotSurvivesRotationAndAppendAfterCapture() = withDirectory { directory ->
        val store = BoundedDiagnosticLogStore(directory, "current.log", "previous.log", 1024, 256)
        store.append("original error")
        val frozen = File(directory, "frozen.log")
        val capture = DiagnosticLogFiles.copyTail(File(directory, "current.log"), frozen, 1024)
        val producer = Thread { repeat(5000) { store.append("new event=$it " + "y".repeat(100)) } }
        producer.start()
        producer.join()
        assertEquals("original error\n", frozen.readText())
        assertEquals(1L, capture.recordCount)
    }

    @Test fun repeatedSnapshotCloseRemovesOwnedCopiesWithoutRemovingOriginal() = withDirectory { directory ->
        val source = File(directory, "current.log").apply { writeText("retained crash\n") }
        repeat(100) {
            val snapshotDirectory = File(directory, "snapshot-$it").apply { mkdir() }
            val copied = DiagnosticLogFiles.copyTail(source, File(snapshotDirectory, "current.log"), 1024)
            DiagnosticLogSnapshot(listOf(copied), 123L, true, 0, snapshotDirectory = snapshotDirectory).use { snapshot ->
                val received = mutableListOf<String>()
                snapshot.forEachLine(received::add)
                assertEquals(listOf("retained crash"), received)
            }
            assertFalse(snapshotDirectory.exists())
        }
        assertTrue(source.exists())
    }

    @Test fun emptySnapshotContainsNoFabricatedRecords() = withDirectory { directory ->
        val source = File(directory, "current.log").apply { writeText("") }
        val copied = DiagnosticLogFiles.copyTail(source, File(directory, "snapshot.log"), 1024)
        val snapshot = DiagnosticLogSnapshot(listOf(copied), 123L, false, 0, "TRACKING_DISABLED_NO_RETAINED_LOGS")
        assertEquals(0L, snapshot.recordCount)
        assertEquals(0L, snapshot.byteCount)
        assertEquals("TRACKING_DISABLED_NO_RETAINED_LOGS", snapshot.omissionReason)
    }

    @Test fun criticalWindowIncludesFailuresAndLifecycleWithoutVerboseSuccesses() {
        assertTrue(criticalDiagnosticRecord("Agent", "failed", "E"))
        assertTrue(criticalDiagnosticRecord("AppCrash", "sanitized frames", "F"))
        assertTrue(criticalDiagnosticRecord("ToolExecution", "rejected before dispatch", "I"))
        assertTrue(criticalDiagnosticRecord("GitHubWorkspace", "failure", "W"))
        assertTrue(criticalDiagnosticRecord("Lifecycle", "MainActivity stopped", "I"))
        assertTrue(criticalDiagnosticRecord("Memory", "ANDROID_MEMORY_TRIM · level=15", "W"))
        assertTrue(criticalDiagnosticRecord("Research", "JAVA_HEAP_PRESSURE", "I"))
        assertFalse(criticalDiagnosticRecord("FrameTiming", "normal rendering", "I"))
        assertFalse(criticalDiagnosticRecord("ToolExecution", "completed", "I"))
    }

    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("diagnostic-log-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
