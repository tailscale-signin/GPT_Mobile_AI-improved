package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.accounting.ModelInvocation
import dev.chungjungsoo.gptmobile.data.github.GatewayExecutionRejected
import dev.chungjungsoo.gptmobile.data.network.safeProviderRequestIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Failure delivery and accounting must describe the same invocation, never the latest unrelated call. */
internal object AgentFailureEvidence {
    fun reconcile(failure: ProfileFailureRecord?, runId: String, invocation: ModelInvocation?): ProfileFailureRecord? {
        if (failure == null || invocation == null || failure.invocationId == null || failure.invocationId != invocation.id || invocation.parentRunId != runId) return failure
        val raw = invocation.usageEvidenceJson?.takeIf { it.length <= 1_048_576 } ?: return failure
        val evidence = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return failure
        if (evidence.text("invocation_id") != invocation.id) return failure
        val recorded = (evidence["failure"] as? JsonObject)?.let {
            runCatching { Json.decodeFromJsonElement(ProfileFailureRecord.serializer(), it) }.getOrNull()
        }?.takeIf { it.invocationId == null || it.invocationId == invocation.id }
        val response = (evidence["response_parsing"] as? JsonObject)?.takeIf { it.text("measurement_scope") == "android_http_attempt" }
        val responded = (response?.get("http_status") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull in 100..599
        val accepted = RemoteRequestAcceptance.entries.firstOrNull { it.name == response?.text("remote_acceptance") } ?: RemoteRequestAcceptance.UNKNOWN
        // A later rejected recovery is not evidence that an earlier uncertain
        // mutation was unsent. Existing explicit facts always keep their scope.
        val dispatched = failure.dispatched ?: recorded?.dispatched ?: true.takeIf { responded }
        return failure.copy(
            dispatched = dispatched,
            remoteAcceptance = failure.remoteAcceptance.takeUnless { it == RemoteRequestAcceptance.UNKNOWN }
                ?: recorded?.remoteAcceptance?.takeUnless { it == RemoteRequestAcceptance.UNKNOWN }
                ?: accepted,
            phase = failure.phase.takeUnless { it.isBlank() || it == "stage_not_recorded" }
                ?: recorded?.phase?.takeUnless { it.isBlank() || it == "stage_not_recorded" }
                ?: failure.phase,
            serverRequestId = failure.serverRequestId ?: recorded?.serverRequestId ?: safeProviderRequestIdentity(response?.text("server_request_id")),
            generationCompleted = failure.generationCompleted ?: recorded?.generationCompleted,
            requestReplayAllowed = if (failure.requestReplayAllowed == false || recorded?.requestReplayAllowed == false) false else failure.requestReplayAllowed,
            retryAllowed = failure.retryAllowed && recorded?.retryAllowed != false,
            dispatchState = failure.dispatchState ?: recorded?.dispatchState ?: "DISPATCHED".takeIf { dispatched == true }
        )
    }

    /** A typed binding rejection preserves server facts without logging its response body. */
    fun exception(error: Throwable, partialOutput: Boolean = false): ProviderEvent.Failed? {
        val rejected = error as? GatewayExecutionRejected ?: return null
        val unknown = rejected.commandOutcomeUnknown || rejected.code == "EXECUTION_OPERATION_UNKNOWN_OUTCOME"
        val inference = rejected.inferenceAttempted
        return ProviderEvent.Failed(
            message = rejected.message.orEmpty(),
            errorCode = if (unknown) GatewayOutcomeRecovery.ERROR_CODE else rejected.code,
            transportComplete = !unknown,
            taskOutcome = if (unknown) "PARTIAL" else "BLOCKED",
            failureDetails = buildJsonObject {
                put("failed_stage", if (unknown) "gateway_dispatch" else rejected.failedStage)
                put("http_status", rejected.httpStatus)
                put("attempt_dispatch_state", "REJECTED")
                put("retryable", !unknown && rejected.retryable == true)
                put("partial_output_preserved", partialOutput)
                inference?.let { put("inference_attempted", it) }
                if (unknown) {
                    put("requires_reconciliation", true)
                    put("request_replay_allowed", false)
                    put("remote_acceptance", "UNKNOWN")
                } else if (!partialOutput && inference != null) {
                    put("dispatched", inference)
                    put("dispatch_state", if (inference) "FAILED" else "NOT_DISPATCHED")
                    put("remote_acceptance", if (inference) "UNKNOWN" else "NO")
                }
            }
        )
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
