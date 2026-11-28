package dev.chungjungsoo.gptmobile.data.agent

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolDependencyDiagnosticsTest {
    @Test fun aDecisionBarrierKeepsThePrerequisiteIdentityWithoutClaimingTheToolIsMissing() {
        val choice = ProviderEvent.ToolCall("private-choice", "github_decide", JsonObject(emptyMap()))
        val pending = ToolDependencyDiagnostics.decisionDeferred("dependent", listOf(choice), emptyList())
        assertEquals("DEPENDENCY_DEFERRED", pending.errorCode)
        assertFalse(pending.dispatched)
        assertEquals(JsonPrimitive(1), pending.executionReceipt?.get("pending_decision_count"))
        assertEquals(JsonArray(listOf(JsonPrimitive("DECISION_REQUIRED"))), pending.executionReceipt?.get("dependency_admission_reasons"))
        assertFalse(pending.executionReceipt.toString().contains("private-choice"))

        val answered = ToolDependencyDiagnostics.decisionDeferred("dependent", listOf(choice), listOf(AgentToolResult(choice.callId, ToolResultContent.Text("Proceed with the selected fix."), false, dispatched = false)))
        assertEquals(JsonPrimitive(0), answered.executionReceipt?.get("pending_decision_count"))
        assertEquals(JsonPrimitive(1), answered.executionReceipt?.get("answered_decision_count"))
        assertTrue(answered.content.toString().contains("Apply that answer"))
        assertFalse(answered.dispatched)
    }

    @Test fun deferredEdgesAreBoundedAndDoNotInventFailedPrerequisites() {
        val dependencies = (1..12).mapTo(sortedSetOf()) { "private-call-$it" }
        val result = ToolDependencyDiagnostics.rejection("dependent", ToolDependencyDiagnostics.State.DEFERRED, dependencies, dependencies, "Deferred")
        val receipt = requireNotNull(result.executionReceipt)
        assertEquals("DEPENDENCY_DEFERRED", result.errorCode)
        assertFalse(result.dispatched)
        assertEquals(JsonPrimitive(12), receipt["dependency_count"])
        assertEquals(JsonPrimitive("DEFERRED"), receipt["native_dependency_state"])
        assertFalse(receipt.containsKey("dependency_failed_count"))
        assertEquals(8, (receipt.getValue("dependency_call_id_hashes") as JsonArray).size)
        assertFalse(receipt.toString().contains("private-call"))
    }

    @Test fun failedEdgesLinkOnlyObservedFailuresToTheOriginalCallHashDomain() {
        val result = ToolDependencyDiagnostics.rejection("dependent", ToolDependencyDiagnostics.State.FAILED, setOf("passed", "failed"), setOf("failed"), "Failed prerequisite")
        val receipt = requireNotNull(result.executionReceipt)
        val expected = MessageDigest.getInstance("SHA-256").digest("gptmobile.issue-context.v1:call:failed".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("DEPENDENCY_FAILED", result.errorCode)
        assertEquals(JsonPrimitive(2), receipt["dependency_count"])
        assertEquals(JsonPrimitive(1), receipt["dependency_failed_count"])
        assertEquals(JsonArray(listOf(JsonPrimitive(expected))), receipt["dependency_call_id_hashes"])
        assertTrue(result.isError && !result.dispatched)
    }
}
