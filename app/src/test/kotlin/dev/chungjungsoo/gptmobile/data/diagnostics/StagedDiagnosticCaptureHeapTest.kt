package dev.chungjungsoo.gptmobile.data.diagnostics

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Dedicated 64 MiB JVM gate: the raw source corpus exceeds the entire process heap. */
class StagedDiagnosticCaptureHeapTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun streamsAndResumesHistoryLargerThanHeapWithIndependentFailure() = runBlocking(Dispatchers.Default) {
        assumeTrue("Requires the diagnostic 64 MiB heap gate", java.lang.Boolean.getBoolean("gptmobile.diagnosticHeapStress"))
        val heap = Runtime.getRuntime().maxMemory()
        assertTrue("The gate must actually constrain the test JVM to 64 MiB", heap <= 64L * 1024 * 1024)
        val records = 20_000
        val payload = "a".repeat(8192)
        val generatedBytes = records.toLong() * payload.length
        assertTrue("Generated source must be larger than the process heap", generatedBytes > heap * 2)
        val directory = temporary.newFolder("capture")
        var toolHealthy = false
        var conversationCaptures = 0
        val stages = listOf(
            DiagnosticCaptureStage("logs", "critical-logs.jsonl", critical = true) { sink ->
                sink.record("{\"event\":\"test_capture_started\"}")
            },
            DiagnosticCaptureStage("conversation", "conversation.jsonl") { sink ->
                conversationCaptures++
                repeat(records) { index ->
                    sink.checkBudget()
                    sink.record("{\"index\":$index,\"content\":\"$payload\"}")
                }
            },
            DiagnosticCaptureStage("tool_receipts", "tool-receipts.jsonl") { sink ->
                sink.record("{\"operation\":\"observed\"}")
                if (!toolHealthy) throw JavaHeapWorkDeferred()
                sink.record("{\"operation\":\"recovered\"}")
            },
            DiagnosticCaptureStage("profiles", "profiles.jsonl", lowMemorySafe = true) { sink ->
                sink.record("{\"profile\":\"redacted\"}")
            }
        )
        val options = DiagnosticCaptureOptions(maxStageBytes = 192L * 1024 * 1024, maxArchiveBytes = 256L * 1024 * 1024)
        val first = StagedDiagnosticCapture.capture(directory, "heap-fixture", stages, options)
        assertFalse(first.complete)
        assertEquals(records.toLong() + 3, first.usefulRecords)
        assertEquals("JAVA_HEAP_PRESSURE", stage(first, "tool_receipts")["omissionReason"]?.let { (it as JsonPrimitive).content })
        assertEquals("COMPLETED", stage(first, "profiles")["status"]?.let { (it as JsonPrimitive).content })
        assertTrue(File(directory, "diagnostic-capture-checkpoints/${first.sessionId}/conversation.jsonl").length() > heap * 2)
        toolHealthy = true
        val resumed = StagedDiagnosticCapture.capture(directory, "heap-fixture", stages, options, first.sessionId)
        assertTrue(resumed.complete)
        assertEquals(1, conversationCaptures)
        assertEquals(JsonPrimitive(true), stage(resumed, "conversation")["reusedCheckpoint"])
        assertEquals(records.toLong() + 4, resumed.usefulRecords)
        assertEquals(resumed.manifest, StagedDiagnosticCapture.verify(resumed.file, options.maxArchiveBytes))
        println("Staged diagnostic heap gate: heap=$heap generatedSourceBytes=$generatedBytes records=$records resumed=true")
    }

    private fun stage(result: DiagnosticCaptureResult, id: String) =
        (result.manifest["stages"] as JsonArray).map { it.jsonObject }.single { it["id"] == JsonPrimitive(id) }
}
