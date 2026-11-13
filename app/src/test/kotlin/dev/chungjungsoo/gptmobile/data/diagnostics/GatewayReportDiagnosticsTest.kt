package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.Application
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GatewayReportDiagnosticsTest {
    private val run = AgentRun(
        runId = "private-owned-run", chatId = 1, userMessageId = 2, assistantMessageId = 3,
        profileUid = "private-profile", providerSnapshot = "LLAMA", modelSnapshot = "private-model",
        status = "INTERRUPTED", gatewayJobId = "private-owned-job", gatewayLastSequence = 9, ownershipFence = 4
    )

    @Test fun modelPreparationOperationRemainsSeparateFromInvocationOrJobIdentity() {
        val snapshot = requireNotNull(
            GatewayReportDiagnostics.admission(
                "profile",
                "model",
                stage = "model_preparation",
                ready = false,
                failureCode = "MODEL_PREPARATION_INCOMPLETE",
                reasonCode = "SELECTED_MODEL_NOT_LOADED",
                operationId = "private-load-operation",
                capturedAtMillis = 1_000_000
            )
        )
        assertEquals(JsonPrimitive("model_preparation"), snapshot.values["gateway_failure_phase"])
        assertEquals(JsonPrimitive("SELECTED_MODEL_NOT_LOADED"), snapshot.values["gateway_reason_code"])
        assertEquals(JsonPrimitive(IssueReportContext.identifierHash("gateway-operation", "private-load-operation")), snapshot.values["gateway_operation_id_hash"])
        assertNull(snapshot.values["invocation_id_hash"])
        assertNull(snapshot.values["job_id_hash"])
        assertNull(snapshot.values["gateway_http_status"])
        assertFalse(snapshot.toString().contains("private-load-operation"))
    }

    @Test fun admissionSnapshotNeedsNoJobAndManualApprovalRetainsTheSameObservation() {
        val snapshot = requireNotNull(
            GatewayReportDiagnostics.admission(
                "private-profile", "private-model", "sha256:" + "a".repeat(64), "16.0.0", 1,
                "request_validation", 409, false, "ENGINEERING_STATE_OR_INPUT_CHANGED", "ENGINEERING_TASK_SCOPE_REQUIRED", 1_000_000
            )
        )
        assertEquals(2, snapshot.schemaVersion)
        assertEquals(JsonPrimitive("a".repeat(64)), snapshot.values["gateway_manifest_hash"])
        assertEquals(JsonPrimitive(false), snapshot.values["gateway_admission_ready"])
        assertEquals(JsonPrimitive(409), snapshot.values["gateway_http_status"])
        assertTrue("job_id_hash" in snapshot.unavailableFields)
        assertNull(snapshot.values["job_id_hash"])
        assertNull(snapshot.values["request_replay_allowed"])
        val report = IssueReport("11111111-1111-4111-8111-111111111111", "manual", "1.0", 1, "abcdef0", "16", 36, "Fixture")
        val preview = IssueReportManualCapture.preview(report, true, "GATEWAY", snapshot, emptyList(), 1_000_001)
        val approved = IssueReportManualCapture.submission(preview, "Gateway admission", "A request was rejected", true)
        assertEquals(6, approved.context?.schemaVersion)
        assertEquals(snapshot.copy(captureMode = "SELECTED_CAPTURE"), approved.context?.diagnostics)
        val payload = approved.approvedPayload()
        assertFalse(payload.contains("private-profile"))
        assertFalse(payload.contains("private-model"))
        assertTrue(IssueReportContext.validDiagnosticsIn(IssueReport.json.parseToJsonElement(payload).let { (it as kotlinx.serialization.json.JsonObject)["context"] }))
        val stale = IssueReportManualCapture.preview(report, true, "GATEWAY", snapshot, emptyList(), 1_600_001)
        assertEquals("NO_CAPTURE", stale.context?.diagnostics?.captureMode)
        assertEquals(payload, approved.approvedPayload())
    }

    @Test fun admissionSnapshotRedactsPrivateValuesWithoutInventingGatewayReadiness() {
        val snapshot = requireNotNull(
            GatewayReportDiagnostics.admission(
                "profile", "model", "private-host", "16.0.0 private-path", -1,
                "private-stage", 999, null, "private-error", "private-reason", 1_000_000
            )
        )
        assertEquals(2, snapshot.schemaVersion)
        assertNull(snapshot.values["gateway_manifest_hash"])
        assertNull(snapshot.values["gateway_version"])
        assertNull(snapshot.values["gateway_protocol_version"])
        assertNull(snapshot.values["gateway_failure_phase"])
        assertNull(snapshot.values["gateway_http_status"])
        assertNull(snapshot.values["gateway_admission_ready"])
        assertNull(snapshot.values["gateway_reason_code"])
        assertFalse(Json.encodeToString(IssueReportDiagnostics.serializer(), snapshot).contains("private"))
        assertNull(GatewayReportDiagnostics.admission(null, null, capturedAtMillis = 1_000_000))
    }

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
