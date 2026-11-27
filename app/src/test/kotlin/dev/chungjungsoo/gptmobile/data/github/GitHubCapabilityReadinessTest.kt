package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReport
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportDiagnostics
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCapabilityReadinessTest {
    @Test fun inventoriesDistinguishAbsentAuthorizationFromMissingAdvertisement() {
        val operation = AgentToolDefinition("github_read", "Read source", JsonObject(emptyMap()))
        val unrelated = operation.copy(name = "weather")
        val absent = GitHubCapabilityReadiness.observe(listOf(unrelated), emptyList(), null)
        assertEquals(JsonPrimitive("CATALOG_NO_GITHUB_CAPABILITIES"), absent["github_prerequisite_observation"])
        val missing = GitHubCapabilityReadiness.observe(listOf(unrelated), listOf(operation, operation), null)
        assertEquals(JsonPrimitive("CATALOG_CAPABILITIES_NOT_ADVERTISED"), missing["github_prerequisite_observation"])
        assertEquals(JsonPrimitive(1), missing["github_authorized_tool_count"])
        assertEquals(JsonPrimitive(0), missing["github_advertised_tool_count"])
        val hidden = GitHubCapabilityReadiness.observe(emptyList(), listOf(operation.copy(modelVisible = false)), null)
        assertEquals(JsonPrimitive(0), hidden["github_authorized_tool_count"])
    }

    @Test fun bothCapabilityCausesAndCountsSurviveReportSanitization() {
        for (reason in GitHubCapabilityReadiness.observations) {
            for (area in listOf("INCIDENT", "ACCEPTANCE")) {
                val snapshot = requireNotNull(
                    IssueReportDiagnostics.fromFields(
                        "CHAT_FAILURE",
                        mapOf(
                            "diagnostic_area" to area,
                            "github_prerequisite_observation" to reason,
                            "github_authorized_tool_count" to 7,
                            "github_advertised_tool_count" to 0
                        ),
                        1000L
                    )
                )
                assertEquals(JsonPrimitive(reason), snapshot.values["github_prerequisite_observation"])
                assertEquals(JsonPrimitive(7), snapshot.values["github_authorized_tool_count"])
                assertEquals(JsonPrimitive(0), snapshot.values["github_advertised_tool_count"])
                assertTrue(GitHubCapabilityReadiness.FEATURE in snapshot.requiredServiceFeatures())
                assertTrue(snapshot.isValid())
                assertEquals(snapshot, IssueReportDiagnostics.fromJson(IssueReport.json.encodeToJsonElement(IssueReportDiagnostics.serializer(), snapshot)))
            }
        }
        val invalid = requireNotNull(
            IssueReportDiagnostics.fromFields(
                "CHAT_FAILURE",
                mapOf(
                    "github_authorized_tool_count" to -1,
                    "github_advertised_tool_count" to "private tool name",
                    "github_prerequisite_observation" to "private connection name"
                ),
                1000L
            )
        )
        assertFalse(invalid.values.keys.any { it in GitHubCapabilityReadiness.countFields || it == "github_prerequisite_observation" })
    }
}
