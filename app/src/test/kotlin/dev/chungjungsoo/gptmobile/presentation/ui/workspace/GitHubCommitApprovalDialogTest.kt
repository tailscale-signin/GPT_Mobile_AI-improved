package dev.chungjungsoo.gptmobile.presentation.ui.workspace

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
