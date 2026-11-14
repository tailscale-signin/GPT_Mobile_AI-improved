package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.openai.response.ErrorDetail
import dev.chungjungsoo.gptmobile.data.network.error.ProviderFailure
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Retain known response/admission evidence before considering an exception a transport failure. */
internal fun providerExceptionDetails(error: Exception, partialOutput: Boolean, nowMillis: Long = System.currentTimeMillis()): ErrorDetail? {
    if (error is dev.chungjungsoo.gptmobile.data.agent.CombinedBudgetExceeded && !partialOutput) {
        return ErrorDetail(
            message = "The Combined turn could not admit another provider attempt within its saved limits.",
            type = "local_admission",
            code = "BUDGET_RESTRICTED",
            retryable = false,
            failureDetails = buildJsonObject {
                put("failed_stage", "provider_admission")
                put("attempt_dispatch_state", "NOT_DISPATCHED")
                put("request_replay_allowed", false)
                put("provider_local_budget_block", true)
            }
        )
    }
    if (error is dev.chungjungsoo.gptmobile.data.context.RequestCapacityException) {
        return ErrorDetail(
            message = requireNotNull(error.message),
            type = "local_admission",
            code = error.code,
            retryable = false,
            failureDetails = buildJsonObject {
                put("code", error.code)
                put("failed_stage", "provider_admission")
                put("attempt_dispatch_state", "NOT_DISPATCHED")
                put("retryable", false)
                put("request_replay_allowed", false)
                put("repair", "REPLAN_REQUEST_BUDGET")
                put("prompt_tokens", error.promptTokens)
                put("safety_tokens", error.safetyTokens)
                error.outputTokens?.let { put("output_allowance", it) }
                error.capacityTokens?.let { put("capacity_tokens", it) }
                put("measurement", error.measurement.name)
                put("admission_prompt_tokens", error.promptTokens)
                put("admission_safety_tokens", error.safetyTokens)
                error.outputTokens?.let { put("admission_output_tokens", it) }
                error.capacityTokens?.let { put("admission_capacity_tokens", it) }
                put("admission_measurement", error.measurement.name)
                error.budget?.let { budget ->
                    put("admission_tool_result_reserve_tokens", budget.toolResultReserveTokens)
                    put("admission_attachment_reserve_tokens", budget.attachmentReserveTokens)
                    put("admission_tool_schema_tokens", budget.components.firstOrNull { it.name == "tool_schemas" }?.tokens ?: 0)
                    budget.dominantComponent?.let {
                        val component = if (it.name == "attachment_reserve") "attachments" else it.name
                        put("admission_dominant_component", component)
                        put("admission_dominant_tokens", it.tokens)
                    }
                }
                put("partial_output_preserved", partialOutput)
                // A rejected continuation cannot prove that an earlier physical
                // request was unsent. Invocation accounting retains that history.
                if (!partialOutput) {
                    put("dispatched", false)
                    put("dispatch_state", "PRECHECK_REJECTED")
                    put("remote_acceptance", "NO")
                }
            }
        )
    }
    if (error is dev.chungjungsoo.gptmobile.data.github.GitHubRequestCircuit.Open) {
        return ErrorDetail(
            message = "GitHub is temporarily cooling down. Resume the saved request after the retry time.",
            type = "github_transport_admission",
            code = "GITHUB_CIRCUIT_OPEN",
            retryable = true,
            failureDetails = buildJsonObject {
                put("failed_stage", "repository_admission")
                put("attempt_dispatch_state", "NOT_DISPATCHED")
                put("retry_after_ms", error.retryAfterMs)
                put("retry_not_before_ms", nowMillis + error.retryAfterMs)
                put("partial_output_preserved", partialOutput)
                if (!partialOutput) {
                    put("dispatch_state", "PRECHECK_REJECTED")
                    put("dispatched", false)
                    put("remote_acceptance", "NO")
                }
            }
        )
    }
    if (error is dev.chungjungsoo.gptmobile.data.agent.GitHubToolContextBudgetException) {
        val failure = error.providerFailure()
        return ErrorDetail(
            message = failure.message,
            type = "local_admission",
            code = failure.errorCode,
            retryable = false,
            failureDetails = buildJsonObject {
                failure.failureDetails.orEmpty().forEach { (key, value) -> if (!partialOutput || key != "dispatched") put(key, value) }
                put("attempt_dispatch_state", "NOT_DISPATCHED")
                if (!partialOutput) {
                    put("dispatch_state", "PRECHECK_REJECTED")
                    put("remote_acceptance", "NO")
                }
                put("partial_output_preserved", partialOutput)
            }
        )
    }
    if (error is dev.chungjungsoo.gptmobile.data.github.GitHubReadValidation) {
        return ErrorDetail(
            message = error.message.orEmpty(),
            type = "github_read_validation",
            code = error.code,
            retryable = false,
            failureDetails = buildJsonObject {
                error.details.forEach { (key, value) ->
                    // A refused dependent read cannot establish that an earlier model
                    // request was unsent after its output has already been observed.
                    if (!partialOutput || key !in setOf("dispatched", "dispatch_state", "remote_acceptance")) put(key, value)
                }
                put("code", error.code)
                put("retryable", false)
                put("partial_output_preserved", partialOutput)
            }
        )
    }
    if (error is dev.chungjungsoo.gptmobile.data.network.gateway.GatewayCapabilityDiscoveryException) {
        return ErrorDetail(
            message = requireNotNull(error.message),
            type = "gateway_capability",
            code = error.code,
            retryable = false,
            failureDetails = buildJsonObject {
                put("code", error.code)
                put("failed_stage", "connection_preflight")
                put("attempt_dispatch_state", "NOT_DISPATCHED")
                put("repair", "CHECK_CONNECTION")
                put("retryable", false)
                put("partial_output_preserved", partialOutput)
                error.httpStatus?.let { put("http_status", it) }
            }
        )
    }
    if (error is dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationException) {
        return ErrorDetail(
            message = requireNotNull(error.message),
            type = "model_preparation",
            code = error.code,
            retryable = false,
            failureDetails = buildJsonObject {
                put("code", error.code)
                put("failed_stage", "model_preparation")
                put("attempt_dispatch_state", "NOT_DISPATCHED")
                put("retryable", false)
                put("partial_output_preserved", partialOutput)
                // This describes the dependent inference, never the model-load POST.
                if (!partialOutput) {
                    put("dispatched", false)
                    put("dispatch_state", "NOT_DISPATCHED")
                    put("remote_acceptance", "NO")
                }
                put("request_replay_allowed", false)
                put("logical_continuation_allowed", false)
                error.state.operationId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }?.let { put("operation_id", it) }
                error.state.transportCode?.takeIf { code -> NetworkFailureKind.entries.any { it.code == code } }?.let { put("transport_code", it) }
                error.state.observation?.safeReasonCode?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,95}")) }?.let { put("reason_code", it) }
            }
        )
    }
    if (error is dev.chungjungsoo.gptmobile.data.github.GatewayAdmissionException) {
        val unsent = error.dispatchState == dev.chungjungsoo.gptmobile.data.github.GatewayAdmissionDispatchState.NOT_DISPATCHED
        val code = "GATEWAY_${error.reason.name}"
        return ErrorDetail(
            message = requireNotNull(error.message),
            type = "gateway_admission",
            code = code,
            retryable = false,
            failureDetails = buildJsonObject {
                put("code", code)
                put("failed_stage", if (error.phase == dev.chungjungsoo.gptmobile.data.github.GatewayAdmissionPhase.PRECHECK) "connection_preflight" else "gateway_recovery")
                put("attempt_dispatch_state", if (unsent) "NOT_DISPATCHED" else "UNKNOWN")
                if (error.preparationPost) put("preparation_exchange", "POST_OUTCOME_UNKNOWN")
                error.operationId?.let {
                    put("operation_id", it)
                    put("incident_id", it)
                }
                put("repair", error.repair.name)
                put("retryable", false)
                put("partial_output_preserved", partialOutput)
                error.httpStatus?.let { put("http_status", it) }
                // Only this preflight is known unsent. Earlier attempts and saved
                // command outcomes remain the invocation owner's responsibility.
            }
        )
    }
    if (error is dev.chungjungsoo.gptmobile.data.context.SelectedModelUnavailableException) {
        return ErrorDetail(
            message = requireNotNull(error.message),
            type = "local_admission",
            code = error.reason.name,
            retryable = false,
            failureDetails = buildJsonObject {
                put("code", error.reason.name)
                put("failed_stage", "provider_admission")
                put("attempt_dispatch_state", "NOT_DISPATCHED")
                put("retryable", false)
                put("partial_output_preserved", partialOutput)
                error.observation.failureFields().forEach { (name, value) -> put(name, value) }
            }
        )
    }
    if (error is dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityUnavailableException) {
        return ErrorDetail(
            message = requireNotNull(error.message),
            type = "local_admission",
            code = error.code,
            retryable = false,
            failureDetails = buildJsonObject {
                put("code", error.code)
                put("failed_stage", "provider_admission")
                put("attempt_dispatch_state", "NOT_DISPATCHED")
                put("retryable", false)
                put("repair", "CHECK_CONNECTION")
                put("partial_output_preserved", partialOutput)
                put(
                    "reasoning_probe_observation",
                    when {
                        error.gatewayFailure != null -> "GATEWAY_OBSERVATION_AVAILABLE"
                        error.code == "REASONING_CAPABILITY_TIMEOUT" -> "DEADLINE_WITHOUT_GATEWAY_OBSERVATION"
                        else -> "NO_GATEWAY_OBSERVATION"
                    }
                )
                error.gatewayFailure?.failureFields()?.forEach { (name, value) -> put(name, value) }
            }
        )
    }
    if (error is dev.chungjungsoo.gptmobile.data.context.ReasoningAdmissionException) {
        val code = "REASONING_${error.requested}_UNSUPPORTED"
        return ErrorDetail(
            message = requireNotNull(error.message),
            type = "local_admission",
            code = code,
            retryable = false,
            failureDetails = buildJsonObject {
                put("code", code)
                put("failed_stage", "provider_admission")
                put("attempt_dispatch_state", "NOT_DISPATCHED")
                put("retryable", false)
                put("reasoning_requested_mode", error.requested.name)
                put("reasoning_admission_basis", "NO_VERIFIED_CONTROL")
                put("reasoning_recovery_action", "CHOOSE_AUTO_OR_QUALIFIED_MODEL")
                error.gatewayRoute?.let { put("reasoning_route_kind", if (it) "GATEWAY" else "DIRECT") }
                error.gatewayObservation?.observationAgeMillis?.let { put("reasoning_capability_age_ms", it) }
                error.gatewayObservation?.modelState?.let { put("reasoning_model_state", it) }
                error.gatewayObservation?.reasoningControlState?.let { put("reasoning_control_state", it) }
                dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext.identifierHash("reasoning-template", error.templateRevision)
                    ?.let { put("reasoning_template_hash", it) }
                put("repair", "CHANGE_REASONING_SETTINGS")
                put("partial_output_preserved", partialOutput)
                // This exception qualifies this preflight only. Invocation accounting owns
                // the proof that every earlier physical attempt was also unsent.
            }
        )
    }
    if (error is dev.chungjungsoo.gptmobile.data.network.error.CircuitBreakerOpenException) {
        val classified = dev.chungjungsoo.gptmobile.data.network.error.ErrorClassification.classify(error)
        val gatewayUnknown = dev.chungjungsoo.gptmobile.data.agent.GatewayOutcomeRecovery.requiresReconciliation(error.message.orEmpty(), classified.errorCode)
        return ErrorDetail(
            message = if (gatewayUnknown) error.message ?: classified.userMessage else classified.userMessage,
            code = if (gatewayUnknown) "GATEWAY_OUTCOME_UNKNOWN" else "CIRCUIT_BREAKER_OPEN",
            retryable = false,
            failureDetails = when {
                gatewayUnknown -> buildJsonObject {
                    put("failed_stage", "gateway_dispatch")
                    put("outcome", "unknown")
                    put("replay_safe", false)
                }
                else -> error.failureDetails(partialOutput)
            }
        )
    }
    val httpFailure = error as? ProviderHttpFailure
    val http = httpFailure?.let { ProviderFailure.parse(it.status, retryAfterMillis = it.retryAfterMs) }
    val rateLimit = error as? FreeAiRateLimitException
    val free = error as? FreeProviderException
    val category = free?.category ?: rateLimit?.let(::freeFailureCategory)
    if (http == null && category == null) return null
    val localLimit = category in setOf(FreeFailureCategory.LOCAL_COOLDOWN_ACTIVE, FreeFailureCategory.LOCAL_ALLOWANCE_EXHAUSTED)
    val localBlock = rateLimit?.localBlock == true || free != null && localLimit || httpFailure?.beforeDispatch == true
    val responded = http != null && httpFailure?.beforeDispatch != true || rateLimit?.localBlock == false
    val retryAt = rateLimit?.retryAt ?: free?.providerLimit?.retryAt
    val observedHttpStatus = httpFailure?.takeUnless { it.beforeDispatch && it.consecutiveRejections == 0 }
        ?.let { it.observedHttpStatus?.takeIf { status -> status in 100..599 } ?: it.status }
    val observedRetryAt = httpFailure?.takeIf { it.retrySource != null }?.retryAtMillis
    val retryable = !partialOutput && (http?.retryable ?: (retryAt != null && (localLimit || category == FreeFailureCategory.PROVIDER_RATE_LIMITED)))
    val code = http?.status?.toString() ?: requireNotNull(category).name
    return ErrorDetail(
        message = http?.message() ?: error.message.orEmpty(),
        type = if (http != null) "http_error" else category?.name,
        code = code,
        retryable = retryable,
        failureDetails = buildJsonObject {
            put("code", code)
            put(
                "failed_stage",
                if (responded) {
                    "model_invocation"
                } else if (localBlock) {
                    "provider_admission"
                } else {
                    "stage_not_recorded"
                }
            )
            if (responded || localBlock) {
                put("dispatched", responded)
                put("dispatch_state", if (responded) "FAILED" else "PRECHECK_REJECTED")
            }
            (observedHttpStatus ?: rateLimit?.cooldown?.status)
                ?.let { put(if (responded) "http_status" else "originating_http_status", it) }
            if (responded) safeProviderRequestIdentity(httpFailure?.serverRequestId)?.let { put("server_request_id", it) }
            rateLimit?.cooldown?.providerCode?.let { put(if (responded) "provider_code" else "originating_provider_code", it) }
            (http?.retryAfterMillis ?: retryAt?.let { ProviderRetryPolicy.remainingMillis(it, nowMillis) })?.let { put("retry_after_ms", it) }
            (retryAt ?: observedRetryAt)?.let { put("retry_at_ms", it) }
            httpFailure?.retrySource?.let { put("retry_source", it) }
            httpFailure?.consecutiveRejections?.takeIf { it > 0 }?.let { put("consecutive_rejections", it) }
            httpFailure?.errorOrigin?.let { put("provider_error_origin", it) }
            httpFailure?.errorType?.let { put("provider_error_type", it) }
            put("retryable", retryable)
            put("partial_output_preserved", partialOutput)
        }
    )
}
