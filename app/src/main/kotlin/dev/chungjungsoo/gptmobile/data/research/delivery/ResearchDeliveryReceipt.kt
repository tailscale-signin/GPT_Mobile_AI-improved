package dev.chungjungsoo.gptmobile.data.research.delivery

import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.Serializable

@Serializable
enum class ResearchReportValidity { ACCEPTED, EVIDENCE_ONLY, REJECTED, INCOMPLETE }

@Serializable
enum class ResearchReviewStatus { REVIEWED, PARTIALLY_REVIEWED, UNREVIEWED }

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
    val schemaVersion: Int = 2,
    val semanticCoverageVerified: Boolean = false,
    val coveredClaimCount: Int = 0,
    val eligibleClaimCount: Int = 0,
    val reviewStatus: ResearchReviewStatus = ResearchReviewStatus.UNREVIEWED
) {
    val accepted: Boolean get() = reportValidity == ResearchReportValidity.ACCEPTED
    val partial: Boolean get() = reportValidity == ResearchReportValidity.EVIDENCE_ONLY || reportValidity == ResearchReportValidity.INCOMPLETE

    /** A receipt validates exact published bytes, including whitespace and appended text. */
    fun matchesRevision(text: String): Boolean = revisionId.isNotBlank() && revisionId == researchHash(text)

    /** Unknown text must never inherit an earlier revision's acceptance. */
    fun displayValidityFor(text: String?): ResearchReportValidity? = reportValidity.takeIf { text != null && matchesRevision(text) }
}
