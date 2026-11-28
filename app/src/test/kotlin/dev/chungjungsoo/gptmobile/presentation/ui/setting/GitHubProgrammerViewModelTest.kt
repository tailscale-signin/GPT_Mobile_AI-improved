package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.viewModelScope
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryContext
import dev.chungjungsoo.gptmobile.data.github.GitHubSessionBootstrapper
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
import kotlinx.coroutines.channels.Channel
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
        coEvery { chats.createDraftChat(any(), any(), any(), any(), any(), any()) } answers { room.copy(privacyModeLocked = arg(5)).also { rooms[it.id] = it } }
        rooms[room.id] = room
        every { database.agentPersistenceDao() } returns persistence
        coEvery { persistence.getChatRoom(any()) } answers { rooms[firstArg<Int>()] }
        every { chats.observeMessageWindow(42, 10) } returns flowOf(emptyList())
        every { chats.observeAgentRuns(42) } returns flowOf(emptyList())
        every { chats.observeToolEvents(42) } returns flowOf(emptyList())
        every { queue.observe(42) } returns flowOf(emptyList())
        every { approvals.pending } returns MutableStateFlow(emptyList())
        model = GitHubProgrammerViewModel(settings, chats, database, queue, mockk(relaxed = true), approvals, connections, drafts, mockk(relaxed = true))
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

    @Test fun restoredApprovedFixtureResumesOnlyItsOriginalChatAndFrozenModel() = runBlocking {
        val context = dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext("github", "owner", "repo", "feature", "a".repeat(40), "coder")
        features.value = features.value.copy(conversationGitHub = mapOf(42 to context))
        model.configureSession("coder", 42)
        withTimeout(5000) { model.state.first { it.chatId == 42 && it.draftLoaded } }
        val publication = dev.chungjungsoo.gptmobile.data.github.GitHubApprovalBinding.Continuation(
            "original-task",
            42,
            15,
            "codex-self-test/" + "d".repeat(20),
            "b".repeat(40),
            profile.model,
            dev.chungjungsoo.gptmobile.data.github.GitHubApprovalBinding.profileContract(profile.compatibleType.name, profile.apiUrl, profile.providerConnectionUid, profile.reasoning)
        )
        val accepted = CompletableDeferred<Unit>()
        model.continueAfterCommit(publication.sha, publication.branch, publication) { accepted.complete(Unit) }
        withTimeout(5000) { accepted.await() }
        coVerify(exactly = 1) { queue.enqueue(42, any(), match { it.models == mapOf("coder" to "selected-model") && it.tools.github?.ref == "feature" }, "github-approved-commit:${publication.sha}") }
        coVerify(exactly = 0) { chats.createDraftChat(any(), any(), any(), any(), any(), any()) }
        assertEquals(context.commitSha, model.state.value.context?.commitSha)
    }

    @Test fun changedModelBlocksAutomaticApprovalContinuationWithoutCreatingAnotherRoom() = runBlocking {
        val context = dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext("github", "owner", "repo", "feature", "a".repeat(40), "coder")
        features.value = features.value.copy(conversationGitHub = mapOf(42 to context))
        model.configureSession("coder", 42)
        withTimeout(5000) { model.state.first { it.chatId == 42 && it.draftLoaded } }
        val publication = dev.chungjungsoo.gptmobile.data.github.GitHubApprovalBinding.Continuation(
            "original-task",
            42,
            15,
            "feature",
            "b".repeat(40),
            "previous-model",
            dev.chungjungsoo.gptmobile.data.github.GitHubApprovalBinding.profileContract(profile.compatibleType.name, profile.apiUrl, profile.providerConnectionUid, profile.reasoning)
        )
        model.continueAfterCommit(publication.sha, publication.branch, publication)
        val blocked = withTimeout(5000) { model.state.first { !it.submitting && it.error?.contains("saved AI model") == true } }
        assertEquals(42, blocked.chatId)
        coVerify(exactly = 0) { queue.enqueue(any(), any(), any(), any()) }
        coVerify(exactly = 0) { chats.createDraftChat(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun approvedCommitInAnExplicitGatewayRoomRetainsItsFrozenQueueRoute() = runBlocking {
        val gatewayProfile = profile.copy(compatibleType = dev.chungjungsoo.gptmobile.data.model.ClientType.LLAMA, apiUrl = "http://192.168.1.4:8090")
        val selection = dev.chungjungsoo.gptmobile.data.queue.GatewayQueuedSelection.capture(gatewayProfile, gatewayProfile.model)
        profiles.value = listOf(gatewayProfile)
        rooms[42] = room.copy(conversationMode = dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode.GATEWAY)
        val context = dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext("github", "owner", "repo", "feature", "a".repeat(40), "coder")
        features.value = features.value.copy(conversationGitHub = mapOf(42 to context), conversationGateway = mapOf(42 to selection))
        model.configureSession("coder", 42)
        withTimeout(5000) { model.state.first { it.chatId == 42 && it.draftLoaded } }
        val publication = dev.chungjungsoo.gptmobile.data.github.GitHubApprovalBinding.Continuation(
            "original-task",
            42,
            15,
            "codex-self-test/" + "d".repeat(20),
            "b".repeat(40),
            gatewayProfile.model,
            dev.chungjungsoo.gptmobile.data.github.GitHubApprovalBinding.profileContract(gatewayProfile.compatibleType.name, gatewayProfile.apiUrl, gatewayProfile.providerConnectionUid, gatewayProfile.reasoning)
        )
        val accepted = CompletableDeferred<Unit>()
        model.continueAfterCommit(publication.sha, publication.branch, publication) { accepted.complete(Unit) }
        withTimeout(5000) { accepted.await() }
        coVerify(exactly = 1) { queue.enqueue(42, any(), match { it.gateway == selection && it.models == mapOf("coder" to selection.model) }, "github-approved-commit:${publication.sha}") }
        coVerify(exactly = 0) { chats.createDraftChat(any(), any(), any(), any(), any(), any()) }
        assertEquals(selection, features.value.conversationGateway[42])
    }

    @Test fun savedProfileSelectionWinsOverTheOriginalRouteAfterRecreation() = runBlocking {
        val handle = androidx.lifecycle.SavedStateHandle(
            mapOf("github_session_initialized" to true, "github_session_profile" to "coder", "github_session_chat" to 42)
        )
        val context = dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext("github", "owner", "repo", "feature", "a".repeat(40), "coder")
        features.value = features.value.copy(conversationGitHub = mapOf(42 to context))
        val restored = GitHubProgrammerViewModel(settings, chats, database, queue, mockk(relaxed = true), approvals, connections, drafts, mockk(relaxed = true), handle)
        try {
            restored.configureSession("old-route-profile", newConversation = true)
            restored.bind(GitHubWorkspaceState(connection = connections.getConnection("github"), selection = context.repository, headSha = context.commitSha))
            val value = withTimeout(5000) { restored.state.first { it.chatId == 42 } }
            assertEquals("coder", value.profileUid)
            assertEquals("coder", value.profileOverrideUid)
            coVerify(exactly = 0) { chats.createDraftChat(any(), any(), any(), any(), any(), any()) }
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
        model.configureSession("coder", 42)
        withTimeout(5000) { model.state.first { it.chatId == 42 && it.draftLoaded && it.conversationResolved } }
        model.ensureConversation().join()
        coVerify(exactly = 1) { chats.updateChatPlatforms(match { it.id == 42 && it.enabledPlatform == listOf("coder") }, listOf("coder")) }
        coVerify(exactly = 0) { chats.createDraftChat(any(), any(), any(), any(), any(), any()) }
        assertEquals(42, model.state.value.chatId)
    }

    @Test fun deletedPreparedRoomRequiresExplicitRecoveryInsteadOfCreatingABlankReplacement() = runBlocking {
        val lookupStarted = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Unit>()
        coEvery { persistence.getChatRoom(42) } coAnswers {
            lookupStarted.complete(Unit)
            releaseLookup.await()
            rooms[42]
        }
        model.configureSession("coder", newConversation = true)
        withTimeout(5000) { model.state.first { it.draftLoaded && it.conversationResolved } }
        try {
            model.ensureConversation().join()
            // Creating the room persists a new selected session. Hold its restore
            // until deletion, rather than observing an earlier ready snapshot.
            withTimeout(5000) { lookupStarted.await() }
            assertFalse(model.state.value.conversationResolved)
            rooms.remove(42)
            model.ensureConversation().join()
            coVerify(exactly = 1) { chats.createDraftChat(any(), any(), any(), any(), any(), any()) }

            releaseLookup.complete(Unit)
            withTimeout(5000) { model.state.first { it.chatId == null && it.conversationResolved } }
            // The screen retries when the pending restore becomes resolved.
            model.ensureConversation().join()
            assertEquals(null, model.state.value.chatId)
            assertTrue(model.state.value.error?.contains("saved GitHub conversation is unavailable") == true)
            assertTrue(rooms.isEmpty())
            coVerify(exactly = 1) { chats.createDraftChat(any(), any(), any(), any(), any(), any()) }
        } finally {
            releaseLookup.complete(Unit)
        }
    }

    @Test fun reopeningFinishedConversationWaitsForItsSavedRoomWithoutCreatingABlankCopy() = runBlocking {
        reopenWhileRoomLoads(42)
    }

    @Test fun implicitWorkspaceResumeWaitsForItsSavedRoomWithoutCreatingABlankCopy() = runBlocking {
        reopenWhileRoomLoads(null)
    }

    private suspend fun reopenWhileRoomLoads(requestedChatId: Int?) {
        close(model)
        val context = GitHubConversationContext("github", "owner", "repo", "feature", "a".repeat(40), "coder")
        features.value = features.value.copy(conversationGitHub = mapOf(42 to context))
        val lookupStarted = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Unit>()
        coEvery { persistence.getChatRoom(42) } coAnswers {
            lookupStarted.complete(Unit)
            releaseLookup.await()
            rooms[42]
        }
        coEvery { chats.createDraftChat(any(), any(), any(), any(), any(), any()) } answers { room.copy(id = 99).also { rooms[it.id] = it } }
        every { chats.observeMessageWindow(99, 10) } returns flowOf(emptyList())
        every { chats.observeAgentRuns(99) } returns flowOf(emptyList())
        every { chats.observeToolEvents(99) } returns flowOf(emptyList())
        every { queue.observe(99) } returns flowOf(emptyList())
        val answer = MessageV2(id = 7, chatId = 42, content = "Saved GitHub answer", platformType = "coder")
        every { chats.observeMessageWindow(42, 10) } returns flowOf(listOf(answer))
        every { chats.observeAgentRuns(42) } returns flowOf(listOf(AgentRun("finished", 42, 6, 7, "coder", "CUSTOM", "selected-model", "COMPLETED")))
        model = GitHubProgrammerViewModel(settings, chats, database, queue, mockk(relaxed = true), approvals, connections, drafts, mockk(relaxed = true))
        model.configureSession("coder", requestedChatId)
        model.bind(GitHubWorkspaceState(connection = connections.getConnection("github"), selection = context.repository, headSha = context.commitSha))
        try {
            withTimeout(5000) { lookupStarted.await() }
            assertTrue(model.state.value.draftLoaded)
            assertEquals(null, model.state.value.chatId)
            // This is the screen's early readiness callback while Room is still
            // loading the finished conversation selected from the home bubble.
            model.ensureConversation().join()
            coVerify(exactly = 0) { chats.createDraftChat(any(), any(), any(), any(), any(), any()) }
            releaseLookup.complete(Unit)
            val reopened = withTimeout(5000) { model.state.first { it.chatId == 42 && it.conversationResolved && it.messages == listOf(answer) && it.runs.singleOrNull()?.status == "COMPLETED" } }
            assertTrue(reopened.conversationResolved)
            assertTrue(context.sameWorkspace(requireNotNull(reopened.context)))
            assertEquals(context.commitSha, reopened.context?.commitSha)
            assertEquals(setOf(42), rooms.keys)
            assertEquals(context, features.value.conversationGitHub[42])
            coVerify(exactly = 0) { queue.enqueue(any(), any(), any(), any()) }
        } finally {
            releaseLookup.complete(Unit)
        }
    }

    @Test fun roomAllocationWaitsForTheRouteSessionToBeConfigured() = runBlocking {
        withTimeout(5000) { model.state.first { it.draftLoaded && it.conversationResolved } }
        model.ensureConversation().join()
        coVerify(exactly = 0) { chats.createDraftChat(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun selectingTheSameProfileKeepsTheResolvedNewConversationReady() = runBlocking {
        model.configureSession("coder", newConversation = true)
        withTimeout(5000) { model.state.first { it.draftLoaded && it.conversationResolved } }
        model.selectProfile("coder")
        assertTrue(model.state.value.conversationResolved)
        model.ensureConversation().join()
        withTimeout(5000) { model.state.first { it.chatId == 42 } }
        coVerify(exactly = 1) { chats.createDraftChat(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun missingSavedRoomReportsRecoveryWithoutReplacingItsIdentity() = runBlocking {
        val context = GitHubConversationContext("github", "owner", "repo", "feature", "a".repeat(40), "coder")
        rooms.remove(42)
        features.value = features.value.copy(conversationGitHub = mapOf(42 to context))
        model.configureSession("coder", 42)
        withTimeout(5000) { model.state.first { it.draftLoaded && it.conversationResolved } }
        model.ensureConversation().join()
        assertEquals(null, model.state.value.chatId)
        assertTrue(model.state.value.error?.contains("saved GitHub conversation is unavailable") == true)
        assertEquals(mapOf(42 to context), features.value.conversationGitHub)
        assertTrue(rooms.isEmpty())
        coVerify(exactly = 0) { chats.createDraftChat(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { queue.enqueue(any(), any(), any(), any()) }
    }

    @Test fun savedRoomCannotBeReplacedUnderAnotherWorkspaceOrPrivacyPolicy() = runBlocking {
        val context = GitHubConversationContext("github", "owner", "repo", "feature", "a".repeat(40), "coder")
        val incompatible = listOf(
            Triple(context.copy(connectionUid = "another-account"), room, false),
            Triple(context.copy(owner = "another-owner"), room, false),
            Triple(context.copy(repo = "another-repo"), room, false),
            Triple(context.copy(ref = "another-branch"), room, false),
            Triple(context.copy(mainProfileUid = "another-profile"), room, false),
            Triple(context, room.copy(isTemporary = true), false),
            Triple(context, room, true)
        )
        for ((saved, savedRoom, privateRoute) in incompatible) {
            close(model)
            rooms[42] = savedRoom
            features.value = features.value.copy(conversationGitHub = mapOf(42 to saved))
            model = GitHubProgrammerViewModel(settings, chats, database, queue, mockk(relaxed = true), approvals, connections, drafts, mockk(relaxed = true))
            model.configureSession("coder", 42, privacyModeLocked = privateRoute)
            model.bind(GitHubWorkspaceState(connection = connections.getConnection("github"), selection = context.repository, headSha = context.commitSha))
            withTimeout(5000) { model.state.first { it.draftLoaded && it.conversationResolved } }
            model.ensureConversation().join()
            assertEquals(null, model.state.value.chatId)
            assertTrue(model.state.value.error?.contains("saved GitHub conversation is unavailable") == true)
            assertEquals(saved, features.value.conversationGitHub[42])
            assertEquals(savedRoom, rooms[42])
        }
        coVerify(exactly = 0) { chats.createDraftChat(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun delayedBranchBindingReattachesTheSavedRoomWithoutCreatingAnotherChat() = runBlocking {
        val context = GitHubConversationContext("github", "owner", "repo", "feature", "a".repeat(40), "coder")
        features.value = features.value.copy(conversationGitHub = mapOf(42 to context))
        model.configureSession("coder", 42)
        withTimeout(5000) { model.state.first { it.chatId == 42 && it.conversationResolved } }
        val changed = context.copy(ref = "selected-branch", commitSha = "b".repeat(40))
        model.bind(GitHubWorkspaceState(connection = connections.getConnection("github"), selection = changed.repository, headSha = changed.commitSha))
        withTimeout(5000) { model.state.first { it.context?.ref == changed.ref && it.draftLoaded && it.conversationResolved } }
        model.ensureConversation().join()
        assertEquals(null, model.state.value.chatId)
        assertTrue(model.state.value.error?.contains("saved GitHub conversation is unavailable") == true)
        features.value = features.value.copy(conversationGitHub = mapOf(42 to changed))
        val reopened = withTimeout(5000) { model.state.first { it.chatId == 42 && it.conversationResolved && it.error == null } }
        assertTrue(changed.sameWorkspace(requireNotNull(reopened.context)))
        assertEquals(changed.commitSha, reopened.context?.commitSha)
        assertEquals(setOf(42), rooms.keys)
        coVerify(exactly = 0) { chats.createDraftChat(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun legacyCommandDraftMovesToTheStandardComposerOnce() = runBlocking {
        model.configureSession("coder", newConversation = true)
        withTimeout(5000) { model.state.first { it.draftLoaded && it.conversationResolved } }
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
        withTimeout(5000) { model.state.first { it.draftLoaded && it.conversationResolved && it.profileUid == "coder" } }
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
        coVerify(exactly = 1) { chats.createDraftChat(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun privateGitHubChoiceCreatesAPermanentlyLockedRoomWithoutSubmittingWork() = runBlocking {
        model.configureSession("coder", newConversation = true, privacyModeLocked = true)
        assertEquals(true, model.state.value.privacyModeLocked)
        withTimeout(5000) { model.state.first { it.draftLoaded && it.conversationResolved && it.profileUid == "coder" } }

        model.ensureConversation().join()
        withTimeout(5000) { model.state.first { it.chatId == 42 } }

        coVerify(exactly = 1) { chats.createDraftChat(listOf("coder"), "STANDARD", "repo", "coder", null, true) }
        assertTrue(requireNotNull(rooms[42]).privacyModeLocked)
        assertEquals(true, model.state.value.privacyModeLocked)
        assertFalse(requireNotNull(rooms[42]).privacyModeEnabled)
        coVerify(exactly = 0) { queue.enqueue(any(), any(), any(), any()) }
    }

    @Test fun delayedWorkspaceRestoreCannotClearThePrivateDraftLockOrReopenThePreviousRoom() = runBlocking {
        val restoring = Channel<Unit>()
        val resumeRestore = Channel<Unit>()
        val bootstrapper = mockk<GitHubSessionBootstrapper>()
        coEvery { bootstrapper.rememberConversation(any(), any(), any()) } coAnswers {
            restoring.send(Unit)
            resumeRestore.receive()
        }
        val context = dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext("github", "owner", "repo", "feature", "a".repeat(40), "coder")
        features.value = features.value.copy(conversationGitHub = mapOf(42 to context))
        val restored = GitHubProgrammerViewModel(settings, chats, database, queue, mockk(relaxed = true), approvals, connections, drafts, mockk(relaxed = true), bootstrapper = bootstrapper)
        try {
            restored.bind(GitHubWorkspaceState(connection = connections.getConnection("github"), selection = context.repository, headSha = context.commitSha))
            withTimeout(5000) {
                restoring.receive()
                restored.configureSession("coder", newConversation = true, privacyModeLocked = true)
                assertEquals(true, restored.state.value.privacyModeLocked)
                resumeRestore.send(Unit)

                // The old restore has returned and the new route is still loading.
                restoring.receive()
                assertEquals(true, restored.state.value.privacyModeLocked)
                assertEquals(null, restored.state.value.chatId)
                assertFalse(restored.state.value.conversationResolved)
                resumeRestore.send(Unit)
            }
        } finally {
            close(restored)
        }
    }

    @Test fun reopeningAPrivateRoomResolvesTheSavedPolicyWithoutUsingRouteDefaults() = runBlocking {
        rooms[42] = room.copy(privacyModeLocked = true)
        val context = dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext("github", "owner", "repo", "feature", "a".repeat(40), "coder")
        features.value = features.value.copy(conversationGitHub = mapOf(42 to context))
        model.configureSession("coder", 42, privacyModeLocked = false)

        val reopened = withTimeout(5000) { model.state.first { it.chatId == 42 && it.privacyModeLocked == true } }

        assertEquals(true, reopened.privacyModeLocked)
        coVerify(exactly = 0) { chats.createDraftChat(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun previousPrivateWorkspaceCannotOverrideANewOrdinaryRouteBeforeSessionConfiguration() = runBlocking {
        rooms[42] = room.copy(privacyModeLocked = true)
        val context = dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext("github", "owner", "repo", "feature", "a".repeat(40), "coder")
        features.value = features.value.copy(conversationGitHub = mapOf(42 to context))
        withTimeout(5000) { model.state.first { it.chatId == 42 } }

        model.configureSession("coder", newConversation = true, privacyModeLocked = false)
        withTimeout(5000) { model.state.first { it.chatId == null && it.conversationResolved && it.privacyModeLocked == false } }
        val ordinary = room.copy(id = 43, privacyModeLocked = false)
        coEvery { chats.createDraftChat(listOf("coder"), "STANDARD", "repo", "coder", null, false) } answers { ordinary.also { rooms[it.id] = it } }
        every { chats.observeMessageWindow(43, 10) } returns flowOf(emptyList())
        every { chats.observeAgentRuns(43) } returns flowOf(emptyList())
        every { chats.observeToolEvents(43) } returns flowOf(emptyList())
        every { queue.observe(43) } returns flowOf(emptyList())
        model.ensureConversation().join()

        coVerify(exactly = 1) { chats.createDraftChat(listOf("coder"), "STANDARD", "repo", "coder", null, false) }
        assertTrue(requireNotNull(rooms[42]).privacyModeLocked)
        assertFalse(requireNotNull(rooms[43]).privacyModeLocked)
    }

    @Test fun privateGitHubDraftChoiceSurvivesRecreationAndOverridesRouteDefaults() = runBlocking {
        val handle = androidx.lifecycle.SavedStateHandle(
            mapOf("github_session_initialized" to true, "github_session_profile" to "coder", "github_session_chat" to 0, "github_session_privacy_locked" to true)
        )
        val restored = GitHubProgrammerViewModel(settings, chats, database, queue, mockk(relaxed = true), approvals, connections, drafts, mockk(relaxed = true), handle)
        try {
            restored.configureSession("old-route-profile", newConversation = true, privacyModeLocked = false)
            restored.bind(GitHubWorkspaceState(connection = connections.getConnection("github"), selection = GitHubRepositoryContext("owner", "repo", "feature"), headSha = "a".repeat(40)))
            withTimeout(5000) { restored.state.first { it.draftLoaded && it.conversationResolved && it.profileUid == "coder" } }
            restored.ensureConversation().join()
            withTimeout(5000) { restored.state.first { it.chatId == 42 } }

            coVerify(exactly = 1) { chats.createDraftChat(listOf("coder"), "STANDARD", "repo", "coder", null, true) }
            assertTrue(requireNotNull(rooms[42]).privacyModeLocked)
            assertEquals(true, handle.get<Boolean>("github_session_privacy_locked"))
        } finally {
            close(restored)
        }
    }

    @Test fun privateGitHubCommandFallbackPersistsTheLockBeforeQueueing() = runBlocking {
        model.configureSession("coder", newConversation = true, privacyModeLocked = true)
        withTimeout(5000) { model.state.first { it.draftLoaded && it.profileUid == "coder" } }
        val accepted = CompletableDeferred<Unit>()

        model.send("Review the selected repository") { accepted.complete(Unit) }
        withTimeout(5000) { accepted.await() }

        coVerify(exactly = 1) { chats.createDraftChat(listOf("coder"), "STANDARD", "repo", "coder", null, true) }
        assertTrue(requireNotNull(rooms[42]).privacyModeLocked)
        coVerify(exactly = 1) { queue.enqueue(42, any(), any(), any()) }
    }

    @Test fun unavailableExplicitProfileDoesNotSilentlyRunAnotherProfile() = runBlocking {
        model.configureSession("deleted-profile", newConversation = true)
        withTimeout(5000) { model.state.first { it.profileUid == null && it.context == null } }
        model.ensureConversation().join()
        coVerify(exactly = 0) { chats.createDraftChat(any(), any(), any(), any(), any(), any()) }
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
        coVerify(exactly = 0) { chats.createDraftChat(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { queue.enqueue(any(), any(), any()) }
        profiles.value = listOf(profile)
        val resumed = withTimeout(5000) { model.state.first { it.profileUid == "coder" && it.draftLoaded } }
        assertEquals("coder", resumed.context?.mainProfileUid)
    }

    @Test fun anUnconfiguredSessionNeverChoosesTheFirstEnabledProfile() = runBlocking {
        close(model)
        features.value = features.value.copy(githubMainProfiles = emptyMap(), githubDefaultProfileUid = null)
        model = GitHubProgrammerViewModel(settings, chats, database, queue, mockk(relaxed = true), approvals, connections, drafts, mockk(relaxed = true))
        model.configureSession(null, newConversation = true)
        model.bind(GitHubWorkspaceState(connection = connections.getConnection("github"), selection = GitHubRepositoryContext("owner", "repo", "feature"), headSha = "a".repeat(40)))
        val unconfigured = withTimeout(5000) { model.state.first { it.profiles.isNotEmpty() } }
        assertEquals(null, unconfigured.profileUid)
        assertEquals(null, unconfigured.context)
        model.ensureConversation().join()
        coVerify(exactly = 0) { chats.createDraftChat(any(), any(), any(), any(), any(), any()) }
    }
}
