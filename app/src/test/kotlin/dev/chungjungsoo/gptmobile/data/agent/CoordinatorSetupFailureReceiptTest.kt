package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationException
import dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationPhase
import dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationState
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.github.GatewayAdmissionException
import dev.chungjungsoo.gptmobile.data.github.GatewayAdmissionReason
import dev.chungjungsoo.gptmobile.data.github.GatewayAdmissionSurface
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringInputRejected
import dev.chungjungsoo.gptmobile.data.github.GatewayExecutionRejected
import dev.chungjungsoo.gptmobile.data.github.GatewayInputRejectionDetails
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinatorSetupFailureReceiptTest {
    @Test fun laterPrecheckPreservesEarlierDispatchAndAcceptanceThroughReceiptProjection() {
        val errors = listOf(
            GatewayExecutionRejected("EXECUTION_MODEL_NOT_READY", 409, inferenceAttempted = false),
            GatewayModelPreparationException(GatewayModelPreparationState("profile", "model", GatewayModelPreparationPhase.BLOCKED, "Preparation unavailable", failureCode = "MODEL_PREPARATION_REJECTED"))
        )
        for (acceptance in listOf(RemoteRequestAcceptance.UNKNOWN, RemoteRequestAcceptance.YES)) {
            val prior = ProfileFailureRecord("NETWORK_TIMEOUT", retryAllowed = false, dispatched = true, remoteAcceptance = acceptance, phase = "response_stream", invocationId = "prior-invocation", requestReplayAllowed = false)
            val message = MessageV2(content = "", platformType = "primary", timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, profileFailure = prior)))
            for (error in errors) {
                val reconciled = requireNotNull(coordinatorFailureRecord(message, error, gatewayPreflightKnownUnsent = false))
                assertEquals(true, reconciled.dispatched)
                assertEquals(acceptance, reconciled.remoteAcceptance)
                assertEquals("DISPATCHED", reconciled.dispatchState)
                assertEquals(false, reconciled.requestReplayAllowed)
                val event = requireNotNull(coordinatorSetupFailureEvent(reconciled, error, partialOutput = false))
                assertEquals(JsonPrimitive(true), event.failureDetails?.get("dispatched"))
                assertEquals(JsonPrimitive("DISPATCHED"), event.failureDetails?.get("dispatch_state"))
                assertEquals(JsonPrimitive(acceptance.name), event.failureDetails?.get("remote_acceptance"))
                assertEquals(JsonPrimitive(if (error is GatewayExecutionRejected) "REJECTED" else "NOT_DISPATCHED"), event.failureDetails?.get("attempt_dispatch_state"))
                val projected = ProfileFailureRecord.fromProvider(event.message, event.errorCode, event.failureDetails)
                assertEquals(true, projected.dispatched)
                assertEquals(acceptance, projected.remoteAcceptance)
                assertEquals(false, projected.requestReplayAllowed)
                assertFalse(projected.isUnsentSetup)
                assertEquals(prior, message.timeline.single().profileFailure)
                assertNull(event.invocationId)
            }
        }
    }

    @Test fun laterAdmissionFailureCannotReplaceAnEarlierUnknownRemoteOutcome() {
        val admission = GatewayAdmissionException(GatewayAdmissionReason.TRANSPORT_INTERRUPTED, null, null, GatewayAdmissionSurface.CAPABILITIES)
        for (category in listOf("GATEWAY_OUTCOME_UNKNOWN", "TOOL_OUTCOME_UNKNOWN")) {
            val retained = ProfileFailureRecord(
                category,
                retryAllowed = false,
                dispatched = true,
                remoteAcceptance = RemoteRequestAcceptance.UNKNOWN,
                phase = "gateway_dispatch",
                requestReplayAllowed = false
            )
            for (partialOutput in listOf(false, true)) {
                assertNull(coordinatorSetupFailureEvent(retained, admission, partialOutput))
            }
        }
    }

    @Test fun completeEngineeringGuardPreservesItsExactReasonAndReconciledFailureFacts() {
        val details = GatewayInputRejectionDetails.from(
            buildJsonObject {
                put("reasonCode", "ENGINEERING_TASK_SCOPE_REQUIRED")
                put("invalid_fields", JsonArray(listOf(JsonPrimitive("task_scope"), JsonPrimitive("private-unapproved-field"))))
                put("failed_stage", "request_validation")
            }
        )
        val rejected = GatewayEngineeringInputRejected(409, details)
        val reconciled = ProfileFailureRecord(
            "ENGINEERING_STATE_OR_INPUT_CHANGED",
            retryAllowed = false,
            dispatched = false,
            remoteAcceptance = RemoteRequestAcceptance.NO,
            phase = "request_validation",
            requestReplayAllowed = false
        )
        val event = requireNotNull(coordinatorSetupFailureEvent(reconciled, rejected, partialOutput = false))
        assertTrue(event.transportComplete)
        assertEquals("engineering_state_or_input_changed", event.errorCode)
        assertEquals(JsonPrimitive("ENGINEERING_TASK_SCOPE_REQUIRED"), event.failureDetails?.get("reasonCode"))
        assertEquals(JsonArray(listOf(JsonPrimitive("task_scope"))), event.failureDetails?.get("invalid_fields"))
        assertEquals(JsonPrimitive(409), event.failureDetails?.get("http_status"))
        assertEquals(JsonPrimitive("request_validation"), event.failureDetails?.get("failed_stage"))
        assertEquals(JsonPrimitive(false), event.failureDetails?.get("dispatched"))
        assertEquals(JsonPrimitive("NO"), event.failureDetails?.get("remote_acceptance"))
        assertEquals(JsonPrimitive(false), event.failureDetails?.get("request_replay_allowed"))
        assertFalse(event.failureDetails.toString().contains("private-unapproved-field"))
    }

    @Test fun partialOutputDoesNotGainAnUnsentClaimFromALaterExecutionRejection() {
        val rejected = GatewayExecutionRejected("EXECUTION_MODEL_NOT_READY", 409, failedStage = "provider_admission", inferenceAttempted = false)
        for (dispatched in listOf(null, true)) {
            val reconciled = ProfileFailureRecord(
                "EXECUTION_MODEL_NOT_READY",
                retryAllowed = false,
                dispatched = dispatched,
                remoteAcceptance = RemoteRequestAcceptance.UNKNOWN,
                phase = "provider_admission",
                requestReplayAllowed = false
            )
            val event = requireNotNull(coordinatorSetupFailureEvent(reconciled, rejected, partialOutput = true))
            assertEquals(dispatched?.let(::JsonPrimitive), event.failureDetails?.get("dispatched"))
            assertEquals(JsonPrimitive(if (dispatched == true) "DISPATCHED" else "UNKNOWN"), event.failureDetails?.get("dispatch_state"))
            assertEquals(JsonPrimitive("UNKNOWN"), event.failureDetails?.get("remote_acceptance"))
            assertEquals(JsonPrimitive(true), event.failureDetails?.get("partial_output_preserved"))
            assertEquals(JsonPrimitive(false), event.failureDetails?.get("request_replay_allowed"))
            val retained = ProfileFailureRecord.fromProvider(event.message, event.errorCode, event.failureDetails)
            assertEquals(dispatched, retained.dispatched)
            assertEquals(RemoteRequestAcceptance.UNKNOWN, retained.remoteAcceptance)
            assertFalse(retained.isUnsentSetup)
        }
    }
}
