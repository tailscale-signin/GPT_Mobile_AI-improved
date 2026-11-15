package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.research.researchHash

/** A local memory lookup cannot establish the outcome or authority of earlier work. */
internal class GitHubEngineeringMemoryAccessRejected(
    val phase: Phase,
    val reason: Reason
) : IllegalStateException(
    when (reason) {
        Reason.CONNECTION_MISSING -> "The selected GitHub connection is no longer available."
        Reason.CONNECTION_TYPE_MISMATCH -> "The selected connection is not a GitHub connection."
        Reason.AUTHORIZATION_CHANGED -> "GitHub memory access changed while preparing this message."
    }
) {
    enum class Phase { PREPARE_SCOPE, PREPARE_PERMISSION, PREPARE_CONNECTION, REVALIDATE_SCOPE, REVALIDATE_PERMISSION, REVALIDATE_AUTHORIZATION, OBSERVE_SCOPE }
    enum class Reason { CONNECTION_MISSING, CONNECTION_TYPE_MISMATCH, AUTHORIZATION_CHANGED }

    val reasonCode: String = "ENGINEERING_MEMORY_${phase.name}_${reason.name}"
    val notice: String = "$message This message will continue without saved repository memory. Review the selected connection and repository access in GitHub settings to restore it."

    fun reportFields(): Map<String, Any?> = mapOf(
        "diagnostic_area" to "TOOL",
        "diagnostic_capture_mode" to "INCIDENT",
        "failed_stage" to "repository_admission",
        "operation" to "github_engineering",
        "recovery_action" to "REVIEW_AUTHORIZATION",
        // This is the actual closed local reason, with no account or repository identifiers.
        "original_reason_code_hash" to researchHash(reasonCode)
    )

    companion object {
        const val ERROR_CODE = "GITHUB_AUTHORIZATION_UNVERIFIED"
    }
}
