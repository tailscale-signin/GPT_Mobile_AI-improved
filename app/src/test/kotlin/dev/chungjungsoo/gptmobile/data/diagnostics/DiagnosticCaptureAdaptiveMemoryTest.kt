package dev.chungjungsoo.gptmobile.data.diagnostics

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticCaptureAdaptiveMemoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun heapDeferralLatchesOneRowPagesForLaterStagesAndResumeWithoutLoweringTheGuard() = runBlocking {
        val directory = temporary.newFolder("capture")
        val mib = 1024L * 1024
        var pressured = false
        var firstPart = true
        val observed = mutableListOf<Pair<String, Int>>()
        val stages = listOf(
            DiagnosticCaptureStage("workspace", "workspace.jsonl", resumableRecords = true) { sink ->
                observed += "workspace" to sink.sourcePageSize
                val after = sink.resumeCursor?.get("after")?.jsonPrimitive?.content?.toInt() ?: 0
                for (row in after + 1..3) {
                    if (firstPart && row == 2) {
                        pressured = true
                        sink.checkBudget()
                    }
                    sink.record("row-$row")
                    sink.checkpoint(JsonObject(mapOf("after" to JsonPrimitive(row))))
                }
            },
            DiagnosticCaptureStage("later", "later.jsonl", resumableRecords = true) { sink ->
                observed += "later" to sink.sourcePageSize
                sink.record("later-evidence")
                sink.checkpoint(JsonObject(mapOf("after" to JsonPrimitive(1))))
            }
        )
        val options = DiagnosticCaptureOptions(
            minimumHeadroomBytes = 16 * mib,
            memorySampler = { DiagnosticExportMemory(1, 256 * mib, 256 * mib, 256 * mib - if (pressured) 12 * mib else 32 * mib, if (pressured) 12 * mib else 32 * mib) }
        )
        val first = StagedDiagnosticCapture.capture(directory, "adaptive", stages, options)
        assertFalse(first.complete)
        assertEquals(JsonPrimitive(true), first.manifest["cooperativeHeapRecovery"])
        assertTrue(File(directory, "diagnostic-capture-checkpoints/${first.sessionId}/session.json").readText().contains("\"cooperativeHeapRecovery\":true"))
        firstPart = false
        pressured = false
        val second = StagedDiagnosticCapture.capture(directory, "adaptive", stages, options, first.sessionId)
        assertTrue(second.complete)
        assertEquals(listOf("workspace" to 16, "workspace" to 1, "later" to 1), observed)
        assertEquals(JsonPrimitive(16 * mib), (second.manifest.getValue("stages") as kotlinx.serialization.json.JsonArray).first().let { it as JsonObject }.getValue("memoryGuard").let { it as JsonObject }["minimumRequiredHeadroomBytes"])
        assertEquals(second.manifest, StagedDiagnosticCapture.verify(second.file))
    }
}
