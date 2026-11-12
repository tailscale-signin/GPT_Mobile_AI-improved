package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.github.GitHubNativeApprovalProgress
import dev.chungjungsoo.gptmobile.data.github.GitHubNativeDecisionReceipt
import dev.chungjungsoo.gptmobile.data.permissions.ToolApproval
import dev.chungjungsoo.gptmobile.presentation.ui.chat.generationActivityLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubChatProgressProjectionTest {
    private val context = GitHubConversationContext("github", "owner", "repo", "feature", "a".repeat(40), "local")
    private val paused = run("original", 2, AgentRunStatus.WAITING_FOR_APPROVAL, 10)
    private val task = GitHubNativeApprovalProgress.Task("task", context.ref, "AWAITING_APPROVAL", 1, 2)
    private val receipt = GitHubNativeDecisionReceipt("task", 1, 2, context.ref, "Saved change", "b".repeat(40), false)

    @Test fun terminalApprovalStillWaitsWithoutAVisibleCardOrDecision() {
        val state = state(paused)

        val waiting = state.progressProjection(snapshot(tasks = listOf(task)))

        assertTrue(AgentRunStatus.WAITING_FOR_APPROVAL in AgentRunStatus.terminal)
        assertTrue(waiting.active)
        assertTrue(waiting.waiting)
        assertFalse(waiting.generating)
        assertEquals("Needs your approval", waiting.description)
        assertTrue(waiting.toolEvents.isEmpty())
        assertTrue(state.progressProjection().waiting)
        assertEquals(AgentRunStatus.WAITING_FOR_APPROVAL, state.runs.single().status)
    }

    @Test fun verifiedPublicationOrRejectionEndsTheUiPauseWithoutMutatingTheAttempt() {
        val state = state(paused)
        listOf(receipt, receipt.copy(sha = null)).forEach { decision ->
            val progress = state.progressProjection(snapshot(listOf(task.copy(state = if (decision.sha == null) "CANCELLED" else "COMPLETED")), listOf(decision)))

            assertFalse(progress.active)
            assertFalse(progress.waiting)
            assertFalse(progress.generating)
            assertEquals("Ready for a command", progress.description)
            assertEquals(AgentRunStatus.WAITING_FOR_APPROVAL, state.runs.single().status)
        }
    }

    @Test fun receiptForOneTaskCannotResolveAnotherPendingTaskOnTheSameSource() {
        listOf("AWAITING_APPROVAL", "COMMITTING").forEach { pendingState ->
            val native = snapshot(
                listOf(task.copy(state = "COMPLETED"), task.copy(taskId = "second", state = pendingState)),
                listOf(receipt)
            )

            val progress = state(paused).progressProjection(native)

            assertTrue(progress.active)
            assertTrue(progress.waiting)
            assertEquals(if (pendingState == "AWAITING_APPROVAL") "Needs your approval" else "Waiting", progress.description)
            assertTrue(state(paused).progressProjection(snapshot(listOf(task.copy(state = pendingState)), listOf(receipt))).waiting)
        }
    }

    @Test fun receiptMustMatchTheBoundTaskChatSourceAndBranch() {
        val mismatches = listOf(
            receipt.copy(taskId = "other"),
            receipt.copy(chatId = 99),
            receipt.copy(sourceMessageId = 3),
            receipt.copy(branch = "other")
        )
        mismatches.forEach { mismatch ->
            assertTrue(state(paused).progressProjection(snapshot(listOf(task.copy(state = "COMPLETED")), listOf(mismatch))).waiting)
        }
    }

    @Test fun decisionsFromAnotherWorkspaceOrChatCannotResolveThePause() {
        val resolved = snapshot(listOf(task.copy(state = "COMPLETED")), listOf(receipt))
        val mismatches = listOf(
            resolved.copy(workspace = context.copy(connectionUid = "other")),
            resolved.copy(workspace = context.copy(owner = "other")),
            resolved.copy(workspace = context.copy(repo = "other")),
            resolved.copy(workspace = context.copy(ref = "other")),
            resolved.copy(workspace = context.copy(mainProfileUid = "other")),
            resolved.copy(chatId = 99)
        )
        mismatches.forEach { mismatch -> assertTrue(state(paused).progressProjection(mismatch).waiting) }
        assertFalse(state(paused).copy(context = context.copy(commitSha = "c".repeat(40))).progressProjection(resolved).waiting)
    }

    @Test fun missingPendingBindingOrInvalidatedSnapshotDoesNotProveResolution() {
        val resolved = snapshot(listOf(task.copy(state = "COMPLETED")), listOf(receipt))
        val missingSource = task.copy(taskId = "unknown", chatId = null, sourceMessageId = null)

        assertTrue(state(paused).progressProjection(resolved.copy(tasks = resolved.tasks + missingSource)).waiting)
        assertTrue(state(paused).progressProjection(null).waiting)
        assertTrue(state(paused).progressProjection(resolved.copy(tasks = emptyList())).waiting)
    }

    @Test fun laterContinuationUsesItsOwnEventsTimelineAndTaskDespiteAnEarlierPause() {
        val continuation = run("continuation", 4, AgentRunStatus.RUNNING, 20)
        val oldEvent = event("old", paused.runId)
        val currentEvent = event("current", continuation.runId)
        val oldTimeline = AssistantTimelineItem(type = AssistantTimelineItemType.TEXT, content = "Old activity")
        val currentTimeline = AssistantTimelineItem(type = AssistantTimelineItemType.THINKING, content = "Current activity")
        val state = state(paused, continuation).copy(
            events = listOf(currentEvent, oldEvent),
            messages = listOf(message(continuation, currentTimeline), message(paused, oldTimeline))
        )
        val snapshots = listOf(
            snapshot(listOf(task.copy(state = "COMPLETED")), listOf(receipt)),
            snapshot(listOf(task))
        )
        snapshots.forEach { snapshot ->
            val progress = state.progressProjection(snapshot)

            assertTrue(progress.active)
            assertTrue(progress.generating)
            assertFalse(progress.waiting)
            assertEquals(continuation.userMessageId, progress.userMessageId)
            assertEquals(listOf(continuation), progress.taskRuns)
            assertEquals(listOf(currentEvent), progress.toolEvents)
            assertEquals(listOf(currentTimeline), progress.timeline)
            assertEquals(generationActivityLabel(listOf(currentEvent), listOf(currentTimeline)), progress.description)
            assertEquals(progress.description, state.progressDescription(snapshot))
        }
    }

    @Test fun aLaterUnresolvedNativeTaskWinsOverAnEarlierRunningAttempt() {
        val earlier = run("earlier", 1, AgentRunStatus.RUNNING, 5)

        val progress = state(earlier, paused).progressProjection(snapshot(listOf(task)))

        assertEquals(paused.userMessageId, progress.userMessageId)
        assertTrue(progress.waiting)
        assertEquals("Needs your approval", progress.description)
        assertTrue(progress.toolEvents.isEmpty())
    }

    @Test fun pendingNativeTaskOnTheCurrentRunningSourceStillTakesPriority() {
        val running = paused.copy(status = AgentRunStatus.RUNNING)

        val progress = state(running).progressProjection(snapshot(listOf(task)))

        assertTrue(progress.generating)
        assertTrue(progress.waiting)
        assertEquals("Needs your approval", progress.description)
    }

    @Test fun completedContinuationDoesNotResurrectTheResolvedOriginalPause() {
        val completed = run("continuation", 4, AgentRunStatus.COMPLETED, 20)

        val progress = state(paused, completed).progressProjection(snapshot(listOf(task.copy(state = "COMPLETED")), listOf(receipt)))

        assertFalse(progress.active)
        assertFalse(progress.waiting)
        assertEquals(completed.userMessageId, progress.userMessageId)
        assertEquals("Answer ready", progress.description)
    }

    @Test fun currentProviderWaitAndToolApprovalStillTakePriorityOverOperations() {
        val running = run("active", 4, AgentRunStatus.RUNNING, 20)
        val state = state(running).copy(events = listOf(event("event", running.runId)))

        assertEquals("Waiting", state.copy(runs = listOf(running.copy(status = AgentRunStatus.WAITING_PROVIDER_LIMIT))).progressDescription())
        assertEquals("Needs your approval", state.copy(approvals = listOf(ToolApproval("approval", running.runId, "github", "github_write", "hash"))).progressDescription())
    }

    private fun snapshot(tasks: List<GitHubNativeApprovalProgress.Task>, decisions: List<GitHubNativeDecisionReceipt> = emptyList()) =
        GitHubNativeApprovalProgress(context, 1, tasks, decisions)

    private fun state(vararg runs: AgentRun) = GitHubProgrammerState(context = context, chatId = 1, runs = runs.toList())

    private fun run(id: String, source: Int, status: String, created: Long) = AgentRun(id, 1, source, source + 100, "local", "CUSTOM", "model", status, createdAt = created)

    private fun event(id: String, runId: String) = ToolEvent(id, runId, 1, "call", "github", "GitHub", "github_read", "github_read__github_api", "{}", null, null, "RUNNING")

    private fun message(run: AgentRun, item: AssistantTimelineItem) = MessageV2(id = run.assistantMessageId, chatId = run.chatId, content = "", platformType = run.profileUid, currentRunId = run.runId, timeline = listOf(item))
}
