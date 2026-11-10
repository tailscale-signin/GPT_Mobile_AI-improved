package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubAuditContinuationTest {
    private val definition = AgentToolDefinition(
        "github_context",
        "Context",
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "action",
                        buildJsonObject {
                            put("type", "string")
                            put("enum", JsonArray(listOf(JsonPrimitive("capabilities"))))
                        }
                    )
                }
            )
        }
    )

    @Test fun readyReadsHaveExactRecipesInsteadOfInventedMissingPrerequisites() {
        val rows = GitHubTestChecklist.rows(emptyMap(), listOf(definition), "Test all GitHub integration tools")
        val row = rows.filterIsInstance<JsonObject>().first { it["id"] == JsonPrimitive("github_context:capabilities") }
        assertEquals(JsonPrimitive("READY_UNTESTED"), row["applicability"])
        assertEquals(JsonPrimitive("UNTESTED"), row["status"])
        assertEquals(JsonPrimitive("capabilities"), ((row["next_call"] as JsonObject)["arguments"] as JsonObject)["action"])
    }

    @Test fun changedUnrelatedObservationDoesNotClaimTaskAdvancement() {
        fun receipts(hash: String) = mapOf(
            "read" to buildJsonObject {
                put("tool", "github_context")
                put("arguments", buildJsonObject { put("action", "capabilities") })
                put("trusted", true)
                put("success", true)
                put("dispatched", true)
                put("result_sha256", hash)
                put("result", buildJsonObject { put("observed_at", hash) })
            }
        )
        val task = "Inspect repository capabilities"
        assertEquals(GitHubTestChecklist.progressDigest(task, receipts("a"), listOf(definition)), GitHubTestChecklist.progressDigest(task, receipts("b"), listOf(definition)))
    }

    @Test fun terminalNewlineIsNotAnotherSourceLine() {
        assertEquals(listOf("one", "two", "three"), GitHubRepositorySession.sourceLines("one\ntwo\nthree\n"))
        assertEquals(listOf("one", ""), GitHubRepositorySession.sourceLines("one\n\n"))
        assertTrue(GitHubRepositorySession.sourceLines("").isEmpty())
    }

    @Test fun admittedPolicyKeepsBudgetContractsAndSelectedIdentityWithoutRawModel() {
        val snapshot = GitHubRunPolicySnapshot.capture("run", "private-profile", "private-model", "revision", "Task", listOf(definition), AgentRunLimits(maxToolCalls = 256), JsonObject(emptyMap()), 76)
        assertEquals(JsonPrimitive(180), snapshot["remaining_tool_calls"])
        assertEquals(1, (snapshot["tool_contracts"] as JsonArray).size)
        assertFalse(snapshot.toString().contains("private-model"))
        assertEquals(JsonNull, GitHubRunPolicySnapshot.capture("run", "p", "m", "r", "Task", emptyList(), AgentRunLimits(), JsonObject(emptyMap()), null)["used_tool_calls"])
    }

    @Test fun requirementMemoryExcludesPresetControlsAndBudgetHeadings() {
        val requirements = GitHubEngineeringMemoryPolicy.requirements(GitHubWorkflowPreset.selected("test_integration").prompt())
        assertEquals(5, requirements.size)
        assertFalse(requirements.toString().contains("Budget:"))
        assertFalse(requirements.toString().contains("[github-workflow-preset:"))
    }

    @Test fun newPresetDoesNotRedefineHistoricalAllToolsRequests() {
        val prompt = GitHubWorkflowPreset.selected("test_integration").prompt()
        assertTrue(GitHubChecklistScope.supportedFixturePlan(prompt))
        val requirements = GitHubIntegrationAcceptance.requirements(prompt, emptyMap(), listOf(definition))
        val objective = requirements.first() as JsonObject
        assertTrue(objective["acceptance"].toString().contains("Every requested authorized API action"))
        assertFalse(GitHubChecklistScope.supportedFixturePlan("Test all authorized GitHub integration tools.\n[github-workflow-preset:test_integration]"))
    }

    @Test fun specificAcceptanceStopIsDisplayedInsteadOfGenericContinuationAdvice() {
        val report = buildJsonObject {
            put(
                "stop_reason",
                buildJsonObject {
                    put("code", "AUTHORIZED_CATALOG_INCOMPLETE")
                    put("detail", "Reconcile changed GitHub contracts; no cursor remains.")
                }
            )
        }
        assertTrue(GitHubExecutionContinuation.incompleteMessage(report, emptyMap()).contains("Reconcile changed GitHub contracts"))
    }
}
