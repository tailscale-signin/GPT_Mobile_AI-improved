package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticCaptureRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun failureEnvelopeSurvivesBeforeCacheReleaseAndExpensiveReadWaitsForRecoveredHeadroom() = runBlocking {
        var headroom = 8L * 1024 * 1024
        val order = mutableListOf<String>()
        val result = StagedDiagnosticCapture.capture(
            temporary.newFolder(),
            "cache-recovery",
            listOf(
                DiagnosticCaptureStage("history", "history.jsonl") { sink ->
                    order += "history"
                    assertEquals(1, sink.sourcePageSize)
                    sink.record("{\"history\":true}")
                },
                DiagnosticCaptureStage("failure", "failure.jsonl", critical = true, lowMemorySafe = true) { sink ->
                    order += "failure"
                    sink.record("{\"failure\":true}")
                }
            ),
            DiagnosticCaptureOptions(
                reducedMemory = true,
                minimumHeadroomBytes = 16L * 1024 * 1024,
                memorySampler = { memory(headroom) },
                releaseOptionalCaches = {
                    order += "release"
                    headroom = 32L * 1024 * 1024
                }
            )
        )
        assertEquals(listOf("failure", "release", "history"), order)
        assertTrue(result.complete)
        val history = (result.manifest["stages"] as JsonArray).map { it.jsonObject }.single { it["id"] == JsonPrimitive("history") }
        assertEquals(JsonPrimitive(true), history["heapPressureRetry"])
        assertEquals(JsonPrimitive(16L * 1024 * 1024), history["retryRequiredHeadroomBytes"])
        assertEquals(result.manifest, StagedDiagnosticCapture.verify(result.file))
    }

    @Test fun unsuccessfulCacheReleaseCannotLoseCriticalEvidenceOrAdmitAnExpensiveRead() = runBlocking {
        var expensiveReads = 0
        var releases = 0
        val result = StagedDiagnosticCapture.capture(
            temporary.newFolder(),
            "cache-still-constrained",
            listOf(
                DiagnosticCaptureStage("failure", "failure.jsonl", critical = true) { it.record("{\"failure\":true}") },
                DiagnosticCaptureStage("history", "history.jsonl") { expensiveReads++ }
            ),
            DiagnosticCaptureOptions(
                minimumHeadroomBytes = 16L * 1024 * 1024,
                memorySampler = { memory(8L * 1024 * 1024) },
                releaseOptionalCaches = {
                    releases++
                    error("optional cache owner unavailable")
                }
            )
        )
        assertEquals(1, releases)
        assertEquals(0, expensiveReads)
        assertEquals(1L, result.usefulRecords)
        assertFalse(result.complete)
        assertEquals(result.manifest, StagedDiagnosticCapture.verify(result.file))
    }

    private fun memory(headroom: Long) = DiagnosticExportMemory(1, 64L * 1024 * 1024, 64L * 1024 * 1024, 64L * 1024 * 1024 - headroom, headroom)
}
