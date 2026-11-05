package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.viewModelScope
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao
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
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
    private val persistence = mockk<AgentPersistenceDao>()
    private val queue = mockk<DurablePromptQueue>(relaxed = true)
    private val approvals = mockk<ToolApprovalManager>()
    private val connections = mockk<ToolConnectionRepository>()
    private val drafts = mockk<GitHubWorkspaceDraftStore>(relaxed = true)
    private val features = MutableStateFlow(AppFeatureSettings(githubMainProfiles = mapOf("github" to "coder")))
    private val profile = PlatformV2(uid = "coder", name = "Local programmer", model = "selected-model")
    private val profiles = MutableStateFlow(listOf(profile))
    private val room = ChatRoomV2(id = 42, title = "owner/repo", enabledPlatform = listOf("coder"))
    private val rooms = ConcurrentHashMap<Int, ChatRoomV2>()
    private lateinit var model: GitHubProgrammerViewModel

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { settings.observePlatformV2s() } returns profiles
        every { settings.observeFeatureSettings() } returns features
        coEvery { settings.fetchPlatformV2s() } answers { profiles.value }
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
        rooms[room.id] = room
        every { database.agentPersistenceDao() } returns persistence
        coEvery { persistence.getChatRoom(any()) } answers { rooms[firstArg<Int>()] }
        every { chats.observeMessageWindow(42, 10) } returns flowOf(emptyList())
        every { chats.observeAgentRuns(42) } returns flowOf(emptyList())
        every { chats.observeToolEvents(42) } returns flowOf(emptyList())
        every { queue.observe(42) } returns flowOf(emptyList())
        every { approvals.pending } returns MutableStateFlow(emptyList())
        model = GitHubProgrammerViewModel(settings, chats, database, queue, mockk(relaxed = true), approvals, connections, drafts)
        model.bind(GitHubWorkspaceState(connection = ToolConnection("github", "GitHub", "github", "GITHUB", null, "OAUTH", "credential-reference", null), selection = GitHubRepositoryContext("owner", "repo", "feature"), headSha = "a".repeat(40)))
    }

    @After fun cleanup() = runBlocking {
        try {
            close(model)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private suspend fun close(viewModel: GitHubProgrammerViewModel) {
        withTimeout(5000) {
            // The collector runs on Dispatchers.IO; cancellation must finish before
            // resetting Main or starting the next test.
            viewModel.viewModelScope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test fun savedProfileSelectionWinsOverTheOriginalRouteAfterRecreation() = runBlocking {
        val handle = androidx.lifecycle.SavedStateHandle(
            mapOf("github_session_initialized" to true, "github_session_profile" to "coder", "github_session_chat" to 42)
        )
        val context = dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext("github", "owner", "repo", "feature", "a".repeat(40), "coder")
        features.value = features.value.copy(conversationGitHub = mapOf(42 to context))
        val restored = GitHubProgrammerViewModel(settings, chats, database, queue, mockk(relaxed = true), approvals, connections, drafts, handle)
        try {
            restored.configureSession("old-route-profile", newConversation = true)
            restored.bind(GitHubWorkspaceState(connection = connections.getConnection("github"), selection = context.repository, headSha = context.commitSha))
            val value = withTimeout(5000) { restored.state.first { it.chatId == 42 } }
            assertEquals("coder", value.profileUid)
            assertEquals("coder", value.profileOverrideUid)
            coVerify(exactly = 0) { chats.createDraftChat(any(), any(), any(), any()) }
        } finally {
            close(restored)
        }
    }

    @Test fun reopeningALegacyMergedGitHubRoomRepairsItWithoutCreatingANewConversation() = runBlocking {
        val context = dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext("github", "owner", "repo", "feature", "a".repeat(40), "coder")
        val merged = room.copy(enabledPlatform = listOf("coder", "remote"), activePlatform = listOf("coder", "remote"))
        rooms[42] = merged
        coEvery { chats.updateChatPlatforms(any(), listOf("coder")) } answers { firstArg() }
        features.value = features.value.copy(conversationGitHub = mapOf(42 to context))
        withTimeout(5000) { model.state.first { it.chatId == 42 && it.draftLoaded } }
        model.ensureConversation().join()
        coVerify(exactly = 1) { chats.updateChatPlatforms(match { it.id == 42 && it.enabledPlatform == listOf("coder") }, listOf("coder")) }
        coVerify(exactly = 0) { chats.createDraftChat(any(), any(), any(), any()) }
        assertEquals(42, model.state.value.chatId)
    }

    @Test fun deletedPreparedRoomIsReplacedInsteadOfReopened() = runBlocking {
        withTimeout(5000) { model.state.first { it.draftLoaded } }
        model.ensureConversation().join()
        withTimeout(5000) { model.state.first { it.chatId == 42 } }
        rooms.remove(42)
        val replacement = room.copy(id = 43)
        coEvery { chats.createDraftChat(any(), any(), any(), any()) } returns replacement
        rooms[43] = replacement
        every { chats.observeMessageWindow(43, 10) } returns flowOf(emptyList())
        every { chats.observeAgentRuns(43) } returns flowOf(emptyList())
        every { chats.observeToolEvents(43) } returns flowOf(emptyList())
        every { queue.observe(43) } returns flowOf(emptyList())
        model.ensureConversation().join()
        withTimeout(5000) { model.state.first { it.chatId == 43 } }
        coVerify(exactly = 2) { chats.createDraftChat(any(), any(), any(), any()) }
    }

    @Test fun legacyCommandDraftMovesToTheStandardComposerOnce() = runBlocking {
        withTimeout(5000) { model.state.first { it.draftLoaded } }
        val context = model.state.value.context!!
        model.updateDraft(context, "Continue the code review")
        model.ensureConversation().join()
        withTimeout(5000) { model.state.first { it.chatId == 42 } }
        coVerify { chats.saveComposerDraft(42, "Continue the code review", "[]", any()) }
        coVerify { drafts.write(match { "owner/repo:feature:coder" in it }, "") }
        assertEquals("", model.state.value.draft)
    }

    @Test fun openingChatCreatesOneScopedRoomWithoutSubmittingWork() = runBlocking {
        model.configureSession("coder", newConversation = true)
        withTimeout(5000) { model.state.first { it.draftLoaded && it.profileUid == "coder" } }
        kotlinx.coroutines.joinAll(model.ensureConversation(), model.ensureConversation())
        withTimeout(5000) { model.state.first { it.chatId == 42 } }
        coVerify(exactly = 1) { chats.createDraftChat(listOf("coder"), "STANDARD", "repo", "coder") }
        coVerify(exactly = 0) { queue.enqueue(any(), any(), any()) }
        assertEquals("feature", features.value.conversationGitHub.getValue(42).ref)
        assertEquals("coder", model.state.value.profileOverrideUid)
        assertTrue(features.value.githubMainProfiles == mapOf("github" to "coder"))
        // Re-entering the same destination after recreation must not allocate another room.
        model.configureSession("coder", newConversation = true)
        model.ensureConversation().join()
        coVerify(exactly = 1) { chats.createDraftChat(any(), any(), any(), any()) }
    }

    @Test fun unavailableExplicitProfileDoesNotSilentlyRunAnotherProfile() = runBlocking {
        model.configureSession("deleted-profile", newConversation = true)
        withTimeout(5000) { model.state.first { it.profileUid == null && it.context == null } }
        model.ensureConversation().join()
        coVerify(exactly = 0) { chats.createDraftChat(any(), any(), any(), any()) }
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
                    it.profileUids == listOf("coder") && it.models == mapOf("coder" to "selected-model") && it.tools.github?.commitSha == "a".repeat(40) && it.tools.github?.autoApproveTools == true && !it.tools.allowAllByDefault && it.tools.enabledToolIds == setOf("github")
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

    @Test fun changingSavedDefaultsNeverSwitchesTheOpenSessionsSelectedProfile() = runBlocking {
        withTimeout(5000) { model.state.first { it.draftLoaded && it.profileUid == "coder" } }
        val alternate = profile.copy(uid = "alternate", name = "Another provider", model = "other-model")
        profiles.value = listOf(profile, alternate)
        features.value = features.value.copy(githubMainProfiles = mapOf("github" to "alternate"), githubDefaultProfileUid = "alternate")
        val updated = withTimeout(5000) { model.state.first { it.profiles.size == 2 && it.profileOverrideUid == "alternate" } }
        assertEquals("coder", updated.profileUid)
        assertEquals("coder", updated.selectedProfileUid)
        assertEquals("coder", updated.context?.mainProfileUid)
        model.selectProfile("alternate")
        val explicitlySelected = withTimeout(5000) { model.state.first { it.profileUid == "alternate" && it.draftLoaded } }
        assertEquals("alternate", explicitlySelected.context?.mainProfileUid)
    }

    @Test fun disabledSelectedProfilePausesInsteadOfUsingAnEnabledAlternative() = runBlocking {
        withTimeout(5000) { model.state.first { it.draftLoaded && it.profileUid == "coder" } }
        features.value = features.value.copy(githubMainProfiles = mapOf("github" to "alternate"), githubDefaultProfileUid = "alternate")
        withTimeout(5000) { model.state.first { it.profileOverrideUid == "alternate" } }
        profiles.value = listOf(profile.copy(enabled = false), profile.copy(uid = "alternate", model = "other-model"))
        val paused = withTimeout(5000) { model.state.first { it.profiles.map { p -> p.uid } == listOf("alternate") && it.profileUid == null } }
        assertEquals("coder", paused.selectedProfileUid)
        assertEquals(null, paused.context)
        model.ensureConversation().join()
        coVerify(exactly = 0) { chats.createDraftChat(any(), any(), any(), any()) }
        coVerify(exactly = 0) { queue.enqueue(any(), any(), any()) }
        profiles.value = listOf(profile)
        val resumed = withTimeout(5000) { model.state.first { it.profileUid == "coder" && it.draftLoaded } }
        assertEquals("coder", resumed.context?.mainProfileUid)
    }

    @Test fun anUnconfiguredSessionNeverChoosesTheFirstEnabledProfile() = runBlocking {
        close(model)
        features.value = features.value.copy(githubMainProfiles = emptyMap(), githubDefaultProfileUid = null)
        model = GitHubProgrammerViewModel(settings, chats, database, queue, mockk(relaxed = true), approvals, connections, drafts)
        model.configureSession(null, newConversation = true)
        model.bind(GitHubWorkspaceState(connection = connections.getConnection("github"), selection = GitHubRepositoryContext("owner", "repo", "feature"), headSha = "a".repeat(40)))
        val unconfigured = withTimeout(5000) { model.state.first { it.profiles.isNotEmpty() } }
        assertEquals(null, unconfigured.profileUid)
        assertEquals(null, unconfigured.context)
        model.ensureConversation().join()
        coVerify(exactly = 0) { chats.createDraftChat(any(), any(), any(), any()) }
    }
}
