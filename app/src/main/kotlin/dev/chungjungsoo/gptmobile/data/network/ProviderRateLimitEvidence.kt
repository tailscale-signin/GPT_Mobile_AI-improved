package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.agent.ProviderWaitSchedulingResult.NoWaitReason
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A local conversation key is not a remote command. Only dispatched bindings can prevent replay. */
internal fun providerRateLimitWaitRefusal(
    config: ProviderRequestConfig,
    dispatchedCommandId: String?,
    remoteOutcomeUnknown: Boolean,
    receivedPayload: Boolean,
    remoteJobId: String?,
    structuredFailure: JsonObject? = null
): NoWaitReason? = when {
    remoteOutcomeUnknown ||
        structuredFailure?.get("requires_reconciliation") == JsonPrimitive(true) ||
        structuredFailure?.get("remote_acceptance") in setOf(JsonPrimitive("YES"), JsonPrimitive("UNKNOWN")) ||
        dev.chungjungsoo.gptmobile.data.network.error.ProviderStreamFailure.isReconciliationCode((structuredFailure?.get("code") as? JsonPrimitive)?.contentOrNull) -> NoWaitReason.REMOTE_OUTCOME_UNKNOWN
    !remoteJobId.isNullOrBlank() || (structuredFailure?.get("job_id") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.isNotBlank() == true -> NoWaitReason.REMOTE_JOB_PRESENT
    dispatchedCommandId != null || config.requireGatewayConversation -> NoWaitReason.GATEWAY_COMMAND_PRESENT
    receivedPayload -> NoWaitReason.INITIAL_OUTPUT_PRESENT
    structuredFailure?.get("retryable") == JsonPrimitive(false) || structuredFailure?.get("request_replay_allowed") == JsonPrimitive(false) -> NoWaitReason.REJECTION_NOT_REPLAY_SAFE
    else -> null
}

/** Preserve why typed transport evidence could not produce a schedulable decision. */
internal fun providerLimitRefusalDetails(config: ProviderRequestConfig, model: String, receivedPayload: Boolean, error: Exception): JsonObject {
    val failure = transportRateLimitFailure(error)?.takeIf { it.status == 429 } ?: return JsonObject(emptyMap())
    val reason = when {
        failure.waitRefusal != null -> failure.waitRefusal
        !(failure.knownNotAccepted || failure.checkpointContinuationAllowed) -> NoWaitReason.REJECTION_NOT_REPLAY_SAFE
        config.profileUid.isNullOrBlank() -> NoWaitReason.PROFILE_UNAVAILABLE
        model.isBlank() || config.lockedModel != null && config.lockedModel != model -> NoWaitReason.ROUTE_MISMATCH
        failure.retryAtMillis == null -> NoWaitReason.RETRY_DEADLINE_UNAVAILABLE
        receivedPayload && !failure.checkpointContinuationAllowed -> NoWaitReason.INITIAL_OUTPUT_PRESENT
        else -> null
    }
    return providerWaitRefusalFields(reason)
}

internal fun providerWaitRefusalFields(reason: NoWaitReason?): JsonObject = buildJsonObject {
    reason?.let {
        put("provider_wait_no_wait_reason", it.name)
        put("retryable", false)
        put("request_replay_allowed", false)
        put("logical_continuation_allowed", false)
    }
}
