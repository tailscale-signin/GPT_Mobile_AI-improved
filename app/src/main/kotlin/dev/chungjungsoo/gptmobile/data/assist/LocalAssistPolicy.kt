package dev.chungjungsoo.gptmobile.data.assist

import kotlinx.serialization.Serializable

@Serializable
data class LocalAssistPolicy(
    val version: Int = 3,
    val profileUid: String? = null,
    val evidenceSelection: Boolean = true,
    val summarizeRetrievedText: Boolean = false,
    val proposeQueries: Boolean = false,
    val taskTimeoutSeconds: Int = 20,
    val maxAttempts: Int = 8,
    val candidateRecords: Int = 12,
    val selectedRecords: Int = 5,
    val preloadInForeground: Boolean = false,
    val preparation: AssistPreparation = AssistPreparation.PREPARE_AUTOMATICALLY,
    val loadTimeoutSeconds: Int = 60,
    val queueWaitSeconds: Int = 20,
    val researchPreparation: Boolean = true,
    val contextTokens: Int = 4096,
    val permissionVersion: Int = 0,
    val profilePermissions: Map<String, AssistProcessingPermission> = emptyMap()
) {
    fun validated() = copy(queueWaitSeconds = queueWaitSeconds.coerceIn(1, 60), contextTokens = contextTokens.coerceIn(1024, 16384), loadTimeoutSeconds = loadTimeoutSeconds.coerceIn(10, 120), taskTimeoutSeconds = taskTimeoutSeconds.coerceIn(2, 30), maxAttempts = maxAttempts.coerceIn(1, 8), candidateRecords = candidateRecords.coerceIn(5, 24), selectedRecords = selectedRecords.coerceIn(1, 5))
}
