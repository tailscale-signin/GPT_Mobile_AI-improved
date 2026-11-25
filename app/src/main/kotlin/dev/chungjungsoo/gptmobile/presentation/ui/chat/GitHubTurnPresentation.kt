package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.agent.tool.McpInputRequest
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.github.GitHubChatDecision
import dev.chungjungsoo.gptmobile.data.github.GitHubNativeApprovalProgress
import dev.chungjungsoo.gptmobile.data.permissions.ToolApproval

internal data class GitHubTurnPresentation(
    val activeResponse: Boolean,
    val responseStarting: Boolean,
    val pendingInput: Boolean,
    val awaitingTurn: Boolean
)

/** Older approval records may lack a source message. Keep their chat-wide pause until it can be resolved. */
internal data class GitHubTurnApproval(val chatId: Int, val sourceMessageId: Int?) {
    fun appliesTo(message: MessageV2?): Boolean = message != null && message.chatId == chatId && (sourceMessageId == null || sourceMessageId == message.id)
}

internal fun pendingGitHubTurnApproval(chatId: Int, taskId: String?, pending: Boolean, progress: GitHubNativeApprovalProgress?): GitHubTurnApproval? {
    if (!pending) return null
    val source = progress?.takeIf { it.chatId == chatId }?.tasks?.singleOrNull { it.taskId == taskId }
    return GitHubTurnApproval(source?.chatId ?: chatId, source?.sourceMessageId)
}

/** Keep collection scans out of the large Compose method without moving its state ownership. */
internal fun gitHubTurnPresentation(
    userMessages: List<MessageV2>,
    assistantMessages: List<List<MessageV2>>,
    agentRunsById: Map<String, AgentRun>,
    activeRunIds: Set<String>,
    isIdle: Boolean,
    nativeApproval: GitHubTurnApproval?,
    approvals: List<ToolApproval>,
    inputRequests: List<McpInputRequest>,
    visibleDecisions: List<GitHubChatDecision>,
    githubPromptMode: Boolean,
    conversationMode: String
): GitHubTurnPresentation {
    val latestUser = userMessages.lastOrNull()
    val latestResponses = assistantMessages.getOrNull(userMessages.lastIndex).orEmpty()
    val activeResponse = latestResponses.any { response ->
        val runId = response.currentRunId ?: return@any false
        val status = agentRunsById[runId]?.status
        status?.let { it !in AgentRunStatus.terminal } ?: (runId in activeRunIds)
    }
    val responseStarting = !isIdle &&
        latestResponses.none { response -> response.currentRunId?.let(agentRunsById::get)?.status in AgentRunStatus.terminal }
    val pendingInput = nativeApproval?.appliesTo(latestUser) == true ||
        approvals.any { agentRunsById[it.runId]?.userMessageId == latestUser?.id } ||
        inputRequests.any { it.runId?.let(agentRunsById::get)?.userMessageId == latestUser?.id }
    val waitingForInput = pendingInput || visibleDecisions.any { it.sourceMessageId == latestUser?.id }
    val awaitingTurn = latestUser != null &&
        githubPromptMode &&
        conversationMode != ConversationMode.COMBINED &&
        (waitingForInput || activeResponse || responseStarting)
    return GitHubTurnPresentation(activeResponse, responseStarting, pendingInput, awaitingTurn)
}

internal fun hasTargetAssistantMessage(assistantMessages: List<List<MessageV2>>, targetMessageId: Int): Boolean =
    targetMessageId > 0 && assistantMessages.any { responses -> responses.any { it.id == targetMessageId } }
