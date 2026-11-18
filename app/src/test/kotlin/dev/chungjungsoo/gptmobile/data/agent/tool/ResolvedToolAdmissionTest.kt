package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolvedToolAdmissionTest {
    private val status = buildJsonObject {
        put("action", "copilot_task_status")
        put("gateway_job_id", "saved")
    }
    private val base = ResolvedAgentTool(
        object : AgentTool {
            override val definition = AgentToolDefinition("github_pr", "test", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(callId, ToolResultContent.Json(arguments), false)
        },
        "github",
        "GitHub",
        "github_pr",
        "github_pr",
        canReuseResult = { false },
        canExecuteArguments = { it["action"] == JsonPrimitive("copilot_task_status") }
    )

    @Test fun alternateHostAuthorityDoesNotReleaseNativeOrCachedEvidence() = runBlocking {
        assertTrue(base.executionAdmission(status)())
        assertFalse(requireNotNull(base.canReuseResult)())
        assertFalse(base.executionAdmission(buildJsonObject { put("action", "merge") })())
        assertFalse(base.copy(canExecuteArguments = null).executionAdmission(status)())
    }

    @Test fun everySharedGateStillConstrainsAlternateExecutionAndApprovalRechecks() = runBlocking {
        var binding = true
        var freeProfileConsent = true
        var personalDataConsent = true
        val resolved = base.withAdmissionGate { binding }.withAdmissionGate { freeProfileConsent }.withAdmissionGate { personalDataConsent }
        val approvalRecheck = resolved.executionAdmission(status)
        assertTrue(approvalRecheck())
        binding = false
        assertFalse(approvalRecheck())
        binding = true
        freeProfileConsent = false
        assertFalse(approvalRecheck())
        freeProfileConsent = true
        personalDataConsent = false
        assertFalse(approvalRecheck())
        assertFalse(requireNotNull(resolved.canReuseResult)())
        personalDataConsent = true
        assertTrue(approvalRecheck())
    }

    @Test fun approvalRechecksRetainExactArgumentsAndLegacyToolsKeepOriginalAdmission() = runBlocking {
        val received = mutableListOf<JsonObject>()
        var allowed = true
        val alternate = base.copy(canExecuteArguments = {
            received += it
            allowed
        })
        val approvalRecheck = alternate.executionAdmission(status)
        assertTrue(approvalRecheck())
        allowed = false
        assertFalse(approvalRecheck())
        assertEquals(listOf(status, status), received)
        var legacyAllowed = true
        val legacy = base.copy(canExecuteArguments = null, canReuseResult = { legacyAllowed }).withAdmissionGate { true }
        assertTrue(legacy.executionAdmission(status)())
        legacyAllowed = false
        assertFalse(legacy.executionAdmission(status)())
    }

    @Test fun grantRevokedDuringPriorAdmissionIsRejectedByTheFinalGate() = runBlocking {
        var consent = true
        val resolved = base.copy(canExecuteArguments = {
            consent = false
            true
        }).withAdmissionGate { consent }
        assertFalse(resolved.executionAdmission(status)())
    }
}
