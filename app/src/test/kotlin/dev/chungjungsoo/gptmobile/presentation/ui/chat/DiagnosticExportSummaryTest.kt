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

    @Test fun disabledApplicationRecorderRequiresReproductionAndDoesNotSuggestMemoryRepairOrResume() {
        val root = buildJsonObject {
            put(
                "stages",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("id", "critical_logs")
                            put("status", "PARTIAL")
                            put("evidenceRecords", 0)
                            put("omissions", buildJsonObject { put("TRACKING_DISABLED_NO_RETAINED_LOGS", 1) })
                        }
                    )
                )
            )
            put("resumable", false)
            put("resumeSupported", true)
            put("recoveryAction", "NEW_CAPTURE_REQUIRED")
        }
        val summary = diagnosticExportSummary(root)
        assertTrue(summary.contains("Enable application log recording"))
        assertTrue(summary.contains("reproduce the issue"))
        assertTrue(summary.contains("Network diagnostics uses a separate setting"))
        assertFalse(summary.contains("Low-memory"))
        assertFalse(summary.contains("Temporary"))
        assertFalse(summary.contains("Resume diagnostic capture"))
    }

    @Test fun retainedSourceOmissionRequestsNewCaptureWithoutCallingItTemporary() {
        val root = buildJsonObject {
            put("stages", JsonArray(listOf(stage("critical_logs", "COMPLETED", 2), stage("workspace", "PARTIAL", 5))))
            put("resumable", false)
            put("resumeSupported", true)
            put("recoveryAction", "NEW_CAPTURE_REQUIRED")
        }
        val summary = diagnosticExportSummary(root)
        assertTrue(summary.contains("Start a new diagnostic capture after reproducing"))
        assertFalse(summary.contains("Temporary"))
        assertFalse(summary.contains("Resume diagnostic capture"))
    }

    @Test fun archiveContinuationExplainsNewTailAndStageCoverage() {
        val root = buildJsonObject {
            put("stages", JsonArray(listOf(stage("critical_logs", "COMPLETED", 2), stage("workspace", "PARTIAL", 40))))
            put("continuationAvailable", true)
            put("captureCompletionPercent", 50)
        }
        val summary = diagnosticExportSummary(root)
        assertTrue(summary.contains("next archive part with new history records"))
        assertTrue(summary.contains("Keep this archive"))
        assertTrue(summary.contains("1 of 2 requested sections"))
        assertTrue(summary.contains("does not measure the percentage of history"))
        assertFalse(summary.contains("50%"))
    }

    @Test fun completedContinuationRetainsTheRequiredPriorArchiveFilename() {
        val root = buildJsonObject {
            put("stages", JsonArray(listOf(stage("critical_logs", "COMPLETED", 2), stage("workspace", "COMPLETED", 40))))
            put("archivePart", 3)
            put("externalPartsRequired", true)
            put(
                "previousArchive",
                buildJsonObject {
                    put("fileName", "previous-part-2.zip")
                    put("sha256", "digest")
                }
            )
        }
        val summary = diagnosticExportSummary(root)
        assertTrue(summary.contains("archive part 3"))
        assertTrue(summary.contains("previous-part-2.zip"))
        assertTrue(summary.contains("earlier linked parts"))
        assertTrue(summary.contains("not repeated here"))
    }

    private fun stage(id: String, status: String, evidence: Long, records: Long = evidence): JsonObject = buildJsonObject {
        put("id", id)
        put("status", status)
        put("records", records)
        put("evidenceRecords", evidence)
    }

    private fun manifest(vararg stages: JsonObject): JsonObject = buildJsonObject { put("stages", JsonArray(stages.toList())) }
}
