package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.Serializable

@Serializable
enum class CombinedRecoveryCategory { REASONING_STALL, EMPTY_OUTPUT, MALFORMED_RECORD, QUEUE_PENDING, NETWORK_TRANSIENT, PROVIDER_UNAVAILABLE, INCOMPLETE_RECORD, AUTHORITY_REJECTED, BUDGET_EXHAUSTED, UNKNOWN }

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
        fun from(event: ProviderEvent.Failed): CombinedRecoveryFailure = fromCode(event.errorCode?.substringBefore(':') ?: event.message.substringBefore(':').takeIf { it.matches(Regex("[A-Z][A-Z0-9_]+")) })
        fun fromCode(code: String?): CombinedRecoveryFailure {
            val category = when (code) {
                "COMBINED_WRITER_STALLED" -> CombinedRecoveryCategory.REASONING_STALL
                "COMBINED_EMPTY_OUTPUT" -> CombinedRecoveryCategory.EMPTY_OUTPUT
                "INVALID_DELIVERY_RECORD", "COMBINED_PROTOCOL_UNSUITABLE" -> CombinedRecoveryCategory.MALFORMED_RECORD
                "QUEUE_JOB_PENDING" -> CombinedRecoveryCategory.QUEUE_PENDING
                "NETWORK_ERROR", "network_error", "QUEUE_STREAM_DISCONNECTED", "408", "500", "502", "503", "504" -> CombinedRecoveryCategory.NETWORK_TRANSIENT
                "COMBINED_PROVIDER_UNAVAILABLE", "PROVIDER_UNAVAILABLE", "HOSTED_GENERATION_FAILED", "LOCAL_COOLDOWN_ACTIVE", "PROVIDER_RATE_LIMITED", "429" -> CombinedRecoveryCategory.PROVIDER_UNAVAILABLE
                "COMBINED_PARTIAL", "COMBINED_RECORD_RECOVERY_LIMIT" -> CombinedRecoveryCategory.INCOMPLETE_RECORD
                "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REJECTED", "PROVIDER_CLIENT_RESTRICTED", "401", "403", "COMBINED_STAGE_IDENTITY_REJECTED", "COMBINED_SUCCESSOR_AUTHORITY_CHANGED", "COMBINED_SUCCESSOR_TRANSFER_DENIED", "COMBINED_SUCCESSOR_CORPUS_CHANGED" -> CombinedRecoveryCategory.AUTHORITY_REJECTED
                "COMBINED_DELIVERY_LIMIT", "COMBINED_BUDGET_EXHAUSTED" -> CombinedRecoveryCategory.BUDGET_EXHAUSTED
                else -> CombinedRecoveryCategory.UNKNOWN
            }
            return CombinedRecoveryFailure(
                category,
                when (category) {
                    CombinedRecoveryCategory.REASONING_STALL, CombinedRecoveryCategory.EMPTY_OUTPUT, CombinedRecoveryCategory.MALFORMED_RECORD, CombinedRecoveryCategory.QUEUE_PENDING, CombinedRecoveryCategory.NETWORK_TRANSIENT, CombinedRecoveryCategory.PROVIDER_UNAVAILABLE -> "TRY_ELIGIBLE_WRITER"
                    CombinedRecoveryCategory.INCOMPLETE_RECORD -> "RESUME_SAVED_RECORD"
                    else -> "STOP"
                },
                remoteJobStatus = if (category == CombinedRecoveryCategory.QUEUE_PENDING) "PENDING" else "UNKNOWN"
            )
        }
    }
}
