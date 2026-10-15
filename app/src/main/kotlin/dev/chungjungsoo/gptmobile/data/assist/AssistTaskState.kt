package dev.chungjungsoo.gptmobile.data.assist

import kotlinx.serialization.Serializable

@Serializable
enum class AssistAdmission { OFF, CHECKING, ELIGIBLE, INELIGIBLE, UNKNOWN_LEGACY }

@Serializable
enum class AssistExecution { NOT_STARTED, QUEUED, PREPARING, RUNNING, COMPLETED, SKIPPED, FAILED, TIMED_OUT, CANCELLED, INTERRUPTED }

@Serializable
enum class AssistDelivery { NONE, PRODUCED, VALIDATED, REJECTED, INCLUDED_IN_REQUEST, NOT_INCLUDED, INCLUSION_UNKNOWN }

/** Response-owned operational metadata. Never populated from global live readiness. */
@Serializable
data class AssistTaskState(
    val schemaVersion: Int = 2,
    val taskId: String,
    val sequence: Long = 0,
    val profileUid: String? = null,
    val model: String? = null,
    val admission: AssistAdmission = AssistAdmission.CHECKING,
    val execution: AssistExecution = AssistExecution.NOT_STARTED,
    val delivery: AssistDelivery = AssistDelivery.NONE,
    val reason: String = "WAITING_FOR_ELIGIBLE_TASK",
    val taskType: String? = null,
    val physicalAttempts: Int = 0,
    val producerInvocationId: String? = null,
    val sourceSnapshot: String? = null,
    val selectedSpanIds: List<String> = emptyList(),
    val examinedSpanIds: List<String> = emptyList(),
    val unprocessedSpanIds: List<String> = emptyList(),
    val runtimeFingerprint: String? = null,
    val modelLoaded: Boolean? = null,
    val requestedAccelerator: String? = null,
    val observedAccelerator: String? = null,
    val observedBackend: String? = null,
    val reused: Boolean = false,
    val policySnapshot: EffectiveAssistPolicy? = null,
    val sharedWorkId: String? = null,
    val subscriberId: String? = null
) {
    val terminal: Boolean get() = execution in setOf(AssistExecution.COMPLETED, AssistExecution.SKIPPED, AssistExecution.FAILED, AssistExecution.TIMED_OUT, AssistExecution.CANCELLED, AssistExecution.INTERRUPTED)

    fun accepts(next: AssistTaskState): Boolean = taskId == next.taskId && next.sequence > sequence && (!terminal || next.execution == execution && next.delivery != AssistDelivery.NONE)
}
