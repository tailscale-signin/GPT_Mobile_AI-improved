package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayResponseMetadata
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
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
        for (key in listOf("reasoning_capability_age_ms", "reasoning_model_state", "admission_prompt_tokens", "admission_safety_tokens", "admission_output_tokens", "admission_capacity_tokens", "admission_dominant_tokens", "admission_measurement", "admission_dominant_component", "reasoning_recovery_action", "reasoning_requested_mode", "reasoning_admission_basis", "reasoning_route_kind", "reasoning_control_state", "reasoning_template_hash")) {
            failure.failureDetails?.get(key)?.let { put(key, it) }
        }
        if (failure.errorCode.equals("CONVERSATION_CONFLICT", ignoreCase = true)) {
            val details = failure.failureDetails.orEmpty()
            val kind = (details["active_job_kind"] as? JsonPrimitive)?.contentOrNull
            kind?.takeIf { it in setOf("generation", "engineering") }?.let { put("conflict_active_job_kind", it) }
            (details["conversation_revision"] as? JsonPrimitive)?.takeUnless { it.isString }
                ?.longOrNull?.takeIf { it in 0..16_000_000L }?.let { put("conflict_conversation_revision", it) }
            (details["pending_client_tool_count"] as? JsonPrimitive)?.takeUnless { it.isString }
                ?.intOrNull?.takeIf { it in 0..128 }?.let { put("conflict_pending_tool_count", it) }
            val conversationId = (details["conversation_id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            IssueReportContext.identifierHash("conversation", conversationId)?.let { put("conflict_conversation_hash", it) }
            val provenance = (details["active_job_provenance"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            put("conflict_active_job_provenance", provenance?.takeIf { it == "CONVERSATION_ACTIVE_JOB" } ?: "UNREPORTED")
            val pendingState = (details["pending_client_tool_state"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            put("conflict_pending_tool_state", pendingState?.takeIf { it in setOf("IDENTIFIED", "MISSING", "EMPTY", "INVALID", "UNAVAILABLE") } ?: "NOT_REPORTED")
            (details["pending_client_tool_ids_hash"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let { put("conflict_pending_tool_ids_hash", it) }
            (details["client_tool_handoff_sequence"] as? JsonPrimitive)?.takeUnless { it.isString }
                ?.longOrNull?.takeIf { it in 1..16_000_000L }?.let { put("conflict_handoff_sequence", it) }
            val activeJobId = (details["active_job_id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
            val streamJobId = metadata?.takeIf { it.transportVerified }?.jobId?.takeIf { it.isNotBlank() }
            // Server conversation ownership does not establish ownership by this
            // Android run. Only verified stream metadata can establish a match.
            put(
                "conflict_stream_job_relation",
                when {
                    activeJobId == null -> "ACTIVE_JOB_NOT_REPORTED"
                    streamJobId == null -> "NO_VERIFIED_STREAM_JOB"
                    activeJobId == streamJobId -> "MATCHES_VERIFIED_STREAM_JOB"
                    else -> "DIFFERS_VERIFIED_STREAM_JOB"
                }
            )
        }
        if (receipt.errorCode in dev.chungjungsoo.gptmobile.data.context.RequestCapacityException.CODES) {
            for (key in IssueReportDiagnostics.capacityEvidenceFields + listOf("admission_tool_schema_tokens", "admission_tool_result_reserve_tokens", "admission_attachment_reserve_tokens")) {
                failure.failureDetails?.get(key)?.let { put(key, it) }
            }
        }
        put("diagnostic_capture_mode", "INCIDENT")
        IssueReportContext.identifierHash("run", runId)?.let { put("run_id_hash", it) }
        IssueReportContext.identifierHash("invocation", receipt.invocationId)?.let { put("invocation_id_hash", it) }
        put("profile_hash", receipt.profileHash)
        put("model_hash", receipt.modelHash)
        put("response_format", receipt.responseFormat.name)
        put("provider_failure_stage", receipt.stage.name)
        put("provider_failure_domain", receipt.failureDomain.name)
        put("provider_failure_code", receipt.errorCode)
        if (receipt.errorCode == "GITHUB_CIRCUIT_OPEN") {
            failure.failureDetails?.get("github_cooldown_stop")?.let { put("github_cooldown_stop", it) }
        }
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
        if (receipt.invocationId != null && "causal_id" !in failure.failureDetails.orEmpty()) {
            val phase = (failure.failureDetails?.get("failed_stage") as? JsonPrimitive)?.contentOrNull
            if (phase in setOf("delivery", "tls", "connection", "dns", "transport", "provider_admission", "conversation_admission")) {
                put("causal_id", receipt.invocationId)
                put("causal_provenance", "MODEL_INVOCATION")
            }
        }
        if (failure.errorCode == "tool_use_failed") put("provider_tool_rejection", "TOOL_USE_FAILED")
        metadata?.takeIf { it.transportVerified }?.let {
            if (it.terminalStatus in setOf("completed", "failed", "cancelled", "canceled", "interrupted")) {
                put("gateway_terminal_status", it.terminalStatus)
            }
            if (it.resultQuality in setOf("complete", "partial")) put("gateway_result_quality", it.resultQuality)
        }
        val jobId = (failure.failureDetails?.get("job_id") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: metadata?.takeIf { it.transportVerified }?.jobId
        IssueReportContext.identifierHash("job", jobId)?.let { put("job_id_hash", it) }
        failure.providerLimit?.takeIf {
            dev.chungjungsoo.gptmobile.data.research.researchHash(it.profileUid) == receipt.profileHash &&
                dev.chungjungsoo.gptmobile.data.research.researchHash(it.model) == receipt.modelHash
        }?.let { limit ->
            // This is the decision at failure time, not proof that a worker resumed.
            put("provider_wait_eligible", limit.mayWait)
            put("provider_checkpoint_present", limit.gitHubCheckpoint != null)
            put("provider_checkpoint_continuation_allowed", limit.checkpointContinuationAllowed)
            limit.consecutiveRejections.bounded()?.let { put("provider_consecutive_rejections", it) }
        }
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
