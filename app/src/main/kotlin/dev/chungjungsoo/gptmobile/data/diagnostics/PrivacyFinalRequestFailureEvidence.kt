package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Closed facts from the typed final-wire refusal; never read or project its private receipt. */
internal object PrivacyFinalRequestFailureEvidence {
    const val CODE = "PRIVACY_FINAL_REQUEST_REJECTED"
    const val FEATURE = "PRIVACY_FINAL_REQUEST_EVIDENCE_V1"
    const val FIELD = "privacy_dispatch_outcome"
    const val STAGE = "request_validation"
    val fields = setOf(FIELD)
    val identityFields = setOf("provider_failure_code", "provider_failure_stage", "provider_failure_domain")
    val outcomes = setOf("POLICY_REVISION_MISMATCH", "KNOWN_VALUE_REINTRODUCED", "NO_SUPPORTED_TEXT_SURFACES", "WIRE_VALIDATION_UNAVAILABLE")

    fun outcome(value: Any?): String? = text(value)?.takeIf { it in outcomes }

    fun observedOutcome(code: String?, fields: Map<String, Any?>?): String? =
        outcome(fields?.get(FIELD))?.takeIf {
            code == CODE &&
                text(fields?.get("failed_stage")) == STAGE &&
                isFalse(fields?.get("request_replay_allowed")) &&
                isFalse(fields?.get("logical_continuation_allowed"))
        }

    fun valid(values: Map<String, JsonElement>): Boolean = FIELD !in values ||
        values["provider_failure_code"] == JsonPrimitive(CODE) &&
        values["provider_failure_stage"] == JsonPrimitive("ADMISSION") &&
        values["provider_failure_domain"] == JsonPrimitive("APPLICATION_VERIFICATION") &&
        outcome(values[FIELD]) != null

    private fun isFalse(value: Any?): Boolean = value == false || value == JsonPrimitive(false)

    private fun text(value: Any?): String? = when (value) {
        is String -> value
        is JsonPrimitive -> value.takeIf { it.isString }?.contentOrNull
        else -> null
    }
}
