package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.serialization.Serializable

/** Every paid generation remains visible, including rejected patches and unsuccessful repairs. */
@Serializable
data class BenchmarkEngineeringAttemptReceipt(
    val index: Int,
    val logicalRequestId: String,
    val candidateDigest: String?,
    val receiptId: String?,
    val outcome: String,
    val verified: Boolean,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val durationMs: Long,
    val reasonCode: String? = null,
    val effectiveSettingsConfirmed: Boolean = false,
    val adapterRequestSettings: Map<String, String> = emptyMap(),
    val physicalRequestIds: List<String> = emptyList(),
    val invocationIds: List<String> = emptyList(),
    val verification: BenchmarkEngineeringVerification? = null
)
