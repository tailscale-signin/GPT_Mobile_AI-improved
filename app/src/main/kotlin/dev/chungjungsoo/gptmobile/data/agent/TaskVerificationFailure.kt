package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.Serializable

/** An unfinished task needs retained-evidence reconciliation, not a provider retry or cooldown. */
@Serializable
data class TaskVerificationFailure(
    val category: String,
    val phase: String = "verification",
    val verificationDispatched: Boolean? = null
) {
    companion object {
        fun from(failure: ProfileFailureRecord): TaskVerificationFailure? = if (failure.isTaskVerification) {
            TaskVerificationFailure(failure.category, failure.phase, failure.dispatched)
        } else {
            null
        }
    }
}
