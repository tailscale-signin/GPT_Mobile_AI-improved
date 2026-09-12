package dev.chungjungsoo.gptmobile.data.model

import kotlinx.serialization.Serializable

/**
 * Plan-and-Execute Agent workflow model.
 * Enables multi-step autonomous task tracking, intermediate tool step updates,
 * and structured sub-goal execution for agentic interactions.
 */
@Serializable
data class AgentPlan(
    val id: String,
    val title: String,
    val steps: List<AgentTaskStep> = emptyList(),
    val status: AgentPlanStatus = AgentPlanStatus.IN_PROGRESS
)

@Serializable
enum class AgentPlanStatus {
    NOT_STARTED,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    WAITING_USER_INPUT
}

@Serializable
data class AgentTaskStep(
    val stepNumber: Int,
    val description: String,
    val status: AgentStepStatus = AgentStepStatus.PENDING,
    val toolName: String? = null,
    val resultSnippet: String? = null
)

@Serializable
enum class AgentStepStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED
}
