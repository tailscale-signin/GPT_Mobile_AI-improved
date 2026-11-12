package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayResponseMetadata
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Project the owning stream's observations. This does not read another invocation or request data. */
internal object ProviderFailureReport {
    fun fields(
        runId: String,
        failure: ProviderEvent.Failed,
        receipt: ProviderFailureReceipt,
        configured: ProviderEvent.RequestConfigured? = null,
        usage: ProviderEvent.Usage? = null,
        visibleBytes: Long? = null,
        reasoningBytes: Long? = null,
        metadata: GatewayResponseMetadata? = null
    ): JsonObject = buildJsonObject {
        put("diagnostic_area", "PROVIDER")
        put("diagnostic_capture_mode", "INCIDENT")
        IssueReportContext.identifierHash("run", runId)?.let { put("run_id_hash", it) }
        IssueReportContext.identifierHash("invocation", receipt.invocationId)?.let { put("invocation_id_hash", it) }
        put("profile_hash", receipt.profileHash)
        put("model_hash", receipt.modelHash)
        put("response_format", receipt.responseFormat.name)
        put("finish_reason", receipt.finishReason.name)
        configured?.configuredProfileOutputTokens?.bounded()?.let { put("configured_output_limit", it) }
        (configured?.serializedOutputTokens ?: configured?.effectiveOutputTokens)?.bounded()?.let { put("effective_output_limit", it) }
        // Usage without the owning invocation cannot supplement a different failure.
        usage?.takeIf { it.invocationId == receipt.invocationId }?.inputTokens?.bounded()?.let { put("input_tokens", it) }
        receipt.outputTokens?.bounded()?.let { put("output_tokens", it) }
        receipt.reasoningTokens?.bounded()?.let { put("reasoning_tokens", it) }
        receipt.outputTokenSource?.let { put("usage_source", it) }
        put("transport_complete", receipt.transportComplete)
        receipt.generationCompleted?.let { put("generation_completed", it) }
        receipt.requestReplayAllowed?.let { put("request_replay_allowed", it) }
        receipt.logicalContinuationAllowed?.let { put("logical_continuation_allowed", it) }
        IssueReportContext.identifierHash("provider-error-code", failure.errorCode)?.let { put("original_error_code_hash", it) }
        IssueReportContext.identifierHash("server-request", receipt.serverRequestId ?: metadata?.takeIf { it.transportVerified }?.requestId)?.let { put("server_request_id_hash", it) }
        IssueReportContext.identifierHash("generation", receipt.generationId)?.let { put("generation_id_hash", it) }
        val jobId = (failure.failureDetails?.get("job_id") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: metadata?.takeIf { it.transportVerified }?.jobId
        IssueReportContext.identifierHash("job", jobId)?.let { put("job_id_hash", it) }
        val backend = receipt.gatewayAttemptEvidence
        listOf("attempts" to "backend_attempts", "completed_attempts" to "backend_completed_attempts", "failed_attempt" to "backend_failed_attempt")
            .forEach { (source, target) -> backend?.number(source)?.let { put(target, it) } }
        val lastAttempt = (backend?.get("attempt_receipts") as? JsonArray)?.lastOrNull() as? JsonObject
        if (lastAttempt != null) {
            put("output_byte_scope", "LAST_BACKEND_ATTEMPT")
            lastAttempt.number("visible_output_bytes")?.let { put("visible_output_bytes", it) }
            lastAttempt.number("reasoning_output_bytes")?.let { put("reasoning_output_bytes", it) }
            lastAttempt.number("output_allowance")?.let { put("backend_output_limit", it) }
            lastAttempt["finish_reason"]?.let { put("backend_finish_reason", it) }
            lastAttempt["status"]?.let { put("backend_status", it) }
            lastAttempt.number("terminal_status_code")?.takeIf { it in 100..599 }?.let { put("backend_terminal_status", it) }
            IssueReportContext.identifierHash("backend-error-code", (lastAttempt["terminal_error_code"] as? JsonPrimitive)?.contentOrNull)
                ?.let { put("backend_terminal_code_hash", it) }
        } else if (visibleBytes != null || reasoningBytes != null) {
            put("output_byte_scope", "CLIENT_STREAM")
            visibleBytes?.takeIf { it in 0..MAX_COUNT.toLong() }?.let { put("visible_output_bytes", it) }
            reasoningBytes?.takeIf { it in 0..MAX_COUNT.toLong() }?.let { put("reasoning_output_bytes", it) }
        }
    }

    private const val MAX_COUNT = 16_000_000
    private fun Int.bounded(): Int? = takeIf { it in 0..MAX_COUNT }
    private fun JsonObject.number(key: String): Int? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.bounded()
}
