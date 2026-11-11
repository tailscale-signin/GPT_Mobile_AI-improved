package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.database.dao.GatewayRunDiagnosticState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GatewayRunDiagnosticInventoryTest {
    @Test fun activeRunHasCheckpointAndCompletedRunDoesNotInventAcceptanceOrFailure() {
        val active = GatewayRunDiagnosticState("a".repeat(32), "WAITING_REMOTE_JOB", "eng_" + "b".repeat(32), 42, 3, null)
        val completed = active.copy(runId = "c".repeat(32), status = "COMPLETED")
        val runs = gatewayRunDiagnosticInventory(listOf(active, completed))["runs"] as JsonArray
        assertEquals(JsonPrimitive(true), (runs[0] as JsonObject)["active"])
        assertEquals(42L, (runs[0] as JsonObject).diagnosticLong("checkpoint_sequence"))
        val receipt = (runs[1] as JsonObject)["terminal_receipt"] as JsonObject
        assertEquals("COMPLETED", receipt.diagnosticText("status"))
        assertEquals("none", receipt.diagnosticText("next_action"))
        assertFalse(receipt.containsKey("acceptance_verified"))
        assertFalse(receipt.containsKey("failed_stage"))
    }

    @Test fun typedRejectionRetainsBlockerWithoutCopyingPrivateFailureProse() {
        val failure = """{"schema":"gptmobile.queued-failure.v1","reason":"GATEWAY_ENGINEERING_GITHUB_REQUIRED","phase":"connection_preflight","dispatch_state":"NOT_DISPATCHED","remote_acceptance":"NOT_ACCEPTED","secret":"private-token"}"""
        val result = gatewayRunDiagnosticInventory(listOf(GatewayRunDiagnosticState("a".repeat(32), "BLOCKED", null, -1, 1, failure)))
        val receipt = ((result["runs"] as JsonArray).single() as JsonObject)["terminal_receipt"] as JsonObject
        assertEquals("GATEWAY_ENGINEERING_GITHUB_REQUIRED", receipt.diagnosticText("reason_code"))
        assertEquals("connection_preflight", receipt.diagnosticText("failed_stage"))
        assertEquals("authorize", receipt.diagnosticText("next_action"))
        assertFalse(result.toString().contains("private-token"))
    }

    @Test fun boundedSnapshotDisclosesTruncationAndRejectsUnrecognizedStates() {
        val row = GatewayRunDiagnosticState("a".repeat(32), "arbitrary-private-text", null, -9, -1, null)
        val result = gatewayRunDiagnosticInventory(List(129) { row })
        assertEquals(JsonPrimitive(false), result["complete"])
        assertEquals(128, (result["runs"] as JsonArray).size)
        assertFalse(result.toString().contains("arbitrary-private-text"))
    }
}
