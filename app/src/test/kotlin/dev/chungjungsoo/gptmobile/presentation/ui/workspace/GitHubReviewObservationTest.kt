package dev.chungjungsoo.gptmobile.presentation.ui.workspace

import android.app.Application
import androidx.lifecycle.viewModelScope
import dev.chungjungsoo.gptmobile.data.agent.tool.EngineeringFilePatch
import dev.chungjungsoo.gptmobile.data.agent.tool.EngineeringRunRow
import dev.chungjungsoo.gptmobile.data.agent.tool.EngineeringTaskRow
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubEngineeringAuthorization
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubEngineeringController
import dev.chungjungsoo.gptmobile.data.github.GitHubApprovalBinding
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceDao
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRepository
import io.mockk.coEvery
import io.mockk.coVerify
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
import org.junit.Assert.assertFalse
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
    private val approvalStore = GitHubRepositoryStateStore()
    private lateinit var connections: ToolConnectionRepository
    private lateinit var workspace: WorkspaceRepository
    private lateinit var model: GitHubReviewViewModel

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        connections = mockk()
        workspace = mockk()
        val dao = mockk<WorkspaceDao>(relaxed = true)
        every { workspace.dao } returns dao
        every { workspace.json } returns Json
        coEvery { dao.list("github") } returns emptyList()
        coEvery { connections.listConnections() } returns emptyList()
        coEvery { engineering.recent() } returns emptyList()
        coEvery { engineering.recoveryTasks() } coAnswers { engineering.recent() }
        coEvery { engineering.verifiedPublication(any()) } returns null
        model = GitHubReviewViewModel(connections, mockk(), workspace, engineering, gitHubRepositoryState = approvalStore)
    }

    @After fun cleanup() {
        model.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun standardChatRestoresOnlyItsBoundFrozenPatchAcrossRestart() = runBlocking {
        val task = savedConversationTask()
        val foreign = task.copy(taskId = "foreign")
        val frozen = savedConversationRun()
        coEvery { engineering.recoveryTasks() } returns listOf(foreign, task)
        coEvery { engineering.run("task") } returns frozen
        coEvery { engineering.run("foreign") } returns frozen.copy(taskId = "foreign")
        coEvery { engineering.events("task") } returns emptyList()
        GitHubApprovalBinding.bind(approvalStore, "task", scope, task.workingBranch, 31, 42, expectedHeadSha = task.baseSha, patchDigest = frozen.patchDigest)
        GitHubApprovalBinding.bind(approvalStore, "foreign", scope, task.workingBranch, 99, 42, expectedHeadSha = task.baseSha, patchDigest = frozen.patchDigest)

        // An active task remains reviewable after it has fallen outside recent history.
        model.refreshConversationApproval(31, setOf("github"))
        assertEquals("task", model.pendingEngineeringTask.value)
        assertEquals(listOf("task"), model.engineeringTasks.value.map { it.taskId })
        assertEquals(scope, model.programmerScope.value)
        assertEquals(frozen.patchDigest, model.selectedEngineeringRun.value?.patchDigest)
        assertEquals("42", model.review.value?.files?.single()?.after)

        model.viewModelScope.cancel()
        model = GitHubReviewViewModel(connections, mockk(), workspace, engineering, gitHubRepositoryState = approvalStore)
        model.refreshConversationApproval(31, setOf("github"))
        assertEquals("task", model.pendingEngineeringTask.value)
        assertTrue(model.conversationApproval.value?.awaitingApproval == true)
        coVerify(exactly = 0) { engineering.approve(any(), any()) }
        coVerify(exactly = 0) { engineering.execute(any()) }
    }

    @Test fun savedApprovalDeepLinkRejectsAnotherChatAndChangedFrozenDigest() = runBlocking {
        val task = savedConversationTask()
        val frozen = savedConversationRun()
        coEvery { engineering.recoveryTasks() } returns listOf(task)
        coEvery { engineering.run("task") } returns frozen
        GitHubApprovalBinding.bind(approvalStore, "task", scope, task.workingBranch, 31, 42, expectedHeadSha = task.baseSha, patchDigest = frozen.patchDigest)

        model.refreshConversationApproval(99, setOf("github"), "task")
        assertNull(model.pendingEngineeringTask.value)
        assertNull(model.programmerScope.value)
        assertTrue(model.conversationApproval.value?.message?.contains("unavailable") == true)

        coEvery { engineering.run("task") } returns frozen.copy(patchDigest = "b".repeat(64))
        model.refreshConversationApproval(31, setOf("github"), "task")
        assertNull(model.pendingEngineeringTask.value)
        assertFalse(model.conversationApproval.value?.available == true)
        coVerify(exactly = 0) { engineering.approve(any(), any()) }
    }

    @Test fun disabledConnectionPreservesSavedApprovalUntilAccessIsRestored() = runBlocking {
        val task = savedConversationTask()
        val frozen = savedConversationRun()
        coEvery { engineering.recoveryTasks() } returns listOf(task)
        coEvery { engineering.run("task") } returns frozen
        coEvery { engineering.events("task") } returns emptyList()
        GitHubApprovalBinding.bind(approvalStore, "task", scope, task.workingBranch, 31, 42, expectedHeadSha = task.baseSha, patchDigest = frozen.patchDigest)

        model.refreshConversationApproval(31, emptySet())
        assertTrue(model.conversationApproval.value?.awaitingApproval == true)
        assertFalse(model.conversationApproval.value?.available == true)
        assertNull(model.pendingEngineeringTask.value)
        assertNull(model.review.value)

        model.refreshConversationApproval(31, setOf("github"))
        assertTrue(model.conversationApproval.value?.available == true)
        assertEquals("task", model.pendingEngineeringTask.value)
    }

    @Test fun ordinaryChatObservesDurableBindingsCreatedAfterTheTask() = runBlocking {
        val task = savedConversationTask()
        val frozen = savedConversationRun()
        val inspected = CompletableDeferred<Unit>()
        coEvery { engineering.recoveryTasks() } coAnswers {
            inspected.complete(Unit)
            listOf(task)
        }
        coEvery { engineering.run("task") } returns frozen
        coEvery { engineering.events("task") } returns emptyList()
        every { engineering.observeChanges() } returns flow { emit(setOf("engineering_tasks")) }
        val observer = launch { model.observeConversationApproval(31, setOf("github")) }
        try {
            withTimeout(5000) { inspected.await() }
            assertNull(model.pendingEngineeringTask.value)
            GitHubApprovalBinding.bind(approvalStore, "task", scope, task.workingBranch, 31, 42, expectedHeadSha = task.baseSha, patchDigest = frozen.patchDigest)
            withTimeout(5000) { model.pendingEngineeringTask.first { it == "task" } }
            assertEquals("42", model.review.value?.files?.single()?.after)
        } finally {
            observer.cancelAndJoin()
        }
    }

    private fun savedConversationTask() = EngineeringTaskRow("task", scope.owner, scope.repo, "codex-self-test/${"a".repeat(20)}", scope.commitSha, state = "AWAITING_APPROVAL", updatedAtEpochMs = 0)

    private fun savedConversationRun() = EngineeringRunRow(
        "task",
        "github",
        GitHubEngineeringAuthorization.patchJson(listOf(EngineeringFilePatch("A.kt", "41", "42"))),
        "a".repeat(64),
        "Fix answer",
        0,
        "main"
    )

    @Test fun automaticPatchLeavesProtectedDestinationsAndHighImpactChangesForReview() {
        val fixtureId = "a".repeat(20)
        val fixtureBranch = "codex-self-test/$fixtureId"
        val source = GitHubFileProposal("docs/codex-self-test/$fixtureId/answer.md", "41", "42")
        assertTrue(eligibleAutomaticPatch(fixtureBranch, "main", listOf(source)))
        assertFalse(eligibleAutomaticPatch("fix/answer", "main", listOf(source)))
        assertFalse(eligibleAutomaticPatch("main", "main", listOf(source)))
        assertFalse(eligibleAutomaticPatch(fixtureBranch, fixtureBranch, listOf(source)))
        assertFalse(eligibleAutomaticPatch(fixtureBranch, "", listOf(source)))
        assertFalse(eligibleAutomaticPatch(fixtureBranch, "main", listOf(source.copy(delete = true))))
        assertFalse(eligibleAutomaticPatch(fixtureBranch, "main", listOf(source.copy(path = ".github/workflows/android.yml"))))
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
        coEvery { engineering.task("task") } returns EngineeringTaskRow("task", scope.owner, scope.repo, scope.ref, scope.commitSha, state = "AWAITING_APPROVAL", updatedAtEpochMs = 0)
        coEvery { engineering.run("task") } returns EngineeringRunRow("task", "github", "[]", "digest", "Fix answer", 0, "main")
        coEvery { engineering.prepare(any(), any(), any(), any(), any(), any(), any(), any()) } throws IllegalArgumentException("Branch moved")
        model.editAndRestage("task", "A.kt", "42", "43")
        assertEquals(original, model.review.value)
        assertEquals("task", model.pendingEngineeringTask.value)
        assertTrue(model.notice.value.contains("Branch moved"))
    }

    @Test fun headerProgressRetainsBothBoundTasksWhenTheApprovalCardIsDismissed() = runBlocking {
        val task = EngineeringTaskRow("task", scope.owner, scope.repo, scope.ref, scope.commitSha, state = "AWAITING_APPROVAL", updatedAtEpochMs = 0)
        val second = task.copy(taskId = "second", state = "COMMITTING")
        val run = EngineeringRunRow("task", "github", "[]", "digest", "Fix answer", 0, "main")
        coEvery { engineering.recent() } returns listOf(task, second)
        coEvery { engineering.run("task") } returns run
        coEvery { engineering.run("second") } returns run.copy(taskId = "second")
        GitHubApprovalBinding.bind(approvalStore, "task", scope, scope.ref, 31, 42)
        GitHubApprovalBinding.bind(approvalStore, "second", scope, scope.ref, 31, 42)

        model.restrictTo(scope, 31)
        val progress = withTimeout(5000) { model.nativeApprovalProgress.first { it?.tasks?.size == 2 } }!!
        model.pendingEngineeringTask.value = "task"
        model.dismissApproval()

        assertNull(model.pendingEngineeringTask.value)
        assertEquals(progress, model.nativeApprovalProgress.value)
        assertTrue(progress.matches(scope, 31))
        assertEquals(setOf("task", "second"), progress.tasks.map { it.taskId }.toSet())
        assertTrue(progress.tasks.all { it.chatId == 31 && it.sourceMessageId == 42 && it.pending })
    }

    @Test fun headerProgressIsInvalidatedOnRefreshFailureAndWorkspaceChange() = runBlocking {
        val task = EngineeringTaskRow("task", scope.owner, scope.repo, scope.ref, scope.commitSha, state = "AWAITING_APPROVAL", updatedAtEpochMs = 0)
        val run = EngineeringRunRow("task", "github", "[]", "digest", "Fix answer", 0, "main")
        coEvery { engineering.recent() } returns listOf(task)
        coEvery { engineering.run("task") } returns run
        GitHubApprovalBinding.bind(approvalStore, "task", scope, scope.ref, 31, 42)
        model.restrictTo(scope, 31)
        withTimeout(5000) { model.nativeApprovalProgress.first { it != null } }

        coEvery { engineering.recent() } throws IllegalStateException("Refresh unavailable")
        model.refreshEngineeringTasks()
        withTimeout(5000) { model.nativeApprovalProgress.first { it == null } }
        assertEquals("Refresh unavailable", model.notice.value)
        coEvery { engineering.recent() } returns listOf(task)
        model.refreshEngineeringTasks()
        withTimeout(5000) { model.nativeApprovalProgress.first { it != null } }

        val switched = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { engineering.recent() } coAnswers {
            switched.complete(Unit)
            release.await()
            emptyList()
        }
        val other = scope.copy(repo = "other")
        model.restrictTo(other, 31)
        try {
            assertNull(model.nativeApprovalProgress.value)
            withTimeout(5000) { switched.await() }
        } finally {
            release.complete(Unit)
        }
        val changed = withTimeout(5000) { model.nativeApprovalProgress.first { it?.workspace == other } }!!
        assertFalse(changed.matches(scope, 31))
        assertTrue(changed.tasks.isEmpty())
    }

    @Test fun unchangedInlineEditRetainsTheOriginalTaskWithoutPreparingOrCancelling() {
        val original = GitHubReview("github", scope.owner, scope.repo, scope.ref, scope.commitSha, files = listOf(GitHubFileProposal("A.kt", "41", "42")))
        model.review.value = original
        model.pendingEngineeringTask.value = "task"

        model.editAndRestage("task", "A.kt", "42", "42")

        assertEquals(original, model.review.value)
        assertEquals("task", model.pendingEngineeringTask.value)
        assertTrue(model.notice.value.contains("unchanged"))
        coVerify(exactly = 0) { engineering.prepare(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { engineering.cancel(any()) }
    }

    @Test fun editedSavedChatApprovalBindsTheNewPatchBeforeRetiringTheOriginal() = runBlocking {
        verifySavedChatEdit(bindingConflict = false)
    }

    @Test fun failedReplacementBindingLeavesTheOriginalSavedChatApprovalRecoverable() = runBlocking {
        verifySavedChatEdit(bindingConflict = true)
    }

    private suspend fun verifySavedChatEdit(bindingConflict: Boolean) {
        val original = GitHubReview("github", scope.owner, scope.repo, scope.ref, scope.commitSha, files = listOf(GitHubFileProposal("A.kt", "41", "42")))
        var originalTask = EngineeringTaskRow("task", scope.owner, scope.repo, scope.ref, scope.commitSha, requiresHardwareVerification = true, state = "AWAITING_APPROVAL", updatedAtEpochMs = 0)
        val replacementTask = originalTask.copy(taskId = "replacement")
        val originalRun = EngineeringRunRow("task", "github", GitHubEngineeringAuthorization.patchJson(listOf(EngineeringFilePatch("A.kt", "41", "42"))), "a".repeat(64), "Fix answer", 0, "main")
        val replacementRun = originalRun.copy(taskId = "replacement", patchJson = GitHubEngineeringAuthorization.patchJson(listOf(EngineeringFilePatch("A.kt", "41", "43"))), patchDigest = "b".repeat(64))
        coEvery { engineering.task("task") } answers { originalTask }
        coEvery { engineering.run("task") } returns originalRun
        coEvery { engineering.run("replacement") } returns replacementRun
        coEvery { engineering.recent() } answers { listOf(originalTask, replacementTask) }
        coEvery { engineering.events(any()) } returns emptyList()
        coEvery { engineering.verifiedPublication(any()) } returns null
        coEvery { engineering.approvalStatus(any(), any(), any()) } returns null
        coEvery { engineering.prepare(any(), any(), any(), any(), any(), any(), any(), any()) } returns "replacement"
        var retired = false
        coEvery { engineering.replacePendingReview("task", originalRun.patchDigest, "replacement", any()) } coAnswers {
            arg<suspend () -> Unit>(3).invoke()
            assertEquals(GitHubApprovalBinding.Source(31, 42, "saved-model", "saved-contract"), GitHubApprovalBinding.source(approvalStore, "replacement", scope, scope.ref))
            retired = true
            originalTask = originalTask.copy(state = "CANCELLED")
        }
        GitHubApprovalBinding.bind(approvalStore, "task", scope, scope.ref, 31, 42, "saved-model", "saved-contract", scope.commitSha, originalRun.patchDigest)
        if (bindingConflict) GitHubApprovalBinding.bind(approvalStore, "replacement", scope, scope.ref, 51, 62, "other-model", "other-contract", scope.commitSha, replacementRun.patchDigest)
        model.restrictTo(scope, 31)
        model.review.value = original
        model.pendingEngineeringTask.value = "task"

        assertFalse("Initial action must be idle", model.busy.value)
        model.editAndRestage("task", "A.kt", "42", "43")
        withTimeout(5000) { model.busy.first { !it } }

        coVerify(exactly = 1) { engineering.prepare("github", scope.owner, scope.repo, scope.ref, scope.commitSha, listOf(EngineeringFilePatch("A.kt", "41", "43")), "Fix answer", true) }
        coVerify(exactly = 0) { engineering.cancel(any()) }
        if (bindingConflict) {
            assertFalse(retired)
            assertEquals("AWAITING_APPROVAL", originalTask.state)
            assertEquals(original, model.review.value)
            assertEquals("task", model.pendingEngineeringTask.value)
            assertTrue(model.notice.value.contains("another saved request"))
        } else {
            assertTrue(retired)
            assertEquals("replacement", model.pendingEngineeringTask.value)
            assertEquals("43", model.review.value?.files?.single()?.after)
            assertEquals("replacement", model.selectedEngineeringRun.value?.taskId)
            assertTrue(model.engineeringTasks.value.any { it.taskId == "replacement" && it.requiresHardwareVerification })
        }
    }
}
