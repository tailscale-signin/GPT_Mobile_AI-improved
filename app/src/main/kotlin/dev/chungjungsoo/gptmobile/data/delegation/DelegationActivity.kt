package dev.chungjungsoo.gptmobile.data.delegation

import kotlinx.serialization.Serializable

@Serializable
enum class DelegationEventKind { INVOCATION_LINKED, POLICY_RESOLVED, STEP_QUEUED, REQUEST_STARTED, OUTPUT_OBSERVED, TOOL_STARTED, REVIEW_FINISHED, FALLBACK_CHOSEN, STEP_FINISHED, RUN_TERMINAL }

@Serializable
enum class DelegationRole { PRIMARY, HELPER, REVIEWER }

@Serializable
enum class DelegationStepStatus { RUNNING, RETURNED_OUTPUT, NO_OUTPUT, CANCELED, FAILED, INTERRUPTED }

/** Metadata only. No task, prompt, output, source URL, endpoint, credential or exception text. */
@Serializable
data class DelegationEvent(
    val kind: DelegationEventKind,
    val atMs: Long = System.currentTimeMillis(),
    val stepId: String? = null,
    val role: DelegationRole = DelegationRole.HELPER,
    val profileUid: String? = null,
    val status: DelegationStepStatus? = null,
    val elapsedMs: Long? = null,
    val queueMs: Long? = null,
    val firstOutputMs: Long? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val usageProvenance: String = "unavailable",
    val reviewScore: Int? = null,
    val reviewVerdict: String? = null,
    val invocationId: String? = null,
    val attemptId: String? = null,
    val rootWorkId: String? = null
)

@Serializable
data class DelegationActivity(
    val schema: Int = 2,
    val runId: String,
    val chatId: Int,
    val policyRevision: String,
    val primaryUid: String,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val status: DelegationStepStatus = DelegationStepStatus.RUNNING,
    val events: List<DelegationEvent> = emptyList(),
    val omittedEvents: Int = 0
) {
    fun append(event: DelegationEvent): DelegationActivity = copy(
        events = (events + event).takeLast(200),
        omittedEvents = omittedEvents + if (events.size >= 200) 1 else 0,
        finishedAt = if (event.kind == DelegationEventKind.RUN_TERMINAL) event.atMs else finishedAt,
        status = if (event.kind == DelegationEventKind.RUN_TERMINAL) event.status ?: DelegationStepStatus.INTERRUPTED else status
    )
}
