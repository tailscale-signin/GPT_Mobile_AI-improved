package dev.chungjungsoo.gptmobile.data.openrouter

import dev.chungjungsoo.gptmobile.data.dto.ApiState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A metadata rejection precedes generation and must retain that distinction in every collector. */
internal class PrivacyAdmissionException(message: String) : IllegalStateException(message) {
    fun asApiError(runId: String): ApiState.Error = ApiState.Error(
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
        }
    )

    companion object {
        const val CODE = "PRIVACY_CONFIGURATION"
    }
}
