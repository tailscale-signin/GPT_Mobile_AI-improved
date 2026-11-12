package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

private val missingDiagnosticValues = setOf("", "unknown", "null", "none", "not_recorded", "not_reported", "not reported", "not measured", "unavailable")
private val diagnosticIdentifier = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
private val modelInvocationLabel = Regex("^(?:Request|Configured|Segment|Finished|Failed) ([A-Za-z0-9][A-Za-z0-9_.-]{0,127})(?:[ :]|$)")
private val githubFailureCode = Regex("\\b(GITHUB_[A-Z0-9_]{2,80})\\b")
private val legacyToolName = Regex("^Started ([A-Za-z0-9][A-Za-z0-9_.:-]{0,127})(?: ·|$)")
private val legacyToolRun = Regex("(?:^| · )run ([A-Za-z0-9][A-Za-z0-9_.:-]{0,127})(?: ·|$)")
private val legacyToolCall = Regex("(?:^| · )call ([A-Za-z0-9][A-Za-z0-9_.:-]{0,127})(?: ·|$)")

internal fun JsonObject.diagnosticText(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
    ?.takeUnless { it.lowercase(java.util.Locale.ROOT) in missingDiagnosticValues }

internal data class DiagnosticExecutionEvidence(val errorCode: String?, val stage: String?, val state: String?, val dispatched: Boolean?)

/** Normalize known receipt aliases; arbitrary prose and HTTP status alone are not dispatch proof. */
internal fun diagnosticExecutionEvidence(fields: JsonObject, eventName: String, eventMessage: String = ""): DiagnosticExecutionEvidence {
    val receipt = (fields["receipt"] as? JsonObject)?.takeIf { eventName == "PROVIDER_RECEIPT" }
    val state = fields.diagnosticText("dispatchState") ?: fields.diagnosticText("executionState")
        ?: fields.diagnosticText("dispatch_state") ?: fields.diagnosticText("attempt_dispatch_state")
        ?: fields.diagnosticText("dependentDispatch") ?: receipt?.diagnosticText("dispatchEvidence")
        ?: fields.diagnosticText("completionDisposition")?.takeIf { it == "PRECHECK_REJECTED" }
        ?: fields.diagnosticText("status")?.takeIf { it == "NOT_DISPATCHED" }
        ?: fields.diagnosticText("dispatched")?.takeIf { it == "NOT_DISPATCHED" }
    val explicitDispatch = (fields["dispatched"] as? JsonPrimitive)?.booleanOrNull
    val dispatched = when {
        state == "UNKNOWN_OUTCOME" -> explicitDispatch.takeIf { it == true }
        explicitDispatch != null -> explicitDispatch
        else -> when (state) {
            "NOT_DISPATCHED", "PRECHECK_REJECTED" -> false
            "DISPATCHED" -> true
            else -> null
        }
    }
    val failed = fields.diagnosticText("status") in setOf("FAILED", "PARTIAL", "BLOCKED", "EVIDENCE_MISSING", "NOT_DISPATCHED")
    val reasonIsFailure = eventName in setOf("INFERENCE_PRECHECK_REJECTED", "INVOCATION_FAILED") || eventName == "RUN_TERMINAL" && failed
    val code = fields.diagnosticText("errorCode") ?: fields.diagnosticText("error_code") ?: fields.diagnosticText("failureCode")
        ?: fields.diagnosticText("reason").takeIf { reasonIsFailure }
        ?: fields.diagnosticText("code").takeIf { eventName == "ADMISSION_READ" }
        ?: fields.diagnosticText("admissionCode").takeIf { eventName == "CLIENT_EXCHANGE" }
        ?: fields.diagnosticText("transport").takeIf { eventName == "CLIENT_EXCHANGE" }
        ?: receipt?.diagnosticText("reasonCode")
        // Older GitHub tool failures placed the stable error code between fields,
        // which made it part of truncatedAtSource instead of a field of its own.
        ?: githubFailureCode.find(eventMessage)?.groupValues?.get(1)
    return DiagnosticExecutionEvidence(
        errorCode = code,
        stage = fields.diagnosticText("failedStage") ?: fields.diagnosticText("failed_stage") ?: fields.diagnosticText("stage")
            ?: fields.diagnosticText("runtimeStage") ?: fields.diagnosticText("failurePhase") ?: receipt?.diagnosticText("failurePhase"),
        state = state ?: "NOT_DISPATCHED".takeIf { dispatched == false },
        dispatched = dispatched
    )
}

internal data class DiagnosticCorrelation(val runId: String?, val invocationId: String?)

/** A legacy model label is an invocation ID; the provider's remote request ID is not. */
internal fun diagnosticCorrelation(fields: JsonObject, eventMessage: String, tag: String?, payload: JsonObject? = null): DiagnosticCorrelation {
    fun id(value: String?) = value?.takeIf(diagnosticIdentifier::matches)
    val receipt = fields["receipt"] as? JsonObject
    val executionReceipt = fields["executionReceipt"] as? JsonObject
    return DiagnosticCorrelation(
        runId = id(
            fields.diagnosticText("runId") ?: fields.diagnosticText("run_id") ?: fields.diagnosticText("parentRunId") ?: fields.diagnosticText("run")
                ?: receipt?.diagnosticText("run_id") ?: receipt?.diagnosticText("runId")
                ?: executionReceipt?.diagnosticText("run_id") ?: executionReceipt?.diagnosticText("runId")
                ?: payload?.diagnosticText("run_id") ?: payload?.diagnosticText("runId")
                ?: legacyToolRun.find(eventMessage)?.groupValues?.get(1).takeIf { tag == "Tool" }
        ),
        invocationId = id(
            fields.diagnosticText("invocationId") ?: fields.diagnosticText("invocation_id") ?: receipt?.diagnosticText("invocationId")
                ?: receipt?.diagnosticText("invocation_id")
                ?: executionReceipt?.diagnosticText("invocation_id") ?: executionReceipt?.diagnosticText("invocationId")
                ?: modelInvocationLabel.find(eventMessage)?.groupValues?.get(1).takeIf { tag == "Model" }
        )
    )
}

internal data class DiagnosticToolIdentity(val name: String?, val callId: String?, val action: String?)

/** Lift bounded tool identity from current receipts and legacy lifecycle messages. */
internal fun diagnosticToolIdentity(fields: JsonObject, eventMessage: String, tag: String?, payload: JsonObject? = null): DiagnosticToolIdentity {
    fun id(value: String?) = value?.takeIf(diagnosticIdentifier::matches)
    val executionReceipt = fields["executionReceipt"] as? JsonObject
    val receiptTrace = executionReceipt?.get("tool_trace") as? JsonObject
    val payloadTrace = payload?.takeIf { it.diagnosticText("schema") == "github.trace.v1" }
    return DiagnosticToolIdentity(
        name = id(
            fields.diagnosticText("tool") ?: fields.diagnosticText("toolName") ?: fields.diagnosticText("modelToolName")
                ?: receiptTrace?.diagnosticText("tool") ?: executionReceipt?.diagnosticText("model_tool_name")
                ?: payloadTrace?.diagnosticText("tool")
                ?: legacyToolName.find(eventMessage)?.groupValues?.get(1).takeIf { tag == "Tool" }
        ),
        callId = id(
            fields.diagnosticText("toolCallId") ?: fields.diagnosticText("tool_call_id") ?: fields.diagnosticText("callId")
                ?: receiptTrace?.diagnosticText("call_id") ?: payloadTrace?.diagnosticText("call_id")
                ?: legacyToolCall.find(eventMessage)?.groupValues?.get(1).takeIf { tag == "Tool" }
        ),
        action = id(fields.diagnosticText("action") ?: receiptTrace?.diagnosticText("action") ?: payloadTrace?.diagnosticText("action"))
    )
}
