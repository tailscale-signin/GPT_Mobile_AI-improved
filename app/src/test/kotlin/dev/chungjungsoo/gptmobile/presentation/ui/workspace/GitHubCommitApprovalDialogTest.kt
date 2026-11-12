package dev.chungjungsoo.gptmobile.presentation.ui.workspace

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.chungjungsoo.gptmobile.data.agent.tool.EngineeringRunRow
import dev.chungjungsoo.gptmobile.data.agent.tool.EngineeringTaskRow
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h1000dp-mdpi")
class GitHubCommitApprovalDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun inlineCardOpensDetailsWithoutApprovalAndCommitsTheFrozenDigest() {
        val model = mockk<GitHubReviewViewModel>(relaxed = true)
        val run = EngineeringRunRow("task", "github", "[]", "frozen-digest", "Fix answer", 0, "main")
        every { model.pendingEngineeringTask } returns MutableStateFlow("task")
        every { model.selectedEngineeringRun } returns MutableStateFlow(run)
        every { model.engineeringTasks } returns MutableStateFlow(emptyList())
        every { model.review } returns MutableStateFlow(GitHubReview("github", "owner", "repo", "feature", "a".repeat(40), files = listOf(GitHubFileProposal("A.kt", "41", "42"))))
        every { model.busy } returns MutableStateFlow(false)
        every { model.notice } returns MutableStateFlow("")
        compose.setContent { MaterialTheme { GitHubConversationApprovalContent(model, GitHubConversationApprovalState(31, "task", awaitingApproval = true, available = true)) {} } }
        compose.onAllNodesWithTag("github_chat_approval").assertCountEquals(1)
        compose.onNodeWithText("Review proposed changes").assertIsDisplayed()
        compose.onNodeWithText(
            "Task task · owner/repo · feature\n" +
                "Base aaaaaaaaaaaa · Patch frozen-diges\n" +
                "Risk: build and CI are not verified by this approval."
        ).assertIsDisplayed()
        compose.onNodeWithText("@@ changed text @@\n- 41\n+ 42").assertDoesNotExist()
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("@@ changed text @@\n- 41\n+ 42").assertIsDisplayed()
        verify(exactly = 0) { model.approveCommit(any(), any(), any()) }
        compose.onNodeWithText("Close").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Commit").performClick()
        verify(exactly = 1) { model.approveCommit("task", "frozen-digest", any()) }
        verify(atLeast = 1) { model.recordApprovalEvent("task", "approval_card_shown") }
        verify(exactly = 0) { model.rejectCommit(any()) }
    }

    @Test fun ordinaryChatFixtureOffersKeepAndRejectForTheSameSavedTask() {
        val model = mockk<GitHubReviewViewModel>(relaxed = true)
        val branch = "codex-self-test/${"a".repeat(20)}"
        val task = EngineeringTaskRow("task", "owner", "repo", branch, "a".repeat(40), state = "AWAITING_APPROVAL", updatedAtEpochMs = 0)
        every { model.pendingEngineeringTask } returns MutableStateFlow("task")
        every { model.selectedEngineeringRun } returns MutableStateFlow(EngineeringRunRow("task", "github", "[]", "frozen-digest", "Fix answer", 0, "main"))
        every { model.engineeringTasks } returns MutableStateFlow(listOf(task))
        every { model.review } returns MutableStateFlow(GitHubReview("github", "owner", "repo", branch, task.baseSha, files = listOf(GitHubFileProposal("A.kt", "41", "42"))))
        every { model.busy } returns MutableStateFlow(false)
        every { model.notice } returns MutableStateFlow("")

        compose.setContent { MaterialTheme { GitHubConversationApprovalContent(model, GitHubConversationApprovalState(31, "task", awaitingApproval = true, available = true)) {} } }
        compose.onNodeWithText("Keep fixture").performClick()
        compose.onNodeWithText("Reject").performClick()
        verify(exactly = 1) { model.keepFixture("task") }
        verify(exactly = 1) { model.rejectCommit("task") }
        verify(exactly = 0) { model.approveCommit(any(), any(), any()) }
    }

    @Test fun ordinaryChatReopensTheSavedReviewWithoutPreparingAnotherPatch() {
        val model = mockk<GitHubReviewViewModel>(relaxed = true)
        every { model.pendingEngineeringTask } returns MutableStateFlow(null)
        every { model.busy } returns MutableStateFlow(false)
        compose.setContent { MaterialTheme { GitHubConversationApprovalContent(model, GitHubConversationApprovalState(31, "task", awaitingApproval = true, available = true)) {} } }
        compose.onNodeWithText("Review approval").performClick()
        verify(exactly = 1) { model.reopenConversationApproval("task") }
        verify(exactly = 0) { model.approveCommit(any(), any(), any()) }
    }

    @Test fun disabledConnectionDoesNotExposeCommitOrGenericRetry() {
        val model = mockk<GitHubReviewViewModel>(relaxed = true)
        every { model.pendingEngineeringTask } returns MutableStateFlow(null)
        every { model.busy } returns MutableStateFlow(false)
        compose.setContent { MaterialTheme { GitHubConversationApprovalContent(model, GitHubConversationApprovalState(31, "task", awaitingApproval = true, available = false)) {} } }
        compose.onNodeWithText("Review GitHub connection").assertIsDisplayed()
        compose.onNodeWithText("Commit").assertDoesNotExist()
        compose.onNodeWithText("Review approval").assertDoesNotExist()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test fun onePopupShowsBranchAndChangesAndApprovesOnce() {
        val model = mockk<GitHubReviewViewModel>(relaxed = true)
        val task = EngineeringTaskRow("task", "owner", "repo", "fix/actual-destination", "a".repeat(40), updatedAtEpochMs = 0)
        val run = EngineeringRunRow("task", "github", "[]", "digest", "Repair cleanup", 0, "main")
        every { model.pendingEngineeringTask } returns MutableStateFlow("task")
        every { model.selectedEngineeringRun } returns MutableStateFlow(run)
        every { model.engineeringTasks } returns MutableStateFlow(listOf(task))
        every { model.review } returns MutableStateFlow(GitHubReview("github", "owner", "repo", task.workingBranch, task.baseSha, files = listOf(GitHubFileProposal("cleanup.kt", "before", "after"), GitHubFileProposal("old.kt", "old", "", delete = true))))
        every { model.busy } returns MutableStateFlow(false)
        compose.setContent { MaterialTheme { GitHubCommitApprovalDialog(model) } }
        compose.onNodeWithText("Approve commit?").assertIsDisplayed()
        compose.onNodeWithText("Branch: fix/actual-destination").assertIsDisplayed()
        compose.onNodeWithText("• Update cleanup.kt").assertIsDisplayed()
        compose.onNodeWithText("• Delete old.kt").assertIsDisplayed()
        compose.onNodeWithText("Approve & continue").performClick()
        verify(exactly = 1) { model.approveCommit("task", "digest", any()) }
    }
}
