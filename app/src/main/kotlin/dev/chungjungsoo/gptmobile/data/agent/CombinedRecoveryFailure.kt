package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveCombinedManifest
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveRunId
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveTimeline
import dev.chungjungsoo.gptmobile.data.database.entity.hasHistoricalRevisionSelected
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.Serializable

@Serializable
enum class CombinedRecoveryCategory { REASONING_STALL, EMPTY_OUTPUT, MALFORMED_RECORD, QUEUE_PENDING, NETWORK_TRANSIENT, PROVIDER_UNAVAILABLE, INCOMPLETE_RECORD, REQUIREMENTS_UNMET, AUTHORITY_REJECTED, BUDGET_EXHAUSTED, UNKNOWN }

@Serializable
data class CombinedRecoveryFailure(
    val category: CombinedRecoveryCategory,
    val decision: String,
    val localAttemptStatus: String = "FAILED",
    val remoteJobStatus: String = "UNKNOWN",
    val deliveryStatus: String = "INCOMPLETE"
) {
    val rotateWriter: Boolean get() = decision == "TRY_ELIGIBLE_WRITER"

    companion object {
        /** Interpret the selected saved revision without changing its original receipts. */
        fun retained(message: MessageV2, terminalError: String? = null): CombinedRecoveryFailure? {
            val timeline = message.effectiveTimeline()
            val recorded = timeline.lastOrNull { it.combinedFailure != null }?.combinedFailure
                ?: terminalError?.let { fromCode(it.substringBefore(':')) }
                ?: return null
            if (recorded.category != CombinedRecoveryCategory.INCOMPLETE_RECORD) return recorded
            val report = timeline.lastOrNull { it.combinedQuality != null }?.combinedQuality ?: return recorded
            if (!report.finalAssessment ||
                !report.protocolComplete ||
                report.complete ||
                !(report.correctionRequired || report.lengthCompliance == "UNMET" || report.topicCoverage == "UNMET")
            ) {
                return recorded
            }
            val reference = message.effectiveCombinedManifest()
            val digest = if (reference == null) {
                researchHash(message.effectiveContent())
            } else {
                // Compact document headers must not hydrate the answer during rendering.
                // An inline archive may be normalized under a successor's storage run.
                // Its historical answer run and a legacy blank report revision stay intact.
                // Revision creation separately checks the immutable manifest's message owner.
                if ((!message.hasHistoricalRevisionSelected() && reference.runId != message.effectiveRunId()) ||
                    reference.id.isBlank() ||
                    reference.runId.isBlank() ||
                    reference.revisionId.isBlank() ||
                    report.revisionId.isNotBlank() &&
                    reference.revisionId != report.revisionId ||
                    reference.bodyLength <= 0
                ) {
                    return recorded
                }
                if (reference.materialized && researchHash(message.effectiveContent()) != reference.bodyDigest) return recorded
                reference.bodyDigest
            }
            if (report.answerDigest.isBlank() || report.answerDigest != digest) return recorded
            return fromCode("COMBINED_REQUIREMENTS_UNMET")
        }

        fun from(event: ProviderEvent.Failed): CombinedRecoveryFailure = fromCode(event.errorCode?.substringBefore(':') ?: event.message.substringBefore(':').takeIf { it.matches(Regex("[A-Z][A-Z0-9_]+")) })
        fun fromCode(code: String?): CombinedRecoveryFailure {
            val category = when (code) {
                "COMBINED_WRITER_STALLED", "REASONING_BUDGET_EXHAUSTED", "reasoning_budget_exhausted" -> CombinedRecoveryCategory.REASONING_STALL
                "COMBINED_EMPTY_OUTPUT", "NO_VISIBLE_ANSWER", "no_visible_answer", "CACHED_UNUSABLE_RESPONSE" -> CombinedRecoveryCategory.EMPTY_OUTPUT
                "OUTPUT_PROTOCOL_INVALID", "INVALID_DELIVERY_RECORD", "COMBINED_PROTOCOL_UNSUITABLE", "STREAM_DEGRADED" -> CombinedRecoveryCategory.MALFORMED_RECORD
                "QUEUE_JOB_PENDING" -> CombinedRecoveryCategory.QUEUE_PENDING
                "NETWORK_ERROR", "network_error", "DNS_RESOLUTION_FAILED", "QUEUE_STREAM_DISCONNECTED", "408", "500", "502", "503", "504" -> CombinedRecoveryCategory.NETWORK_TRANSIENT
                "COMBINED_PROVIDER_UNAVAILABLE", "PROVIDER_UNAVAILABLE", "HOSTED_GENERATION_FAILED", "LOCAL_COOLDOWN_ACTIVE", "LOCAL_ALLOWANCE_EXHAUSTED", "PROVIDER_RATE_LIMITED", "429" -> CombinedRecoveryCategory.PROVIDER_UNAVAILABLE
                "COMBINED_PARTIAL", "COMBINED_RECORD_INCOMPLETE", "COMBINED_RECORD_RECOVERY_LIMIT", "COMBINED_LENGTH_REQUIREMENT_UNMET" -> CombinedRecoveryCategory.INCOMPLETE_RECORD
                "COMBINED_REQUIREMENTS_UNMET" -> CombinedRecoveryCategory.REQUIREMENTS_UNMET
                "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REJECTED", "PROVIDER_CLIENT_RESTRICTED", "401", "403", "COMBINED_STAGE_IDENTITY_REJECTED", "COMBINED_SUCCESSOR_AUTHORITY_CHANGED", "COMBINED_SUCCESSOR_TRANSFER_DENIED", "COMBINED_SUCCESSOR_CORPUS_CHANGED" -> CombinedRecoveryCategory.AUTHORITY_REJECTED
                "COMBINED_DELIVERY_LIMIT", "COMBINED_BUDGET_EXHAUSTED" -> CombinedRecoveryCategory.BUDGET_EXHAUSTED
                else -> CombinedRecoveryCategory.UNKNOWN
            }
            return CombinedRecoveryFailure(
                category,
                when (category) {
                    CombinedRecoveryCategory.REASONING_STALL, CombinedRecoveryCategory.EMPTY_OUTPUT, CombinedRecoveryCategory.MALFORMED_RECORD, CombinedRecoveryCategory.QUEUE_PENDING, CombinedRecoveryCategory.NETWORK_TRANSIENT, CombinedRecoveryCategory.PROVIDER_UNAVAILABLE -> "TRY_ELIGIBLE_WRITER"
                    CombinedRecoveryCategory.INCOMPLETE_RECORD -> if (code == "COMBINED_LENGTH_REQUIREMENT_UNMET") "STOP" else "RESUME_SAVED_RECORD"
                    CombinedRecoveryCategory.REQUIREMENTS_UNMET -> "REVIEW_COMPLETED_ANSWER"
                    else -> "STOP"
                },
                localAttemptStatus = when (category) {
                    CombinedRecoveryCategory.REQUIREMENTS_UNMET -> "COMPLETED_WITH_ISSUES"
                    CombinedRecoveryCategory.QUEUE_PENDING -> "WAITING_REMOTE_JOB"
                    else -> "FAILED"
                },
                remoteJobStatus = if (category == CombinedRecoveryCategory.QUEUE_PENDING) {
                    "PENDING"
                } else if (category == CombinedRecoveryCategory.REQUIREMENTS_UNMET) {
                    "COMPLETED"
                } else {
                    "UNKNOWN"
                },
                deliveryStatus = if (category == CombinedRecoveryCategory.REQUIREMENTS_UNMET) "COMPLETE_NEEDS_CORRECTION" else "INCOMPLETE"
            )
        }
    }
}
