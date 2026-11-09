package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.openai.response.ErrorDetail
import dev.chungjungsoo.gptmobile.data.network.error.ProviderFailure
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Retain known response/admission evidence before considering an exception a transport failure. */
internal fun providerExceptionDetails(error: Exception, partialOutput: Boolean, nowMillis: Long = System.currentTimeMillis()): ErrorDetail? {
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
                put("repair", error.repair.name)
                put("retryable", false)
                put("partial_output_preserved", partialOutput)
                error.httpStatus?.let { put("http_status", it) }
                // Only this preflight is known unsent. Earlier attempts and saved
                // command outcomes remain the invocation owner's responsibility.
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
    val http = (error as? ProviderHttpFailure)?.let { ProviderFailure.parse(it.status, retryAfterMillis = it.retryAfterMs) }
    val rateLimit = error as? FreeAiRateLimitException
    val free = error as? FreeProviderException
    val category = free?.category ?: rateLimit?.let(::freeFailureCategory)
    if (http == null && category == null) return null
    val localLimit = category in setOf(FreeFailureCategory.LOCAL_COOLDOWN_ACTIVE, FreeFailureCategory.LOCAL_ALLOWANCE_EXHAUSTED)
    val localBlock = rateLimit?.localBlock == true || free != null && localLimit
    val responded = http != null || rateLimit?.localBlock == false
    val retryAt = rateLimit?.retryAt ?: free?.providerLimit?.retryAt
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
            (http?.status ?: rateLimit?.cooldown?.status)?.let { put(if (responded) "http_status" else "originating_http_status", it) }
            rateLimit?.cooldown?.providerCode?.let { put(if (responded) "provider_code" else "originating_provider_code", it) }
            (http?.retryAfterMillis ?: retryAt?.let { ProviderRetryPolicy.remainingMillis(it, nowMillis) })?.let { put("retry_after_ms", it) }
            retryAt?.let { put("retry_at_ms", it) }
            put("retryable", retryable)
            put("partial_output_preserved", partialOutput)
        }
    )
}
