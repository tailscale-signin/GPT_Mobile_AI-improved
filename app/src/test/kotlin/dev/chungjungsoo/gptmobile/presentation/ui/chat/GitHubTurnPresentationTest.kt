package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.agent.tool.McpInputRequest
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.github.GitHubChatDecision
import dev.chungjungsoo.gptmobile.data.github.GitHubDecisionOption
import dev.chungjungsoo.gptmobile.data.permissions.ToolApproval
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubTurnPresentationTest {
    private val user = MessageV2(id = 11, chatId = 7, content = "Inspect the repository", platformType = null)
    private val response = MessageV2(id = 12, chatId = 7, content = "", platformType = "profile", currentRunId = "run")

    @Test fun terminalRunStateOverridesStaleActiveMembershipAndLoading() {
        AgentRunStatus.terminal.forEach { status ->
            assertEquals(status, GitHubTurnPresentation(false, false, false, false), presentation(run = run(status), activeRunIds = setOf("run"), isIdle = false))
        }
    }

    @Test fun durableActiveStatusAndFallbackMembershipKeepTheCurrentResponseActive() {
        listOf(AgentRunStatus.QUEUED, AgentRunStatus.RUNNING, AgentRunStatus.WAITING_PROVIDER_LIMIT, AgentRunStatus.WAITING_REMOTE_JOB).forEach { status ->
            assertEquals(status, GitHubTurnPresentation(true, false, false, true), presentation(run = run(status)))
        }
        assertEquals(GitHubTurnPresentation(true, false, false, true), presentation(activeRunIds = setOf("run")))
        assertEquals(GitHubTurnPresentation(false, false, false, false), presentation(activeRunIds = setOf("other")))
        assertFalse(presentation(assistantMessages = listOf(listOf(response.copy(currentRunId = null))), activeRunIds = setOf("run")).activeResponse)
    }

    @Test fun missingLatestResponseStartsOnlyWhileLoadingAndNeverBorrowsThePreviousTurn() {
        val users = listOf(user.copy(id = 9), user)
        val responses = listOf(listOf(response))
        assertEquals(GitHubTurnPresentation(false, false, false, false), presentation(users, responses, run(AgentRunStatus.RUNNING)))
        assertEquals(GitHubTurnPresentation(false, true, false, true), presentation(users, responses, run(AgentRunStatus.RUNNING), isIdle = false))
    }

    @Test fun approvalsAndMcpRequestsWaitOnlyForTheLatestUser() {
        val approval = ToolApproval("approval", "run", "github", "write_file", "hash")
        val request = McpInputRequest("input", "github", "Choose a file", JsonObject(emptyMap()), CompletableDeferred(), runId = "run")
        val waiting = GitHubTurnPresentation(false, false, true, true)
        assertEquals(waiting, presentation(run = run(AgentRunStatus.WAITING_FOR_APPROVAL), approvals = listOf(approval)))
        assertEquals(waiting, presentation(run = run(AgentRunStatus.WAITING_FOR_APPROVAL), inputRequests = listOf(request)))
        assertEquals(waiting, presentation(approvalPending = true))
        val earlier = run(AgentRunStatus.COMPLETED).copy(userMessageId = 9)
        assertEquals(GitHubTurnPresentation(false, false, false, false), presentation(run = earlier, approvals = listOf(approval), inputRequests = listOf(request)))
    }

    @Test fun visibleDecisionWaitsWithoutPretendingAnApprovalIsPending() {
        val decision = GitHubChatDecision("decision", 7, user.id, "profile", "workspace", "Which file?", "", listOf(GitHubDecisionOption("First"), GitHubDecisionOption("Second")))
        assertEquals(GitHubTurnPresentation(false, false, false, true), presentation(visibleDecisions = listOf(decision)))
        assertFalse(presentation(visibleDecisions = listOf(decision.copy(sourceMessageId = 9))).awaitingTurn)
    }

    @Test fun awaitingPresentationRequiresAUserAndGithubNonCombinedMode() {
        assertTrue(presentation(approvalPending = true).awaitingTurn)
        assertFalse(presentation(userMessages = emptyList(), approvalPending = true).awaitingTurn)
        assertFalse(presentation(approvalPending = true, githubPromptMode = false).awaitingTurn)
        assertFalse(presentation(approvalPending = true, conversationMode = ConversationMode.COMBINED).awaitingTurn)
    }

    @Test fun targetLookupSearchesEveryAssistantSlotAndRejectsPlaceholderIds() {
        val rows = listOf(emptyList(), listOf(response.copy(id = 0), response))
        assertTrue(hasTargetAssistantMessage(rows, response.id))
        assertFalse(hasTargetAssistantMessage(rows, user.id))
        assertFalse(hasTargetAssistantMessage(rows, 0))
        assertFalse(hasTargetAssistantMessage(rows, -1))
        assertFalse(hasTargetAssistantMessage(emptyList(), response.id))
    }

    private fun run(status: String) = AgentRun("run", 7, user.id, response.id, "profile", "provider", "model", status)

    private fun presentation(
        userMessages: List<MessageV2> = listOf(user),
        assistantMessages: List<List<MessageV2>> = listOf(listOf(response)),
        run: AgentRun? = null,
        activeRunIds: Set<String> = emptySet(),
        isIdle: Boolean = true,
        approvalPending: Boolean = false,
        approvals: List<ToolApproval> = emptyList(),
        inputRequests: List<McpInputRequest> = emptyList(),
        visibleDecisions: List<GitHubChatDecision> = emptyList(),
        githubPromptMode: Boolean = true,
        conversationMode: String = ConversationMode.GATEWAY
    ) = gitHubTurnPresentation(userMessages, assistantMessages, run?.let { mapOf(it.runId to it) }.orEmpty(), activeRunIds, isIdle, approvalPending, approvals, inputRequests, visibleDecisions, githubPromptMode, conversationMode)
}
