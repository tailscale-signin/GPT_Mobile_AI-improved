package dev.chungjungsoo.gptmobile.data.research.delivery

import kotlinx.serialization.Serializable

@Serializable
enum class ResearchReportValidity { ACCEPTED, EVIDENCE_ONLY, REJECTED, INCOMPLETE }

/** Small durable delivery result; report bodies remain in their owned answer revision. */
@Serializable
data class ResearchDeliveryReceipt(
    val reportValidity: ResearchReportValidity,
    val evidenceAvailable: Boolean,
    val packetDigest: String,
    val revisionId: String,
    val issueCodes: List<String> = emptyList(),
    val sourceIds: List<String> = emptyList(),
    val claimIds: List<String> = emptyList(),
    val blockIds: List<String> = emptyList(),
    val outputCap: Int? = null,
    val retryEligible: Boolean = false,
    val schemaVersion: Int = 1
) {
    val accepted: Boolean get() = reportValidity == ResearchReportValidity.ACCEPTED
    val partial: Boolean get() = reportValidity == ResearchReportValidity.EVIDENCE_ONLY || reportValidity == ResearchReportValidity.INCOMPLETE
}
