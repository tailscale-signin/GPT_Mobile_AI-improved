package dev.chungjungsoo.gptmobile.data.benchmark

import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class BenchmarkStreamingExportHeapTest {
    @Test fun streamingEmitsCompleteBodiesAndLeavesTheCallerStreamOpen() {
        exercise(2, 3, 1024)
    }

    @Test fun moreThanFortyMiBOfRawHistoryAndAttemptCopiesStreamsWithin256MiBHeap() {
        assumeTrue("The large export gate requires scripts/heap-stress.init.gradle", java.lang.Boolean.getBoolean("gptmobile.heapStress"))
        assertTrue("This gate must use the constrained heap worker", Runtime.getRuntime().maxMemory() <= 256L * 1024 * 1024)
        val bytes = exercise(8, 64, 96 * 1024)
        assertTrue(bytes > 96L * 1024 * 1024)
    }

    private fun exercise(runCount: Int, casesPerRun: Int, bodyCharacters: Int): Long {
        val runs = (0 until runCount).map { runIndex ->
            val samples = (0 until casesPerRun).map { case ->
                // Each retained answer owns its body; the fixture does not alias one large String.
                val answer = "BEGIN_RAW_EVIDENCE_${runIndex}_$case:" + "x".repeat(bodyCharacters) + "_END_RAW_EVIDENCE"
                BenchmarkSample("case-$case", "Case", "task", BenchmarkOutcome.PASSED, canonicalAnswer = answer, outputCharacters = answer.length, requestDispatched = true, terminalReceived = true)
            }
            BenchmarkRun("run-$runIndex", "profile", "Profile", "CUSTOM", "model", "config", false, BenchmarkMode.FULL, runIndex.toLong(), samples, suiteVersion = 9, plannedTrials = casesPerRun)
        }
        val expectedRaw = MessageDigest.getInstance("SHA-256")
        repeat(2) { runs.forEach { run -> run.samples.forEach { sample -> expectedRaw.update(checkNotNull(sample.canonicalAnswer).toByteArray()) } } }
        val source = BenchmarkExportSource(runs, emptyList(), null, null, null, exportedAt = 1)
        val stream = EvidenceDigestStream()
        val writer = BenchmarkStreamingExport(stream)
        writeBenchmarkExport(writer, source, detailed = true) { output ->
            output.put("runs", benchmarkExportRecords(runs.asSequence().map { benchmarkExportJson.encodeToJsonElement(BenchmarkRun.serializer(), it) }))
            output.put(
                "attempts",
                benchmarkExportRecords {
                    runs.forEach { run ->
                        run.samples.forEachIndexed { index, sample ->
                            val attempt = BenchmarkAttempt("${run.id}:$index", "batch", 0, run.id, sample.testId, index, 0, 90_000, state = "TERMINAL", sample = sample)
                            add(benchmarkExportJson.encodeToJsonElement(BenchmarkAttempt.serializer(), attempt))
                        }
                    }
                }
            )
            output.put("note", JsonPrimitive("https://example.org/report?token=PRIVATE_EXPORT_SENTINEL"))
        }
        writer.finish()
        assertFalse(stream.closed)
        assertTrue(stream.flushed)
        assertEquals('}'.code, stream.lastByte)
        assertEquals(runCount * casesPerRun * 2, stream.rawRecords)
        assertEquals(expectedRaw.digest().toList(), stream.rawDigest.digest().toList())
        assertEquals(0, stream.secretOccurrences)
        assertFalse(stream.collectingRaw)
        println("Streamed benchmark export: ${stream.bytes} bytes, ${stream.rawRecords} complete raw bodies, sha256=${stream.documentDigest.digest().joinToString("") { "%02x".format(it) }}")
        return stream.bytes
    }

    /** Constant-memory proof of every complete raw body, redaction, and document termination. */
    private class EvidenceDigestStream : OutputStream() {
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

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            documentDigest.update(buffer, offset, length)
            for (index in offset until offset + length) observe(buffer[index])
        }

        private fun observe(value: Byte) {
            bytes++
            lastByte = value.toInt() and 255
            secretOffset = if (value == secret[secretOffset]) {
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
                endOffset = if (value == end[endOffset]) {
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
                beginOffset = if (value == begin[beginOffset]) {
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
