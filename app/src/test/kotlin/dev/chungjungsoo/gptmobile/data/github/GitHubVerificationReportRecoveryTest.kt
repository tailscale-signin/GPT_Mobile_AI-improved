package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubVerificationReportRecoveryTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject
    private val issueTool = AgentToolDefinition("github_issue", "Issue history", json("""{"properties":{"action":{"const":"history"},"issue_number":{"type":"integer"}}}"""))
    private fun failed(code: String, dispatched: Boolean) = json("""{"trusted":true,"success":false,"dispatched":$dispatched,"tool":"github_issue","arguments":{"action":"history","issue_number":7},"error_code":"$code"}""")

    @Test fun legacyPreflightDenialCannotPretendTokenExpiryOrAnUncertainWrite() {
        val failure = failed("AUTH_REQUIRED", false)
        val row = GitHubTestChecklist.rows(mapOf("denial" to failure), listOf(issueTool)).single() as JsonObject
        assertEquals(JsonPrimitive("PREFLIGHT_REJECTED"), row["status"])
        assertEquals(JsonPrimitive("DENIED"), (row["readiness"] as JsonObject)["authorization"])
        val advice = (row["next_action"] as JsonPrimitive).content
        assertTrue(advice.contains("before dispatch"))
        assertTrue(advice.contains("does not prove a GitHub token expired"))
        assertFalse(advice.contains("reconcile an uncertain write"))
        assertFalse(GitHubTestChecklist.apiComplete("test all tools read only", mapOf("denial" to failure), listOf(issueTool)))
    }

    @Test fun remoteAuthenticationDenialHasDifferentRecoveryFromPreflight() {
        val advice = GitHubVerificationRecovery.advice(listOf(failed("AUTH_REQUIRED", true)))
        assertTrue(advice.contains("remote authentication rejection"))
        assertFalse(advice.contains("rejected before dispatch"))
    }

    @Test fun permissionAndSsoFailuresRemainBlocked() {
        for (code in listOf("PERMISSION_DENIED", "PERMISSION_REVOKED", "AUTH_REQUIRED", "GITHUB_SSO_REQUIRED")) {
            assertTrue(code, GitHubVerificationRecovery.authorizationBlocked(code))
        }
        assertFalse(GitHubVerificationRecovery.authorizationBlocked("REPOSITORY_GRAPH_UNAVAILABLE"))
    }

    @Test fun graphRecoveryDoesNotMisrepresentNativeStatusAsGraphSuccess() {
        val advice = GitHubVerificationRecovery.advice(listOf(failed("REPOSITORY_GRAPH_UNAVAILABLE", true)))
        assertTrue(advice.contains("host GitHub connection"))
        assertTrue(advice.contains("durable storage"))
        assertTrue(advice.contains("do not prove graph coverage"))
        assertFalse(advice.contains("reconcile an uncertain write"))
    }

    @Test fun onlyAnObservedUncertainEffectRequiresReconciliation() {
        val read = GitHubVerificationRecovery.advice(listOf(failed("GITHUB_OPERATION_FAILED", true)))
        assertTrue(read.contains("failed read is not an uncertain write"))
        val write = GitHubVerificationRecovery.advice(listOf(failed("RECONCILIATION_REQUIRED", true)))
        assertTrue(write.contains("Reconcile the exact uncertain remote operation"))
    }

    @Test fun fixtureAndDecisionPrerequisitesDoNotBecomePassingTests() {
        val tools = listOf(
            AgentToolDefinition("github_ci", "Dispatch", json("""{"properties":{"action":{"const":"dispatch"}}}""")),
            AgentToolDefinition("github_decide", "Decision", json("""{"type":"object"}"""))
        )
        val rows = GitHubTestChecklist.rows(emptyMap(), tools, "test all tools").filterIsInstance<JsonObject>()
        val fixture = rows.single { it["id"] == JsonPrimitive("github_ci:dispatch") }
        assertEquals(JsonPrimitive("BLOCKED"), fixture["status"])
        val decision = rows.single { it["id"] == JsonPrimitive("github_decide:") }
        assertEquals(JsonPrimitive("UNTESTED"), decision["status"])
        assertEquals(JsonPrimitive("CONDITIONAL_USER_DECISION"), decision["applicability"])
        assertEquals(JsonPrimitive(false), decision["required"])
        assertEquals(null, GitHubTestChecklist.nextCall("test all tools", tools, emptyMap()))
        assertFalse(GitHubTestChecklist.apiComplete("test all tools", emptyMap(), tools))
    }
}
