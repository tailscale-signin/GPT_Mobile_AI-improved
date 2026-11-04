package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubIncidentExportTest {
    @Test fun missingEvidenceIsExplicitWithoutTreatingExecutionCompletionAsVerified() {
        val records = buildJsonObject {
            put("runs", JsonArray(listOf(Json.encodeToJsonElement(AgentRun.serializer(), AgentRun("run", 1, 2, 3, "profile", "provider", "model", AgentRunStatus.COMPLETED)))))
            put("toolEvents", JsonArray(emptyList()))
        }
        val report = githubIncidentExport(records)
        val run = (report["runs"] as JsonArray).single() as JsonObject
        assertEquals(JsonPrimitive("NOT_TESTED"), run["acceptanceStatus"])
        assertEquals(JsonPrimitive(false), run["verificationAvailable"])
        assertEquals(JsonPrimitive(false), report["sourceHistoryComplete"])
    }

    @Test fun latestVerdictAndUnparseableRecordsAreReportedHonestly() {
        val savedRun = AgentRun("run", 1, 2, 3, "profile", "provider", "model", AgentRunStatus.PARTIAL, terminalError = "GITHUB_REVIEW_CAPACITY")
        val records = buildJsonObject {
            put("runs", JsonArray(listOf(Json.encodeToJsonElement(AgentRun.serializer(), savedRun))))
            put("toolEvents", JsonArray(listOf(JsonPrimitive("unreadable"))))
            put(
                "workspace",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("id", "github-evidence:binding:verification_latest")
                            put("runId", "run")
                            put("updatedAt", 100)
                            put("payload", """{"completion_verified":false,"evidence_retention_complete":false,"verification_failure":"GITHUB_REVIEW_CAPACITY"}""")
                        }
                    )
                )
            )
        }
        val report = githubIncidentExport(records)
        val run = (report["runs"] as JsonArray).single() as JsonObject
        assertEquals(JsonPrimitive("GITHUB_REVIEW_CAPACITY"), run["failureCategory"])
        assertEquals(JsonPrimitive("BLOCKED"), run["acceptanceStatus"])
        assertEquals(JsonPrimitive(false), run["evidenceRetentionComplete"])
        assertEquals(JsonPrimitive(1), report["unreadableOperationRecords"])
        assertTrue(report.toString().contains("CONTINUE_RUN"))
        assertFalse(report.toString().contains("RETRY_FAILED_STAGE"))
    }
}
