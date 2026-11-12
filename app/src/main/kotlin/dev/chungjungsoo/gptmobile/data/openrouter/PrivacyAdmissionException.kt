package dev.chungjungsoo.gptmobile.data.openrouter

import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A metadata rejection precedes generation and must retain that distinction in every collector. */
internal class PrivacyAdmissionException(
    message: String,
    private val model: String? = null,
    private val policy: OpenRouterProviderRouting? = null,
    private val observation: Observation? = null
) : IllegalStateException(message) {
    enum class Observation { CACHED_REJECTION, NO_COMPATIBLE_ENDPOINT }

    fun asApiError(runId: String, profileUid: String? = null): ApiState.Error = ApiState.Error(
        message = message ?: "Choose a model compatible with the selected privacy settings.",
        errorCode = CODE,
        failureDetails = buildJsonObject {
            put("run_id", runId)
            put("provider", "OPENROUTER")
            put("failed_stage", "provider_admission")
            put("dispatched", false)
            put("remote_acceptance", "NO")
            put("retryable", false)
            put("resume_allowed", false)
            put("requires_reconciliation", false)
            put("recovery_action", "RETRY_AFTER_CONFIGURATION_CHANGE")
            if (observation != null && model != null && policy != null) {
                put("diagnostic_area", "PROVIDER")
                put("diagnostic_capture_mode", "INCIDENT")
                profileUid?.let { put("profile_hash", researchHash(it)) }
                put("model_hash", researchHash(model))
                runCatching { Json.encodeToString(policy) }.getOrNull()?.let { put("privacy_policy_hash", researchHash(it)) }
                put("privacy_observation", observation.name)
                policy.zdr?.let { put("privacy_zdr_required", it) }
                policy.allowFallbacks?.let { put("privacy_fallbacks_allowed", it) }
                policy.only?.let { put("privacy_provider_allowlist_count", it.size) }
                policy.ignore?.let { put("privacy_provider_denylist_count", it.size) }
                policy.order?.let { put("privacy_provider_order_count", it.size) }
            }
        }
    )

    companion object {
        const val CODE = "PRIVACY_CONFIGURATION"
    }
}
