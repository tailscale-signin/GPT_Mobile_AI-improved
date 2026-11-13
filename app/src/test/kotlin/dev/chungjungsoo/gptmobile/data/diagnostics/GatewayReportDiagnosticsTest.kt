package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayModelPreparationFailure
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayModelPreparationReceipt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayReportDiagnosticsTest {
    private val run = AgentRun(
        runId = "private-owned-run", chatId = 1, userMessageId = 2, assistantMessageId = 3,
        profileUid = "private-profile", providerSnapshot = "LLAMA", modelSnapshot = "private-model",
        status = "INTERRUPTED", gatewayJobId = "private-owned-job", gatewayLastSequence = 9, ownershipFence = 4
    )

    @Test fun ownedRecoveryPreservesRemoteStateAndCheckpointWithoutInventingReplayAuthority() {
        val snapshot = requireNotNull(GatewayReportDiagnostics.recovery(run, "private-owned-job", "completed", 1_000_000))
        assertEquals("GATEWAY", snapshot.area)
        assertEquals(JsonPrimitive("COMPLETED"), snapshot.values["gateway_state"])
        assertEquals(JsonPrimitive(IssueReportContext.identifierHash("job", "private-owned-job")), snapshot.values["job_id_hash"])
        assertEquals(JsonPrimitive(IssueReportContext.identifierHash("gateway-checkpoint", "private-owned-run:4:9")), snapshot.values["checkpoint_hash"])
        assertNull(snapshot.values["transport_complete"])
        assertNull(snapshot.values["request_replay_allowed"])
        assertNull(snapshot.values["logical_continuation_allowed"])
        assertTrue("transport_complete" in snapshot.unavailableFields)
        assertFalse(Json.encodeToString(IssueReportDiagnostics.serializer(), snapshot).contains("private"))
        assertEquals("INTERRUPTED", run.status)
    }

    @Test fun aDifferentJobAndAbsentRemoteObservationNeverAcquireSavedJobFacts() {
        assertNull(GatewayReportDiagnostics.recovery(run, "another-job", "completed", 1_000_000))
        val unobserved = requireNotNull(GatewayReportDiagnostics.recovery(run.copy(gatewayLastSequence = -1), "private-owned-job", "unobserved", 1_000_000))
        assertNull(unobserved.values["gateway_state"])
        assertNull(unobserved.values["checkpoint_hash"])
        assertTrue(unobserved.isValid())
    }

    @Test fun exactModelPreparationRetainsRejectionForManualCaptureWithoutRawIdentifiers() {
        val receipt = GatewayModelPreparationReceipt(
            "gptmobile.gateway.model-preparation.v1", "private-operation", "private-model", "LOAD_REJECTED", "DISPATCHED",
            false, 1, "SLEEPING", preparationAction = "WAKE",
            failure = GatewayModelPreparationFailure("BACKEND_METADATA_DENIED", "EXECUTION_METADATA_DENIED", "denied", "selected_model", 403, "INVALID_API_KEY")
        )
        val snapshot = requireNotNull(GatewayReportDiagnostics.modelPreparation("private-profile", "private-model", receipt, 1_000_000))
        assertEquals(JsonPrimitive("LOAD_REJECTED"), snapshot.values["model_preparation_state"])
        assertEquals(JsonPrimitive("WAKE"), snapshot.values["model_preparation_action"])
        assertEquals(JsonPrimitive(false), snapshot.values["model_preparation_load_accepted"])
        assertEquals(JsonPrimitive(403), snapshot.values["gateway_upstream_status"])
        assertEquals(JsonPrimitive("INVALID_API_KEY"), snapshot.values["gateway_upstream_message_code"])
        assertNull(snapshot.values["gateway_state"])
        assertNull(snapshot.values["transport_complete"])
        assertFalse(Json.encodeToString(IssueReportDiagnostics.serializer(), snapshot).contains("private"))
        val cache = ReportDiagnosticSnapshotCache(clock = { 1_000_000 })
        cache.retain(snapshot)
        assertEquals(snapshot, cache.latest("GATEWAY"))
        assertNull(GatewayReportDiagnostics.modelPreparation("private-profile", "different-model", receipt, 1_000_000))
    }

    @Test fun unknownAcceptanceAndUnallowlistedFailureNeverBecomeInventedOrPrivateFacts() {
        val receipt = GatewayModelPreparationReceipt(
            "gptmobile.gateway.model-preparation.v1", "private-operation", "private-model", "LOAD_OUTCOME_UNKNOWN", "DISPATCHED",
            null, 1, "UNKNOWN", preparationAction = "WAKE",
            failure = GatewayModelPreparationFailure("private reason", "private code", "private category", "private path", 999, "private upstream text")
        )
        val snapshot = requireNotNull(GatewayReportDiagnostics.modelPreparation(null, "private-model", receipt, 1_000_000))
        assertNull(snapshot.values["model_preparation_load_accepted"])
        assertNull(snapshot.values["gateway_upstream_status"])
        assertNull(snapshot.values["original_error_code_hash"])
        assertFalse(Json.encodeToString(IssueReportDiagnostics.serializer(), snapshot).contains("private"))
        assertTrue(snapshot.isValid())
    }
}
