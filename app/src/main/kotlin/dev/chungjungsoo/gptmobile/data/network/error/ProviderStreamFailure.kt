package dev.chungjungsoo.gptmobile.data.network.error

import dev.chungjungsoo.gptmobile.data.diagnostics.redactLogMessage
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseErrorEvent
import dev.chungjungsoo.gptmobile.data.network.safeProviderRequestIdentity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** A successful HTTP handshake does not establish successful model generation. */
internal object ProviderStreamFailure {
    fun event(
        code: String?,
        message: String,
        type: String? = null,
        details: JsonObject? = null,
        httpStatus: Int? = null,
        serverRequestId: String? = null,
        responsePayloadObserved: Boolean = false,
        streamEventType: String? = null,
        providerResponseId: String? = null,
        metadata: JsonObject? = null,
        retryAfterMillis: Long? = null,
        knownNotAccepted: Boolean = false
    ): ResponseErrorEvent {
        // Native structured projection can refine a generic backend code into a
        // contract or a prior unknown outcome. Neither may become a new retry.
        val rawApplicationCode = dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.retainedProviderCode(code)
        val structuredCode = dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.retainedProviderCode((details?.get("code") as? JsonPrimitive)?.contentOrNull)
        val unknownOutcomeCode = listOfNotNull(structuredCode, rawApplicationCode).firstOrNull(::isReconciliationCode)
            ?: "GATEWAY_OUTCOME_UNKNOWN".takeIf { details?.get("requires_reconciliation") == JsonPrimitive(true) }
        val applicationCode = unknownOutcomeCode ?: structuredCode ?: rawApplicationCode
        val applicationFailure = applicationCode?.let { dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.fromProvider("", it) }
        val failure = ProviderFailure.fromValues(httpStatus ?: 200, code, message.takeIf { applicationCode == null }, type.takeIf { applicationCode == null }, metadata = metadata.takeIf { applicationCode == null })
        val retryable = unknownOutcomeCode == null && (applicationFailure?.retryAllowed ?: failure.retryable) && details?.get("retryable") != JsonPrimitive(false)
        val category = applicationCode ?: when (failure.kind) {
            ProviderFailureKind.BUDGET -> "BILLING_UNAVAILABLE"
            ProviderFailureKind.AUTHENTICATION -> "AUTHENTICATION_FAILED"
            ProviderFailureKind.PERMISSION, ProviderFailureKind.FORBIDDEN_UNKNOWN -> "ACCESS_FORBIDDEN"
            ProviderFailureKind.CONTENT_POLICY -> "PROVIDER_CONTENT_POLICY"
            ProviderFailureKind.RATE_LIMIT -> "429"
            ProviderFailureKind.UNAVAILABLE -> "PROVIDER_UNAVAILABLE"
            ProviderFailureKind.UNKNOWN -> "PROVIDER_GENERATION_FAILED"
        }
        val readable = if (applicationCode != null) {
            redactLogMessage(message).replace('\n', ' ').replace('\r', ' ').take(1600)
        } else {
            when (failure.kind) {
                ProviderFailureKind.BUDGET -> requireNotNull(failure.budget).message()
                ProviderFailureKind.AUTHENTICATION -> "The provider could not authenticate the generation. Update the platform connection in Settings → Platforms, then resume the saved work."
                ProviderFailureKind.PERMISSION, ProviderFailureKind.FORBIDDEN_UNKNOWN -> "The provider denied access to this generation. Check the selected profile's access before resuming."
                ProviderFailureKind.CONTENT_POLICY -> "The provider declined this generation under its content policy. The saved evidence is retained."
                ProviderFailureKind.RATE_LIMIT -> "The provider rate-limited the generation. Saved evidence is retained for the provider cooldown."
                ProviderFailureKind.UNAVAILABLE -> "The provider failed while processing the generation. Saved output and tool results are retained for bounded continuation."
                ProviderFailureKind.UNKNOWN -> redactLogMessage(message).replace('\n', ' ').replace('\r', ' ').take(1600)
            }
        }
        val fields = details.orEmpty().toMutableMap()
        // Preserve stricter reconciliation and explicit backend-stage observations.
        fields["code"] = JsonPrimitive(category)
        fields.putIfAbsent("failed_stage", JsonPrimitive(applicationFailure?.phase?.takeUnless { it == "stage_not_recorded" } ?: "response_stream"))
        fields["provider_failure_kind"] = JsonPrimitive(failure.kind.name)
        fields["generation_completed"] = JsonPrimitive(false)
        fields.putIfAbsent("dispatched", JsonPrimitive(true))
        fields.putIfAbsent(
            "remote_acceptance",
            JsonPrimitive(
                if (responsePayloadObserved) {
                    "YES"
                } else if (knownNotAccepted) {
                    "NO"
                } else {
                    "UNKNOWN"
                }
            )
        )
        fields.putIfAbsent("dispatch_state", JsonPrimitive("FAILED"))
        fields["retryable"] = JsonPrimitive(retryable)
        fields["request_replay_allowed"] = JsonPrimitive(retryable && !responsePayloadObserved && fields["request_replay_allowed"] != JsonPrimitive(false) && fields["requires_reconciliation"] != JsonPrimitive(true))
        fields.putIfAbsent("next_action", JsonPrimitive(if (retryable) "RESUME_UNFINISHED_WORK" else "RETRY_AFTER_CONFIGURATION_CHANGE"))
        failure.budget?.let { budget ->
            fields.putAll(budget.fields())
            val delay = retryAfterMillis?.takeIf { it in 1L..86_400_000L }
            val mode = when {
                unknownOutcomeCode != null || responsePayloadObserved || details?.get("remote_acceptance") in setOf(JsonPrimitive("YES"), JsonPrimitive("ACCEPTED")) || !knownNotAccepted && budget.kind == ProviderBudgetKind.IN_FLIGHT_BUDGET -> "REVIEW_SAVED_WORK"
                budget.kind != ProviderBudgetKind.IN_FLIGHT_BUDGET -> "AFTER_CONFIGURATION_CHANGE"
                delay == null -> "PROVIDER_TIMING_UNAVAILABLE"
                else -> "MANUAL_AFTER_PROVIDER_DELAY"
            }
            fields["provider_budget_retry"] = JsonPrimitive(mode)
            // This timing is for an explicit retry of saved work. It never grants
            // wire replay, credential rotation, or an automatic change in spend.
            if (mode == "MANUAL_AFTER_PROVIDER_DELAY") {
                fields["retry_after_ms"] = JsonPrimitive(requireNotNull(delay))
                fields["retry_at_ms"] = JsonPrimitive(dev.chungjungsoo.gptmobile.data.network.ProviderRetryPolicy.deadlineMillis(System.currentTimeMillis(), delay))
                fields["next_action"] = JsonPrimitive("RESUME_UNFINISHED_WORK")
            }
        }
        if (unknownOutcomeCode != null) {
            fields["requires_reconciliation"] = JsonPrimitive(true)
            fields["dispatched"] = JsonPrimitive(true)
            fields["dispatch_state"] = JsonPrimitive("UNKNOWN")
            if (fields["remote_acceptance"] !in setOf(JsonPrimitive("YES"), JsonPrimitive("ACCEPTED"))) fields["remote_acceptance"] = JsonPrimitive("UNKNOWN")
            fields["next_action"] = JsonPrimitive("RECONCILE_REMOTE_OUTCOME")
        }
        httpStatus?.takeIf { it in 100..599 }?.let { fields["http_status"] = JsonPrimitive(it) }
        safeProviderRequestIdentity(serverRequestId)?.let { fields["server_request_id"] = JsonPrimitive(it) }
        streamEventType?.takeIf { it in EVENT_TYPES }?.let { fields["stream_event_type"] = JsonPrimitive(it) }
        safeProviderRequestIdentity(providerResponseId)?.let { fields["provider_response_id"] = JsonPrimitive(it) }
        // Classification may use a documented error type when the raw code is
        // unknown. Keep the two upstream observations separate for correlation.
        fields.remove("upstream_error_code")
        code?.lowercase(java.util.Locale.ROOT)?.takeIf { it in ProviderFailure.SAFE_CODES }
            ?.let { fields["upstream_error_code"] = JsonPrimitive(it) }
        // Unknown codes can contain arbitrary upstream text. Keep correlation
        // through the existing bounded identifier hash, never publish that text.
        code?.takeUnless { it.lowercase(java.util.Locale.ROOT) in ProviderFailure.SAFE_CODES }
            ?.let { dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext.identifierHash("provider-error-code", it) }
            ?.let { fields["upstream_error_code_hash"] = JsonPrimitive(it) }
        fields.remove("upstream_error_type")
        type?.lowercase(java.util.Locale.ROOT)?.takeIf { it in ProviderFailure.SAFE_CODES }?.let { fields["upstream_error_type"] = JsonPrimitive(it) }
        return ResponseErrorEvent(code = category, message = readable, failureDetails = JsonObject(fields))
    }

    private val reconciliationCodes = setOf("GATEWAY_OUTCOME_UNKNOWN", "EXECUTION_OPERATION_UNKNOWN_OUTCOME", "TOOL_OUTCOME_UNKNOWN", "TIMEOUT_OUTCOME_UNKNOWN", "DISPATCH_OUTCOME_UNKNOWN", "TOOL_RESULT_ID_MISMATCH", "RECONCILIATION_REQUIRED", "GATEWAY_TOOL_OUTCOME_UNKNOWN", "REMOTE_OUTCOME_UNKNOWN")

    /** Admission and semantic projection must agree on the same closed uncertainty codes. */
    internal fun isReconciliationCode(code: String?): Boolean =
        dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.retainedProviderCode(code) in reconciliationCodes

    internal val EVENT_TYPES = setOf("error", "response.failed")

    /** Keep semantic correlation without retaining arbitrary upstream error text. */
    fun retained(details: JsonObject?): JsonObject? {
        val kind = (details?.get("provider_failure_kind") as? JsonPrimitive)?.contentOrNull
            ?.takeIf { value -> ProviderFailureKind.entries.any { it.name == value } } ?: return null
        val fields = mutableMapOf("provider_failure_kind" to JsonPrimitive(kind))
        if (kind == "BUDGET") {
            val choices = mapOf(
                "provider_budget_kind" to ProviderBudgetKind.entries.mapTo(mutableSetOf()) { it.name },
                "provider_budget_limit_source" to ProviderBudgetEvidence.LIMIT_SOURCES,
                "provider_budget_reason" to ProviderBudgetEvidence.REASONS,
                "provider_budget_retry" to ProviderBudgetEvidence.RETRY_MODES
            )
            for ((key, values) in choices) {
                (details[key] as? JsonPrimitive)?.takeIf { it.isString && it.content in values }?.let { fields[key] = it }
            }
            if (fields["provider_budget_kind"] == JsonPrimitive("IN_FLIGHT_BUDGET") && fields["provider_budget_retry"] == JsonPrimitive("MANUAL_AFTER_PROVIDER_DELAY")) {
                (details["retry_after_ms"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in 1..86_400_000 }
                    ?.let { fields["retry_after_ms"] = JsonPrimitive(it) }
            }
        }
        for (key in listOf("upstream_error_code", "upstream_error_type")) {
            (details[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it in ProviderFailure.SAFE_CODES }
                ?.let { fields[key] = JsonPrimitive(it) }
        }
        (details["upstream_error_code_hash"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
            ?.let { fields["upstream_error_code_hash"] = JsonPrimitive(it) }
        (details["http_status"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in 100..599 }
            ?.let { fields["http_status"] = JsonPrimitive(it) }
        (details["provider_semantic_status"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it == 429 && kind == "RATE_LIMIT" }
            ?.let { fields["provider_semantic_status"] = JsonPrimitive(it) }
        (details["stream_event_type"] as? JsonPrimitive)?.contentOrNull?.takeIf { it in EVENT_TYPES }
            ?.let { fields["stream_event_type"] = JsonPrimitive(it) }
        // Response identities stay local; durable/public projections use the same
        // bounded correlation hash as request and invocation identities.
        val responseId = safeProviderRequestIdentity((details["provider_response_id"] as? JsonPrimitive)?.contentOrNull)
        val responseHash = dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext.identifierHash("provider-response", responseId)
            ?: (details["provider_response_id_hash"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        responseHash?.let { fields["provider_response_id_hash"] = JsonPrimitive(it) }
        return JsonObject(fields)
    }

    /** A validated upstream code outranks an inherited hash from an earlier observation. */
    fun upstreamCodeHash(details: JsonObject?): String? {
        val retained = retained(details) ?: return null
        val code = (retained["upstream_error_code"] as? JsonPrimitive)?.contentOrNull
        return dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext.identifierHash("provider-error-code", code)
            ?: (retained["upstream_error_code_hash"] as? JsonPrimitive)?.contentOrNull
    }
}
