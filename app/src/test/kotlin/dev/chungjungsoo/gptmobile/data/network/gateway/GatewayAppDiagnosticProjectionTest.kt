package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogEntry
import dev.chungjungsoo.gptmobile.data.diagnostics.structuredDiagnosticEventMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayAppDiagnosticProjectionTest {
    private val run = "11111111-1111-1111-1111-111111111111"
    private val invocation = "22222222-2222-2222-2222-222222222222"

    @Test fun privacyPolicyRemainsSpecificWhileEarlierUnknownOutcomeKeepsItsPriority() {
        for (unknown in listOf(false, true)) {
            val message = structuredDiagnosticEventMessage(
                "Finished",
                mapOf(
                    "runId" to run,
                    "callId" to invocation,
                    "tool" to "github_read",
                    "errorCode" to "PRIVACY_SERVICE_UNVERIFIED",
                    "executionState" to if (unknown) "UNKNOWN_OUTCOME" else "PRECHECK_REJECTED",
                    "dispatched" to unknown
                )
            )
            val result = requireNotNull(gatewayAppDiagnosticProjection(AppLogEntry(1000, "E", "Tool", message)))
            val attributes = result["bounded_attributes"] as JsonObject
            assertEquals(JsonPrimitive(if (unknown) "DISPATCH_UNCERTAIN" else "PRIVACY_SERVICE_UNVERIFIED"), result["error_code"])
            assertEquals(JsonPrimitive(if (unknown) "unknown" else "not_dispatched"), result["dispatch_state"])
            assertEquals(JsonPrimitive(if (unknown) "reconcile" else "manual"), result["retry_class"])
            assertEquals(JsonPrimitive("PRIVACY_SERVICE_UNVERIFIED"), attributes["reason_code"])
            assertEquals(JsonPrimitive(if (unknown) "reconcile" else "recheck_prerequisite"), attributes["recovery_action"])
            assertEquals(JsonPrimitive(if (unknown) "original_outcome_reconciled" else "dependency_changed"), attributes["recovery_condition"])
        }
    }

    @Test fun unknownCausesRetainTheRecorderAndLocalClassificationWithoutExportingFreeText() {
        val spoofed = mapOf("source_recorder_family" to "gateway", "source_error_class" to "fatal", "detail" to "private-event-body")
        val malformed = structuredDiagnosticEventMessage("OBSERVED", spoofed + ("outcome" to "invalid_response"))
        val research = requireNotNull(gatewayAppDiagnosticProjection(AppLogEntry(1000, "E", "ResearchExamine", malformed)))
        val attrs = research["bounded_attributes"] as JsonObject
        assertEquals(JsonPrimitive("UNKNOWN_CAUSE"), research["error_code"])
        assertEquals(JsonPrimitive("unknown"), research["dispatch_state"])
        assertEquals(JsonPrimitive("research"), attrs["source_recorder_family"])
        assertEquals(JsonPrimitive("research_schema"), attrs["source_error_class"])
        assertEquals(JsonPrimitive("unclassified_error"), attrs["source_event_type"])
        assertFalse(research.toString().contains("private-event-body"))
        val message = structuredDiagnosticEventMessage("OBSERVED", spoofed)
        val unknown = requireNotNull(gatewayAppDiagnosticProjection(AppLogEntry(1000, "E", "private-recorder", message)))
        val fallback = unknown["bounded_attributes"] as JsonObject
        assertEquals(JsonPrimitive("other"), fallback["source_recorder_family"])
        assertEquals(JsonPrimitive("error"), fallback["source_error_class"])
        assertFalse(unknown.toString().contains("private-recorder"))
        val healthy = requireNotNull(gatewayAppDiagnosticProjection(AppLogEntry(1000, "I", "Agent", message)))["bounded_attributes"] as JsonObject
        assertNull(healthy["source_recorder_family"])
        assertNull(healthy["source_error_class"])
    }

    @Test fun rejectedToolProjectionRetainsBoundedIdentityAndProofWithoutArgumentValues() {
        val message = structuredDiagnosticEventMessage(
            "Finished",
            mapOf(
                "runId" to run,
                "callId" to invocation,
                "tool" to "github_issues",
                "action" to "list",
                "executionState" to "PRECHECK_REJECTED",
                "dispatched" to false,
                "errorCode" to "INVALID_ARGUMENTS",
                "argumentShapeHash" to "c".repeat(64),
                "arguments" to "private-argument-canary"
            )
        )
        val result = requireNotNull(gatewayAppDiagnosticProjection(AppLogEntry(1, "E", "Tool", message)))
        val attributes = result["bounded_attributes"] as JsonObject
        assertEquals(JsonPrimitive(invocation), result["tool_call_id"])
        assertEquals(JsonPrimitive("precheck_rejected"), attributes["execution_origin"])
        assertEquals(JsonPrimitive("tool_precheck"), attributes["source_event_type"])
        assertEquals(JsonPrimitive(GatewayDiagnosticEvent.digest("list")), attributes["tool_action_digest"])
        assertEquals(JsonPrimitive("c".repeat(64)), attributes["argument_shape_digest"])
        assertFalse(result.toString().contains("private-argument-canary"))
    }

    @Test fun healthyLifecycleAndToolEventsDoNotPrescribeFailureRecovery() {
        for (tag in listOf("Lifecycle", "GitHub", "Agent")) {
            val result = requireNotNull(gatewayAppDiagnosticProjection(AppLogEntry(1000, "I", tag, structuredDiagnosticEventMessage("OBSERVED", mapOf("runId" to run)))))
            assertEquals(JsonPrimitive("NONE"), result["error_code"])
            assertEquals(JsonPrimitive("none"), result["retry_class"])
            assertEquals(JsonPrimitive("none"), (result["bounded_attributes"] as JsonObject)["recovery_action"])
        }
    }

    @Test fun excludedCriterionDoesNotReportAnUntestedCapabilityAsATaskFailure() {
        val result = requireNotNull(gatewayAppDiagnosticProjection(AppLogEntry(1000, "I", "GitHub", structuredDiagnosticEventMessage("GITHUB_ACCEPTANCE_CRITERION", mapOf("criterion_state" to "NOT_TESTED", "criterion_required" to false, "criterion_blocker" to "OUT_OF_SCOPE")))))
        assertEquals(JsonPrimitive("NONE"), result["error_code"])
        val attributes = result["bounded_attributes"] as JsonObject
        assertEquals(JsonPrimitive("none"), attributes["recovery_action"])
        assertEquals(JsonPrimitive("OUT_OF_SCOPE"), attributes["criterion_blocker"])
    }

    @Test fun knownTerminalCausesAndObservedProgressRemainSpecific() {
        for ((reason, expected, recovery) in listOf(
            Triple("TRANSPORT_EOF", "TRANSPORT_EOF", "reconcile"),
            Triple("COMPLETION_REPAIR_BUDGET_EXHAUSTED", "COMPLETION_REPAIR_BUDGET_EXHAUSTED", "resume_checkpoint"),
            Triple("INCOMPLETE", "TASK_INCOMPLETE", "resume_checkpoint")
        )) {
            val result = requireNotNull(gatewayAppDiagnosticProjection(AppLogEntry(1000, "E", "AgentRun", structuredDiagnosticEventMessage("RUN_TERMINAL", mapOf("runId" to run, "status" to "FAILED", "errorCode" to reason, "lastObservedStage" to "REASONING")))))
            val attributes = result["bounded_attributes"] as JsonObject
            assertEquals(JsonPrimitive(expected), result["error_code"])
            assertEquals(JsonPrimitive(reason), attributes["reason_code"])
            assertEquals(JsonPrimitive(recovery), attributes["recovery_action"])
            assertEquals(JsonPrimitive("REASONING"), attributes["last_observed_stage"])
        }
    }

    @Test fun criterionEventsKeepBoundedProofIdentityWithoutPrivateCriteria() {
        val result = requireNotNull(
            gatewayAppDiagnosticProjection(
                AppLogEntry(
                    1000,
                    "I",
                    "GitHub",
                    structuredDiagnosticEventMessage(
                        "GITHUB_ACCEPTANCE_CRITERION",
                        mapOf(
                            "runId" to run,
                            "criterion_id_digest" to "a".repeat(64),
                            "criterion_category" to "DEVICE_EXECUTION",
                            "criterion_state" to "NOT_TESTED",
                            "criterion_blocker" to "DEVICE_EXECUTION_NOT_OBSERVED",
                            "criterion_required" to true,
                            "criterion_ordinal" to 2,
                            "evidence_receipt_count" to 0,
                            "criterion_text" to "private-criterion-canary"
                        )
                    )
                )
            )
        )
        val attributes = result["bounded_attributes"] as JsonObject
        assertEquals(JsonPrimitive("terminal_delivery"), result["stage"])
        assertEquals(JsonPrimitive("a".repeat(64)), attributes["criterion_id_digest"])
        assertEquals(JsonPrimitive("NOT_TESTED"), attributes["criterion_state"])
        assertEquals(JsonPrimitive("DEVICE_EXECUTION_NOT_OBSERVED"), attributes["criterion_blocker"])
        assertEquals(JsonPrimitive(true), attributes["criterion_required"])
        assertEquals(JsonPrimitive(0), attributes["evidence_receipt_count"])
        assertFalse(result.toString().contains("private-criterion-canary"))
    }

    @Test fun engineeringAdmissionFailureRetainsCauseDependencyAndRecovery() {
        val result = requireNotNull(
            gatewayAppDiagnosticProjection(
                AppLogEntry(
                    1000,
                    "E",
                    "AgentRun",
                    structuredDiagnosticEventMessage(
                        "RUN_TERMINAL",
                        mapOf(
                            "runId" to run,
                            "status" to "BLOCKED",
                            "executionState" to "PRECHECK_REJECTED",
                            "dispatched" to false,
                            "errorCode" to "ACCEPTANCE_PRODUCER_REQUIRED",
                            "failedStage" to "admission"
                        )
                    )
                )
            )
        )
        val attributes = result["bounded_attributes"] as kotlinx.serialization.json.JsonObject
        assertEquals(JsonPrimitive("ACCEPTANCE_PRODUCER_REQUIRED"), attributes["reason_code"])
        assertEquals(JsonPrimitive("admission"), attributes["failed_stage"])
        assertEquals(JsonPrimitive("acceptance"), attributes["dependency"])
        assertEquals(JsonPrimitive("repair_configuration"), attributes["recovery_action"])
    }

    @Test fun typedTerminalRetainsCorrelationAndUnsentEvidenceWithoutForwardingMessages() {
        val entry = AppLogEntry(
            1000,
            "E",
            "AgentRun",
            structuredDiagnosticEventMessage(
                "RUN_TERMINAL",
                mapOf(
                    "runId" to run,
                    "invocationId" to invocation,
                    "causalIncidentId" to null,
                    "status" to "FAILED",
                    "executionState" to "PRECHECK_REJECTED",
                    "dispatched" to false,
                    "errorCode" to "GATEWAY_ENGINEERING_GITHUB_REQUIRED",
                    "failureMessage" to "private-failure-canary",
                    "retryable" to false
                )
            )
        )
        val result = requireNotNull(gatewayAppDiagnosticProjection(entry))
        assertEquals(JsonPrimitive(run), result["run_id"])
        assertEquals(JsonPrimitive(invocation), result["incident_id"])
        assertEquals(JsonPrimitive("terminal_delivery"), result["stage"])
        assertEquals(JsonPrimitive("PRECHECK_REJECTED"), result["error_code"])
        assertEquals(JsonPrimitive("not_dispatched"), result["dispatch_state"])
        assertEquals(JsonPrimitive("manual"), result["retry_class"])
        assertFalse(result.toString().contains("private-failure-canary"))
        assertFalse(result.containsKey("failureMessage"))
    }

    @Test fun earlierUnknownOutcomeRequiresReconciliationEvenWithLaterUnsentAttempt() {
        val originalIncident = "33333333-3333-3333-3333-333333333333"
        val originalOperation = "44444444-4444-4444-4444-444444444444"
        val result = requireNotNull(
            gatewayAppDiagnosticProjection(
                AppLogEntry(
                    1000,
                    "E",
                    "AgentRun",
                    structuredDiagnosticEventMessage(
                        "RUN_TERMINAL",
                        mapOf(
                            "runId" to run,
                            "callId" to invocation,
                            "causalIncidentId" to originalIncident,
                            "operationId" to originalOperation,
                            "status" to "FAILED",
                            "executionState" to "UNKNOWN_OUTCOME",
                            "dispatched" to false,
                            "errorCode" to "PRIVACY_SERVICE_UNVERIFIED",
                            "retryable" to false
                        )
                    )
                )
            )
        )
        assertEquals(JsonPrimitive("DISPATCH_UNCERTAIN"), result["error_code"])
        assertEquals(JsonPrimitive("unknown"), result["dispatch_state"])
        assertEquals(JsonPrimitive("reconcile"), result["retry_class"])
        assertEquals(JsonPrimitive(run), result["run_id"])
        assertEquals(JsonPrimitive(invocation), result["tool_call_id"])
        assertEquals(JsonPrimitive(originalIncident), result["incident_id"])
        assertEquals(JsonPrimitive(originalOperation), result["operation_id"])
        val attributes = result["bounded_attributes"] as JsonObject
        assertEquals(JsonPrimitive("PRIVACY_SERVICE_UNVERIFIED"), attributes["reason_code"])
        assertEquals(JsonPrimitive("reconcile"), attributes["recovery_action"])
        assertEquals(JsonPrimitive("original_outcome_reconciled"), attributes["recovery_condition"])
        assertEquals(JsonPrimitive(false), attributes["retryable"])
    }

    @Test fun legacyEventsStillProjectButExchangeAndOpaqueIdentifiersStayLocal() {
        val result = requireNotNull(gatewayAppDiagnosticProjection(AppLogEntry(1000, "E", "Tool", "FAILED · runId=$run · operationId=private-label-canary · errorCode=NETWORK_DNS")))
        assertEquals(JsonPrimitive(run), result["run_id"])
        assertFalse(result.toString().contains("private-label-canary"))
        assertNull(gatewayAppDiagnosticProjection(AppLogEntry(1000, "W", "Gateway", "CLIENT_EXCHANGE · status=503")))
        assertNull(gatewayAppDiagnosticProjection(AppLogEntry(1000, "W", "Gateway", structuredDiagnosticEventMessage("CLIENT_EXCHANGE", mapOf("status" to 503)))))
    }
}
