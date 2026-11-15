package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubAcceptanceRecoveryCheckpointTest {
    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject

    @Test fun unavailableStatusReadRequestsPrerequisitesWithoutSpendingOrInventingAuthority() {
        val report = json("""{"completion_verified":false,"remaining_requirements":[{"id":"required-check-policy"}]}""")
        val budget = json("""{"effective_call_limit":32,"used_calls":7,"remaining_calls":25}""")
        val checkpoint = GitHubStatusRequest.checkpoint(emptyList(), emptyMap(), 1000L, 0L, "Repository health", "owner/repo", report, budget)
        val stop = checkpoint.getValue("stop_reason") as JsonObject
        assertEquals(JsonPrimitive("STATUS_OBSERVATION_UNAVAILABLE"), stop["code"])
        assertEquals(JsonPrimitive("RESOLVE_CAPABILITY_PREREQUISITE"), stop["recovery_action"])
        assertEquals(JsonPrimitive(25), stop["remaining_tool_calls"])
        assertEquals(budget, checkpoint["stage_budget"])
        assertEquals(JsonPrimitive(false), checkpoint["plan_complete"])
        assertEquals(JsonPrimitive(false), report["completion_verified"])
        assertFalse(checkpoint.containsKey("next_read"))
    }

    @Test fun pendingStatusReadRetainsItsOriginalAllowanceAndVerificationBoundary() {
        val tool = AgentToolDefinition("github_context", "Selected status", json("""{"properties":{"action":{"const":"status"}}}"""))
        val report = json("""{"completion_verified":false}""")
        for ((remaining, expected) in listOf(0 to "REVIEW_REMAINING_SCOPE_AND_BUDGET", 8 to "RESUME_UNVERIFIED_REQUIREMENTS")) {
            val budget = json("""{"remaining_calls":$remaining}""")
            val checkpoint = GitHubStatusRequest.checkpoint(listOf(tool), emptyMap(), 1000L, 0L, "Repository health", "owner/repo", report, budget)
            val stop = checkpoint.getValue("stop_reason") as JsonObject
            assertEquals(JsonPrimitive(expected), stop["recovery_action"])
            assertEquals(JsonPrimitive(remaining == 0), (checkpoint.getValue("next_read") as JsonObject)["requires_new_allowance"])
            assertEquals(budget, checkpoint["stage_budget"])
            assertEquals(JsonPrimitive(false), checkpoint["plan_complete"])
        }
    }

    @Test fun missingRepositoryInputsAreCountedWithoutConvertingOptionalSamplingIntoAcceptance() {
        val task = "Test all tools read only"
        val checklist = json(
            """{"authorized_catalog":{"complete":true},"fixtures":[],"requirements":[{"acceptance_state":"BLOCKED"}],
            "capabilities":[
              {"required":true,"status":"BLOCKED","blocker_code":"REPOSITORY_INPUT_REQUIRED"},
              {"required":true,"status":"BLOCKED","blocker_code":"REPOSITORY_INPUT_REQUIRED"},
              {"required":false,"status":"BLOCKED","blocker_code":"REPOSITORY_INPUT_REQUIRED"}
            ]}"""
        )
        val stop = GitHubIntegrationReport.stopReason(task, emptyMap(), emptyList(), 173, checklist)
        assertEquals(JsonPrimitive("CAPABILITY_PREREQUISITES_MISSING"), stop["code"])
        assertEquals(JsonPrimitive(2), (stop.getValue("required_capability_blocker_counts") as JsonObject)["REPOSITORY_INPUT_REQUIRED"])
        assertTrue((stop.getValue("detail") as JsonPrimitive).content.contains("2 need authorized repository, ref or source inputs"))
        assertTrue((stop.getValue("detail") as JsonPrimitive).content.contains("Unused budget does not grant approval"))
        assertEquals(JsonPrimitive(false), stop["exhaustive_inventory_required"])
        assertEquals(JsonPrimitive(173), stop["remaining_tool_calls"])
    }

    @Test fun approvedDiagnosticProjectionKeepsUnknownPolicyAndRequiresCompatibleReporter() {
        val observed = GitHubStatusPolicyEvidence.snapshot(json("""{"protection":{"available":false,"http_status":404},"rules":{"available":true,"data":[]},"branch":{"protected":false},"policy":{"required_checks_complete":false}}"""))
        val verdict = JsonObject(
            mapOf(
                "acceptance_ledger" to json("{}"),
                "status_coverage" to JsonArray(listOf(JsonObject(observed + ("item" to JsonPrimitive("Required-check policy")))))
            )
        )
        val fields = GitHubAcceptanceReportDiagnostics.fields(verdict, retainedReport = true)
        val diagnostic = requireNotNull(IssueReportContext.fromFields("CHAT_FAILURE", fields, 1_800_000_000_000).diagnostics)
        assertEquals(JsonPrimitive(404), diagnostic.values["status_policy_protection_http_status"])
        assertEquals(JsonPrimitive(false), diagnostic.values["status_branch_protected"])
        assertEquals(JsonPrimitive(false), diagnostic.values["status_policy_complete"])
        assertEquals(JsonPrimitive("CHECK_PROTECTION_READ_PERMISSION"), diagnostic.values["status_policy_recovery_hint"])
        assertTrue("ACCEPTANCE_RECOVERY_EVIDENCE_V1" in diagnostic.requiredServiceFeatures())
        assertFalse(diagnostic.toString().contains("VERIFIED_NONE"))
    }

    @Test fun graphSpecificPrerequisitesDoNotHideOtherRepositoryInputs() {
        for (graphBlocker in listOf("REPOSITORY_INPUT_REQUIRED", "GRAPH_INDEX_REQUIRED", "GRAPH_INPUT_REQUIRED")) {
            val checklist = json(
                """{"authorized_catalog":{"complete":true},"fixtures":[],"requirements":[{"acceptance_state":"BLOCKED"}],
                "capabilities":[
                  {"id":"github_read:file","required":true,"status":"BLOCKED","blocker_code":"REPOSITORY_INPUT_REQUIRED"},
                  {"id":"repository_graph:query","required":true,"status":"BLOCKED","blocker_code":"$graphBlocker","repository_graph_observation":"NOT_INDEXED"},
                  {"id":"repository_graph:node","required":false,"status":"BLOCKED","blocker_code":"$graphBlocker"}
                ]}"""
            )
            val stop = GitHubIntegrationReport.stopReason("Test all tools read only", emptyMap(), emptyList(), 173, checklist)
            val detail = (stop.getValue("detail") as JsonPrimitive).content
            assertEquals(JsonPrimitive("CAPABILITY_PREREQUISITES_MISSING"), stop["code"])
            assertEquals(JsonPrimitive(1), stop["graph_input_blocked_count"])
            assertTrue(graphBlocker, detail.contains("1 need authorized repository, ref or source inputs"))
            assertTrue(graphBlocker, detail.contains("1 require a graph index"))
            assertEquals(JsonPrimitive(173), stop["remaining_tool_calls"])
        }
    }
}
