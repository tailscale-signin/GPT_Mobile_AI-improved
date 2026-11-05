package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.openai.response.ErrorDetail
import dev.chungjungsoo.gptmobile.data.network.error.ProviderFailure
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Retain known response/admission evidence before considering an exception a transport failure. */
internal fun providerExceptionDetails(error: Exception, partialOutput: Boolean, nowMillis: Long = System.currentTimeMillis()): ErrorDetail? {
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
