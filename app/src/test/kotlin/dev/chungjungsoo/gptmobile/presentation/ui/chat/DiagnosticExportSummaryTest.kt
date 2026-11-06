package dev.chungjungsoo.gptmobile.presentation.ui.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticExportSummaryTest {
    @Test fun metadataCannotBeDescribedAsCapturedLogs() {
        val summary = diagnosticExportSummary(manifest(stage("critical_logs", "EMPTY", 0, records = 1), stage("recent_logs", "PARTIAL", 0, records = 1)))
        assertTrue(summary.startsWith("No application log records"))
        assertTrue(summary.contains("Low-memory diagnostics"))
        assertFalse(summary.contains("prepared with"))
    }

    @Test fun partialConversationDoesNotHideRetainedToolFailureLogs() {
        val summary = diagnosticExportSummary(manifest(stage("critical_logs", "COMPLETED", 8), stage("recent_logs", "COMPLETED", 12), stage("conversation", "FAILED", 0)))
        assertTrue(summary.contains("20 retained log records"))
        assertTrue(summary.contains("1 section is incomplete"))
        assertTrue(summary.contains("Resume diagnostic capture"))
    }

    @Test fun successfulReducedCaptureDoesNotClaimFullSourceCoverage() {
        val root = buildJsonObject {
            put("stages", JsonArray(listOf(stage("critical_logs", "COMPLETED", 2))))
            put("reducedMemory", true)
        }
        val summary = diagnosticExportSummary(root)
        assertTrue(summary.startsWith("Low-memory diagnostics prepared"))
        assertTrue(summary.contains("retention limits"))
        assertFalse(summary.contains("fully"))
    }

    @Test fun temporaryCaptureNeverSuggestsADeletedResumeCheckpoint() {
        val root = buildJsonObject {
            put("stages", JsonArray(listOf(stage("critical_logs", "EMPTY", 0))))
            put("resumable", false)
        }
        val summary = diagnosticExportSummary(root)
        assertTrue(summary.contains("cannot be resumed"))
        assertFalse(summary.contains("Resume diagnostic capture"))
    }

    private fun stage(id: String, status: String, evidence: Long, records: Long = evidence): JsonObject = buildJsonObject {
        put("id", id)
        put("status", status)
        put("records", records)
        put("evidenceRecords", evidence)
    }

    private fun manifest(vararg stages: JsonObject): JsonObject = buildJsonObject { put("stages", JsonArray(stages.toList())) }
}
