package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.privacy.PrivacyProfileEligibility
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Closed eligibility facts only. Private-session capture remains disabled by its existing policy. */
internal object PrivacyFailureEvidence {
    const val CODE = "PRIVACY_PROVIDER_INELIGIBLE"
    const val FEATURE = "PRIVACY_ELIGIBILITY_EVIDENCE_V1"
    val fields = setOf("privacy_reason_code", "privacy_status")
    val reasonCodes = PrivacyProfileEligibility.Reason.entries.map { it.name }.toSet()
    val statuses = setOf("NOT_VERIFIED", "INCOMPATIBLE")

    fun reason(value: JsonElement?): String? = closedString(value, reasonCodes)

    fun status(value: JsonElement?): String? = closedString(value, statuses)

    private fun closedString(value: JsonElement?, allowed: Set<String>): String? =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it in allowed }
}
