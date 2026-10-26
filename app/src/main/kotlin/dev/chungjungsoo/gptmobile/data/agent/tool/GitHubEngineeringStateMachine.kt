package dev.chungjungsoo.gptmobile.data.agent.tool

/**
 * Pure state reducer for the GitHub Engineering Agent.
 *
 * This does not execute network operations or accept model-authored completion claims.
 * The persistent ledger must atomically record verified evidence and its resulting state.
 */
internal enum class EngineeringState {
    CREATED,
    INSPECTING,
    PATCH_GENERATED,
    VALIDATING_LOCAL,
    PATCH_READY,
    AWAITING_APPROVAL,
    COMMITTING,
    PUSHED,
    CI_PENDING,
    CI_VERIFIED,
    AWAITING_HARDWARE_VERIFICATION,
    HARDWARE_VERIFIED,
    COMPLETED,
    BLOCKED,
    CONFLICTED,
    CI_FAILED,
    FAILED,
    CANCELLED,
}

internal enum class EngineeringEvidence {
    INSPECTION_STARTED,
    PATCH_GENERATED,
    LOCAL_VALIDATION_STARTED,
    LOCAL_VALIDATION_PASSED,
    APPROVAL_REQUIRED,
    APPROVAL_GRANTED,
    COMMIT_STARTED,
    COMMIT_CONFIRMED,
    REMOTE_REF_CONFIRMED,
    CI_WATCH_STARTED,
    REQUIRED_CHECKS_PASSED,
    HARDWARE_REQUIRED,
    HARDWARE_RECEIPT_VERIFIED,
    POLICY_COMPLETION_CONFIRMED,
    POLICY_BLOCKED,
    REF_CONFLICT,
    CI_CHECK_FAILED,
    EXECUTION_FAILED,
    USER_CANCELLED,
}

internal object GitHubEngineeringStateMachine {
    private val transitions = mapOf(
        EngineeringState.CREATED to mapOf(EngineeringEvidence.INSPECTION_STARTED to EngineeringState.INSPECTING),
        EngineeringState.INSPECTING to mapOf(EngineeringEvidence.PATCH_GENERATED to EngineeringState.PATCH_GENERATED),
        EngineeringState.PATCH_GENERATED to mapOf(EngineeringEvidence.LOCAL_VALIDATION_STARTED to EngineeringState.VALIDATING_LOCAL),
        EngineeringState.VALIDATING_LOCAL to mapOf(EngineeringEvidence.LOCAL_VALIDATION_PASSED to EngineeringState.PATCH_READY),
        EngineeringState.PATCH_READY to mapOf(
            EngineeringEvidence.APPROVAL_REQUIRED to EngineeringState.AWAITING_APPROVAL,
            EngineeringEvidence.COMMIT_STARTED to EngineeringState.COMMITTING,
        ),
        EngineeringState.AWAITING_APPROVAL to mapOf(EngineeringEvidence.APPROVAL_GRANTED to EngineeringState.COMMITTING),
        EngineeringState.COMMITTING to mapOf(EngineeringEvidence.REMOTE_REF_CONFIRMED to EngineeringState.PUSHED),
        EngineeringState.PUSHED to mapOf(EngineeringEvidence.CI_WATCH_STARTED to EngineeringState.CI_PENDING),
        EngineeringState.CI_PENDING to mapOf(EngineeringEvidence.REQUIRED_CHECKS_PASSED to EngineeringState.CI_VERIFIED),
        EngineeringState.CI_VERIFIED to mapOf(
            EngineeringEvidence.HARDWARE_REQUIRED to EngineeringState.AWAITING_HARDWARE_VERIFICATION,
            EngineeringEvidence.POLICY_COMPLETION_CONFIRMED to EngineeringState.COMPLETED,
        ),
        EngineeringState.AWAITING_HARDWARE_VERIFICATION to mapOf(EngineeringEvidence.HARDWARE_RECEIPT_VERIFIED to EngineeringState.HARDWARE_VERIFIED),
        EngineeringState.HARDWARE_VERIFIED to mapOf(EngineeringEvidence.POLICY_COMPLETION_CONFIRMED to EngineeringState.COMPLETED),
    )

    /** The caller must validate evidence provenance and policy before invoking this reducer. */
    fun advance(current: EngineeringState, evidence: EngineeringEvidence): EngineeringState {
        val next = transitions[current]?.get(evidence)
        if (next != null) return next
        if (current in setOf(EngineeringState.COMPLETED, EngineeringState.CANCELLED)) {
            throw IllegalArgumentException("Terminal task cannot transition: $current")
        }
        val failureAllowed = current !in setOf(
            EngineeringState.BLOCKED,
            EngineeringState.CONFLICTED,
            EngineeringState.CI_FAILED,
            EngineeringState.FAILED,
        )
        if (!failureAllowed) throw IllegalArgumentException("Recovery requires a new recorded attempt: $current")
        return when (evidence) {
            EngineeringEvidence.POLICY_BLOCKED -> EngineeringState.BLOCKED
            EngineeringEvidence.REF_CONFLICT -> EngineeringState.CONFLICTED
            EngineeringEvidence.CI_CHECK_FAILED -> if (current == EngineeringState.CI_PENDING) EngineeringState.CI_FAILED else throw IllegalArgumentException("CI failure outside CI watch")
            EngineeringEvidence.EXECUTION_FAILED -> EngineeringState.FAILED
            EngineeringEvidence.USER_CANCELLED -> EngineeringState.CANCELLED
            else -> throw IllegalArgumentException("Invalid engineering transition: $current + $evidence")
        }
    }
}
