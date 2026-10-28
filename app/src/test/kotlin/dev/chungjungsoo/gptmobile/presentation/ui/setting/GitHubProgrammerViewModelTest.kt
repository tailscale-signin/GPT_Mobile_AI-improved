package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.viewModelScope
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryContext
import dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceDraftStore
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.permissions.ToolApprovalManager
import dev.chungjungsoo.gptmobile.data.queue.DurablePromptQueue
import dev.chungjungsoo.gptmobile.data.queue.PendingPromptPayload
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GitHubProgrammerViewModelTest {
    private val settings = mockk<SettingRepository>()
    private val chats = mockk<ChatRepository>(relaxed = true)
    private val database = mockk<ChatDatabaseV2>(relaxed = true)
    private val queue = mockk<DurablePromptQueue>(relaxed = true)
    private val approvals = mockk<ToolApprovalManager>()
    private val connections = mockk<ToolConnectionRepository>()
    private val drafts = mockk<GitHubWorkspaceDraftStore>(relaxed = true)
    private val features = MutableStateFlow(AppFeatureSettings(githubMainProfiles = mapOf("github" to "coder")))
    private val profile = PlatformV2(uid = "coder", name = "Local programmer", model = "selected-model")
    private val room = ChatRoomV2(id = 42, title = "owner/repo", enabledPlatform = listOf("coder"))
    private lateinit var model: GitHubProgrammerViewModel

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { settings.observePlatformV2s() } returns flowOf(listOf(profile))
        every { settings.observeFeatureSettings() } returns features
        coEvery { settings.fetchPlatformV2s() } returns listOf(profile)
        coEvery { settings.getFeatureSettings() } answers { features.value }
        coEvery { settings.providerActivationRequired(any()) } returns false
        coEvery { settings.mutateFeatureSettings(any()) } coAnswers {
            val transform = firstArg<(AppFeatureSettings) -> AppFeatureSettings>()
            features.value = transform(features.value)
            features.value
        }
        coEvery { connections.getConnection("github") } returns ToolConnection("github", "GitHub", "github", "GITHUB", null, "OAUTH", "credential-reference", null)
        coEvery { drafts.read(any()) } returns null
        coEvery { chats.createDraftChat(any(), any(), any(), any()) } returns room
        coEvery { database.agentPersistenceDao().getChatRoom(42) } returns room
        every { chats.observeMessageWindow(42, 10) } returns flowOf(emptyList())
        every { chats.observeAgentRuns(42) } returns flowOf(emptyList())
        every { chats.observeToolEvents(42) } returns flowOf(emptyList())
        every { queue.observe(42) } returns flowOf(emptyList())
        every { approvals.pending } returns MutableStateFlow(emptyList())
        model = GitHubProgrammerViewModel(settings, chats, database, queue, mockk(relaxed = true), approvals, connections, drafts)
        model.bind(GitHubWorkspaceState(connection = ToolConnection("github", "GitHub", "github", "GITHUB", null, "OAUTH", "credential-reference", null), selection = GitHubRepositoryContext("owner", "repo", "feature"), headSha = "a".repeat(40)))
    }

    @After fun cleanup() {
        model.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun acceptedCommandUsesDurableQueueWithFrozenProfileAndRepositoryScope() = runBlocking {
        withTimeout(5000) { model.state.first { it.draftLoaded } }
        val accepted = CompletableDeferred<Unit>()
        model.send("Fix the failing checks") { accepted.complete(Unit) }
        withTimeout(5000) { accepted.await() }
        coVerify(exactly = 1) {
            queue.enqueue(
                42,
                match { it.contains("owner/repo") && it.contains("Branch: feature") && it.endsWith("Fix the failing checks") },
                match<PendingPromptPayload> {
                    it.profileUids == listOf("coder") && it.models == mapOf("coder" to "selected-model") && it.tools.github?.commitSha == "a".repeat(40) && !it.tools.allowAllByDefault && it.tools.enabledToolIds == setOf("github")
                }
            )
        }
        assertEquals("repo", features.value.conversationGitHub.getValue(42).repo)
    }

    @Test fun sendingWithGlobalDefaultDoesNotCreateAConnectionOverride() = runBlocking {
        features.value = features.value.copy(githubMainProfiles = emptyMap(), githubDefaultProfileUid = "coder")
        withTimeout(5000) { model.state.first { it.draftLoaded && it.profileOverrideUid == null } }
        val accepted = CompletableDeferred<Unit>()
        model.send("Inspect this branch") { accepted.complete(Unit) }
        withTimeout(5000) { accepted.await() }
        assertTrue(features.value.githubMainProfiles.isEmpty())
        assertEquals("coder", model.state.value.profileUid)
    }

    @Test fun queueFailurePreservesEncryptedDraftAndReportsFailureWithoutAcceptance() = runBlocking {
        withTimeout(5000) { model.state.first { it.draftLoaded } }
        val context = model.state.value.context!!
        assertTrue(model.flushDraft(context, "Keep this command"))
        coEvery { queue.enqueue(any(), any(), any()) } throws IllegalStateException("Queue storage unavailable")
        var accepted = false
        model.send("Keep this command") { accepted = true }
        val failed = withTimeout(5000) { model.state.first { !it.submitting && it.error != null } }
        assertEquals("Queue storage unavailable", failed.error)
        assertFalse(accepted)
        coVerify { drafts.write(match { "owner/repo:feature:coder" in it }, "Keep this command") }
    }
}
