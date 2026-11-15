package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Gateway allocation is authoritative; clients display it without reallocating tokens. */
@Serializable
data class GatewayExecutionBudgetReceipt(
    val schema: String,
    @SerialName("receipt_id") val receiptId: String,
    @SerialName("binding_id") val bindingId: String,
    @SerialName("model_id") val modelId: String,
    @SerialName("context_tokens") val contextTokens: Int,
    @SerialName("input_tokens") val inputTokens: Int,
    @SerialName("output_tokens") val outputTokens: Int,
    @SerialName("safety_tokens") val safetyTokens: Int,
    @SerialName("verification_tokens") val verificationTokens: Int,
    @SerialName("final_tokens") val finalTokens: Int,
    @SerialName("recovery_tokens") val recoveryTokens: Int,
    @SerialName("remaining_tokens") val remainingTokens: Int,
    @SerialName("count_mode") val countMode: String,
    @SerialName("parent_receipt_id") val parentReceiptId: String? = null,
    @SerialName("allocation_version") val allocationVersion: Int = 1
) {
    fun matches(expectedBindingId: String?, expectedModelId: String?): Boolean {
        val values = listOf(inputTokens, outputTokens, safetyTokens, verificationTokens, finalTokens, recoveryTokens, remainingTokens)
        return schema == "gateway.execution-budget.v1" &&
            bindingId == expectedBindingId &&
            modelId == expectedModelId &&
            receiptId.matches(Regex("[a-f0-9]{64}")) &&
            allocationVersion > 0 &&
            contextTokens > 0 &&
            outputTokens > 0 &&
            values.all { it >= 0 } &&
            values.sumOf { it.toLong() } == contextTokens.toLong()
    }
}

@Serializable
data class GatewayEngineeringCheckpoints(
    @SerialName("job_id") val jobId: String,
    val revision: Int,
    @SerialName("head_sha") val headSha: String? = null,
    val state: String,
    val published: Boolean = true,
    val candidates: List<GatewayEngineeringCandidate> = emptyList()
)

@Serializable
data class GatewayEngineeringCandidate(
    @SerialName("check_id") val checkId: String,
    @SerialName("patch_identity") val patchIdentity: String,
    @SerialName("check_index") val checkIndex: Int,
    val status: String,
    @SerialName("source_sha") val sourceSha: String
)
