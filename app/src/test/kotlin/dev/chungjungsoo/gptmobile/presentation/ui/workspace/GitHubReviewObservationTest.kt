package dev.chungjungsoo.gptmobile.presentation.ui.workspace

import android.app.Application
import androidx.lifecycle.viewModelScope
import dev.chungjungsoo.gptmobile.data.agent.tool.EngineeringFilePatch
import dev.chungjungsoo.gptmobile.data.agent.tool.EngineeringRunRow
import dev.chungjungsoo.gptmobile.data.agent.tool.EngineeringTaskRow
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubEngineeringAuthorization
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubEngineeringController
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceDao
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GitHubReviewObservationTest {
    private val scope = GitHubConversationContext("github", "owner", "repo", "feature", "a".repeat(40), "profile")
    private val engineering = mockk<GitHubEngineeringController>()
    private lateinit var model: GitHubReviewViewModel

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val connections = mockk<ToolConnectionRepository>()
        val workspace = mockk<WorkspaceRepository>()
        val dao = mockk<WorkspaceDao>(relaxed = true)
        every { workspace.dao } returns dao
        every { workspace.json } returns Json
        coEvery { dao.list("github") } returns emptyList()
        coEvery { connections.listConnections() } returns emptyList()
        coEvery { engineering.recent() } returns emptyList()
        model = GitHubReviewViewModel(connections, mockk(), workspace, engineering)
    }

    @After fun cleanup() {
        model.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun invalidationWhileBusyIsDeliveredAfterTheActionFinishes() = runBlocking {
        val task = EngineeringTaskRow("task", scope.owner, scope.repo, scope.ref, scope.commitSha, state = "AWAITING_APPROVAL", updatedAtEpochMs = 0)
        val patch = GitHubEngineeringAuthorization.patchJson(listOf(EngineeringFilePatch("A.kt", "41", "42")))
        val run = EngineeringRunRow("task", "github", patch, "digest", "Fix answer", 0, "main")
        coEvery { engineering.recent() } returns listOf(task)
        coEvery { engineering.run("task") } returns run
        coEvery { engineering.events("task") } returns emptyList()
        val observed = CompletableDeferred<Unit>()
        every { engineering.observeChanges() } returns flow {
            observed.complete(Unit)
            emit(setOf("engineering_tasks"))
        }
        model.restrictTo(scope)
        model.busy.value = true
        val observer = launch { model.observeWorkspaceApproval(scope) }
        try {
            withTimeout(5000) { observed.await() }
            assertNull(model.pendingEngineeringTask.value)
            model.busy.value = false
            withTimeout(5000) { model.pendingEngineeringTask.first { it == "task" } }
            assertEquals("42", model.review.value?.files?.single()?.after)
            assertEquals("digest", model.selectedEngineeringRun.value?.patchDigest)
        } finally {
            observer.cancelAndJoin()
        }
    }

    @Test fun rejectedInlineEditLeavesTheVisibleFrozenPatchUnchanged() {
        val original = GitHubReview("github", scope.owner, scope.repo, scope.ref, scope.commitSha, files = listOf(GitHubFileProposal("A.kt", "41", "42")))
        model.review.value = original
        model.pendingEngineeringTask.value = "task"
        coEvery { engineering.run("task") } returns EngineeringRunRow("task", "github", "[]", "digest", "Fix answer", 0, "main")
        coEvery { engineering.prepare(any(), any(), any(), any(), any(), any(), any(), any()) } throws IllegalArgumentException("Branch moved")
        model.editAndRestage("task", "A.kt", "42", "43")
        assertEquals(original, model.review.value)
        assertEquals("task", model.pendingEngineeringTask.value)
        assertTrue(model.notice.value.contains("Branch moved"))
    }
}
