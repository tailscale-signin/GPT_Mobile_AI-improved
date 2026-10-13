package dev.chungjungsoo.gptmobile.data.assist

import kotlinx.serialization.Serializable

@Serializable
data class LocalAssistPolicy(
    val version: Int = 1,
    val profileUid: String? = null,
    val evidenceSelection: Boolean = true,
    val summarizeRetrievedText: Boolean = false,
    val proposeQueries: Boolean = false,
    val taskTimeoutSeconds: Int = 8,
    val maxAttempts: Int = 2,
    val candidateRecords: Int = 12,
    val selectedRecords: Int = 5
) {
    fun validated() = copy(taskTimeoutSeconds = taskTimeoutSeconds.coerceIn(2, 30), maxAttempts = maxAttempts.coerceIn(1, 8), candidateRecords = candidateRecords.coerceIn(5, 24), selectedRecords = selectedRecords.coerceIn(1, 5))
}
