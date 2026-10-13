package dev.chungjungsoo.gptmobile.data.delegation

import kotlinx.serialization.Serializable

@Serializable enum class StageExecution { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELED, INTERRUPTED }

@Serializable enum class OutputQuality { NONE, MALFORMED, PARTIAL, USABLE }

@Serializable enum class StageReview { NOT_REQUESTED, PENDING, UNAVAILABLE, PASSED, REJECTED, INCOMPLETE }

/** Stored in the existing activity journal; artifacts remain in the research/message stores. */
@Serializable data class DelegationStageState(
    val execution: StageExecution,
    val output: OutputQuality = OutputQuality.NONE,
    val review: StageReview = StageReview.NOT_REQUESTED,
    val delivered: Boolean = false
)
