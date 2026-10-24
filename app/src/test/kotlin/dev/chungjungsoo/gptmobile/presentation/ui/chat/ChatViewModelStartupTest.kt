package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.agent.ActiveAgentRun
import dev.chungjungsoo.gptmobile.data.agent.AgentRunCoordinator
import dev.chungjungsoo.gptmobile.data.agent.AgentRunNotice
import dev.chungjungsoo.gptmobile.data.agent.CombinedAcceptedTurn
import dev.chungjungsoo.gptmobile.data.agent.CombinedExecutionContext
import dev.chungjungsoo.gptmobile.data.agent.CombinedPolicyResolver
import dev.chungjungsoo.gptmobile.data.chat.ConversationReadStateStore
import dev.chungjungsoo.gptmobile.data.chat.GenerationCompletionStore
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.LocalModelRepository
import dev.chungjungsoo.gptmobile.data.repository.ModelCatalogRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.research.AcceptedResearchTask
import dev.chungjungsoo.gptmobile.data.research.ResearchAttemptState
import dev.chungjungsoo.gptmobile.data.research.ResearchExecutionRecord
import dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore
import dev.chungjungsoo.gptmobile.data.research.researchHash
import dev.chungjungsoo.gptmobile.presentation.StartupRecoveryGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelStartupTest {
    // Main.immediate can collect cached repository flows before the constructor returns.
    // A StandardTestDispatcher would postpone that work and conceal the startup crash.
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = ViewModelStore()
    private val repository = mockk<ChatRepository>(relaxed = true)
    private val coordinator = mockk<AgentRunCoordinator>(relaxed = true)
    private val messages = MutableStateFlow<List<MessageV2>>(emptyList())
    private val runs = MutableStateFlow<List<AgentRun>>(emptyList())
    private val activeRuns = MutableStateFlow<Map<String, ActiveAgentRun>>(emptyMap())
    private val profiles = (1..3).map { PlatformV2(uid = "profile-$it", name = "Profile $it", enabled = true, model = "model-$it") }
    private val features = AppFeatureSettings(
        automaticConversationTitles = false,
        profileAttributionColors = dev.chungjungsoo.gptmobile.data.model.ProfileColorRegistry.ensure(emptyMap(), profiles.map { it.uid })
    )

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        every { coordinator.activeRuns } returns activeRuns
        every { coordinator.streamMessages } returns MutableStateFlow(emptyMap())
        every { coordinator.notices } returns MutableSharedFlow<AgentRunNotice>()
    }

    @After fun cleanup() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test fun freshStandardChatHandlesImmediateEmptyRunEmission() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        val model = createViewModel(chatId = 0, combined = false)
        assertEquals(0, model.chatRoom.value.id)
        assertTrue(model.combinedFailures.value.isEmpty())
        assertTrue(model.loadingStates.value.all { it == ChatViewModel.LoadingState.Idle })
    }

    @Test fun freshCombinedChatHandlesImmediateEmptyRunEmission() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        val model = createViewModel(chatId = 0)
        assertEquals(ConversationMode.COMBINED, model.chatRoom.value.conversationMode)
        assertTrue(model.combinedFailures.value.isEmpty())
        verify(exactly = 0) { coordinator.start(any()) }
    }

    @Test fun restoredCombinedChatPublishesEveryFailureWithoutReplacingSuccessfulReply() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        seedTurn(listOf(AgentRunStatus.COMPLETED, AgentRunStatus.FAILED, AgentRunStatus.FAILED))
        val model = createViewModel()
        assertEquals(setOf("profile-2", "profile-3"), model.combinedFailures.value.map { it.profileUid }.toSet())
        assertTrue(model.combinedFailures.value.all { it.details == "HTTP 502" && it.retryAllowed })
        assertEquals("A complete useful response.", model.groupedMessages.value.assistantMessages.single().first().content)
        verify(exactly = 0) { coordinator.start(any()) }
        coVerify(exactly = 0) { repository.persistAgentTurn(any()) }
    }

    @Test fun pendingRecoveryPublishesFailuresAndKeepsUnaffectedProfileRunning() = runTest(dispatcher) {
        val recovery = CompletableDeferred<Unit>()
        StartupRecoveryGate.start(backgroundScope) { recovery.await() }
        seedTurn(listOf(AgentRunStatus.RUNNING, AgentRunStatus.FAILED, AgentRunStatus.FAILED))
        val model = createViewModel()
        assertTrue(model.combinedFailures.value.isEmpty())
        recovery.complete(Unit)
        runCurrent()
        assertEquals(setOf("profile-2", "profile-3"), model.combinedFailures.value.map { it.profileUid }.toSet())
        assertEquals(ChatViewModel.LoadingState.Loading, model.loadingStates.value.first())
        assertEquals(setOf("run-1"), activeRuns.value.keys)
        verify(exactly = 0) { coordinator.cancelRuns(any()) }
        verify(exactly = 0) { coordinator.cancelChat(any()) }
    }

    @Test fun simultaneousProfileFailuresDoNotCancelOtherProfiles() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        seedTurn(List(3) { AgentRunStatus.RUNNING })
        val model = createViewModel()
        runs.value = runs.value.mapIndexed { index, run ->
            if (index == 0) run else run.copy(status = AgentRunStatus.FAILED, terminalError = "HTTP 502")
        }
        activeRuns.value = activeRuns.value.filterKeys { it == "run-1" }
        assertEquals(setOf("profile-2", "profile-3"), model.combinedFailures.value.map { it.profileUid }.toSet())
        assertEquals(listOf(ChatViewModel.LoadingState.Loading, ChatViewModel.LoadingState.Idle, ChatViewModel.LoadingState.Idle), model.loadingStates.value)
        verify(exactly = 0) { coordinator.cancelRuns(any()) }
        verify(exactly = 0) { coordinator.cancelChat(any()) }
        verify(exactly = 0) { coordinator.start(any()) }
    }

    @Test fun reopeningAfterProcessRecoveryRestoresSameFailureAttempts() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        seedTurn(listOf(AgentRunStatus.COMPLETED, AgentRunStatus.FAILED, AgentRunStatus.FAILED))
        val first = createViewModel()
        val expected = first.combinedFailures.value
        assertEquals(2, expected.size)
        store.clear()
        StartupRecoveryGate.start(backgroundScope) {}
        val reopened = createViewModel()
        assertEquals(expected, reopened.combinedFailures.value)
        assertEquals("Saved draft", reopened.question.text.toString())
        verify(exactly = 0) { coordinator.start(any()) }
    }

    @Test fun restoredPrimaryKeepsOwnershipWithoutReopeningHistoricalContributorFailureModal() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        seedTurn(listOf(AgentRunStatus.COMPLETED, AgentRunStatus.FAILED, AgentRunStatus.COMPLETED))
        val primary = runs.value.first().copy(runId = "combined-synthesis:primary", status = AgentRunStatus.RUNNING, synthesisKey = "root")
        runs.value = runs.value + primary
        messages.value = messages.value.map { if (it.platformType == "profile-1") it.copy(currentRunId = primary.runId, content = "Primary is writing") else it }
        activeRuns.value = mapOf(primary.runId to ActiveAgentRun(primary.runId, 7, primary.profileUid, primary.assistantMessageId))
        val model = createViewModel()
        assertTrue(model.combinedFailures.value.isEmpty())
        assertEquals(AgentRunStatus.FAILED, model.agentRunsById.value.getValue("run-2").status)
        assertEquals("HTTP 502", model.agentRunsById.value.getValue("run-2").terminalError)
        assertEquals(primary, model.agentRunsById.value.getValue(primary.runId))
        assertEquals(setOf(primary.runId), activeRuns.value.keys)
        assertEquals(ChatViewModel.LoadingState.Loading, model.loadingStates.value.first())
        assertEquals("Primary is writing", model.groupedMessages.value.assistantMessages.single().first().content)
        verify(exactly = 0) { coordinator.cancelRuns(any()) }
        verify(exactly = 0) { coordinator.cancelChat(any()) }
        verify(exactly = 0) { coordinator.start(any()) }
    }

    @Test fun immediateDraftRestorationIsNotResetAfterConstructorReturns() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        val model = createViewModel(combined = false)
        assertEquals("Saved draft", model.question.text.toString())
        model.leaveConversation {}
        verify(exactly = 1) { exitWork.enqueue(7, any()) }
        exitAction!!.invoke()
        coVerify { repository.saveComposerDraft(7, "Saved draft", "[]", any()) }
    }

    @Test fun interruptedResearchCannotRetryUntilReviewedAuthorizationAndOnlyResumesOnce() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        seedTurn(listOf(AgentRunStatus.INTERRUPTED, AgentRunStatus.COMPLETED, AgentRunStatus.COMPLETED))
        val original = interruptedResearch()
        val execution = MutableStateFlow(mapOf("root" to original))
        val research = researchStore(execution)
        coEvery { research.resumeExecution(any(), any(), any(), any(), any()) } answers {
            assertEquals(original.attempt, arg(4))
            assertEquals(true, arg<Boolean>(3))
            original.resume(arg(1), 40, reconciled = true).also { execution.value = mapOf("root" to it) }
        }
        val model = createViewModel(research = research)
        model.retryResearch("root")
        model.reviewInterruptedResearch("root")
        val review = requireNotNull(model.interruptedResearchReview.value)
        assertEquals(listOf("run-1"), review.requests.map { it.runId })
        coVerify(exactly = 0) { research.resumeExecution(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { coordinator.start(any()) }
        model.authorizeReviewedResearchAttempt("unreviewed-action")
        coVerify(exactly = 0) { research.resumeExecution(any(), any(), any(), any(), any()) }
        model.authorizeReviewedResearchAttempt(review.actionId)
        model.authorizeReviewedResearchAttempt(review.actionId)
        model.retryResearch("root")
        coVerify(exactly = 1) { research.resumeExecution("root", review.actionId, true, true, original.attempt) }
        assertEquals(1L, execution.value.getValue("root").attempt.generation)
        assertEquals(ResearchAttemptState.INTERRUPTED, execution.value.getValue("root").previousAttempts.single().state)
        assertEquals(null, model.interruptedResearchReview.value)
    }

    @Test fun staleOrDismissedInterruptedReviewCannotAuthorizeANewGeneration() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        seedTurn(listOf(AgentRunStatus.INTERRUPTED, AgentRunStatus.COMPLETED, AgentRunStatus.COMPLETED))
        val original = interruptedResearch()
        val execution = MutableStateFlow(mapOf("root" to original))
        val research = researchStore(execution)
        val model = createViewModel(research = research)
        model.reviewInterruptedResearch("root")
        val dismissed = requireNotNull(model.interruptedResearchReview.value)
        model.dismissInterruptedResearchReview()
        model.authorizeReviewedResearchAttempt(dismissed.actionId)
        model.reviewInterruptedResearch("root")
        val stale = requireNotNull(model.interruptedResearchReview.value)
        execution.value = mapOf("root" to original.resume("another-authorized-action", 40, reconciled = true))
        model.authorizeReviewedResearchAttempt(stale.actionId)
        coVerify(exactly = 0) { research.resumeExecution(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { coordinator.start(any()) }
        assertTrue(model.attachmentNotice.value.orEmpty().contains("attempt changed"))
    }

    private fun researchStore(execution: MutableStateFlow<Map<String, ResearchExecutionRecord>>): ResearchSessionStore = mockk<ResearchSessionStore>(relaxed = true).also { research ->
        every { research.executionState } returns execution
        every { research.sessions } returns MutableStateFlow(emptyMap())
    }

    private fun interruptedResearch(): ResearchExecutionRecord {
        val accepted = ResearchExecutionRecord.accept(
            AcceptedResearchTask(
                taskId = researchHash("task:root"), rootWorkId = "root", chatId = 7, originatingUserMessageId = 1,
                taskText = "Explain the findings", intent = "REQUIRED", participantUids = profiles.map { it.uid }, primaryUid = "profile-1",
                settingsRevision = "settings", sourceAuthorityRevision = "scope", acceptedAt = 10, targetSources = 5
            )
        )
        return accepted.transition(accepted.attempt.attemptId, 0, ResearchAttemptState.ANALYZING, 20, runId = "run-1", profileUid = "profile-1", dispatched = true)
            .transition(accepted.attempt.attemptId, 0, ResearchAttemptState.INTERRUPTED, 30)
    }

    private val exitWork = mockk<dev.chungjungsoo.gptmobile.data.chat.ConversationExitWork>(relaxed = true)
    private var exitAction: (suspend () -> Unit)? = null

    private fun seedTurn(statuses: List<String>) {
        val requested = CombinedPolicyResolver.accept(
            ConversationCombinedSettings(),
            profiles.map { it.uid },
            profiles.associate { it.uid to it.model },
            "Explain the findings",
            singlePass = true
        )
        val snapshot = CombinedAcceptedTurn(context = CombinedExecutionContext(requested, rootBudgetId = "root"), tools = ChatMcpToolConfig())
        messages.value = listOf(MessageV2(id = 1, chatId = 7, content = requested.userPrompt, platformType = null)) + profiles.mapIndexed { index, profile ->
            MessageV2(id = index + 2, chatId = 7, content = if (statuses[index] == AgentRunStatus.COMPLETED) "A complete useful response." else "", platformType = profile.uid, currentRunId = "run-${index + 1}")
        }
        runs.value = profiles.mapIndexed { index, profile ->
            AgentRun("run-${index + 1}", 7, 1, index + 2, profile.uid, profile.compatibleType.name, profile.model, statuses[index], terminalError = "HTTP 502".takeIf { statuses[index] == AgentRunStatus.FAILED }, synthesisSnapshot = snapshot.encode())
        }
        activeRuns.value = runs.value.filter { it.status == AgentRunStatus.RUNNING }.associate { it.runId to ActiveAgentRun(it.runId, 7, it.profileUid, it.assistantMessageId) }
    }

    private fun createViewModel(chatId: Int = 7, combined: Boolean = true, research: ResearchSessionStore? = null): ChatViewModel {
        val room = ChatRoomV2(id = chatId, title = "Saved chat", enabledPlatform = profiles.map { it.uid }, activePlatform = profiles.map { it.uid }, conversationMode = if (combined) ConversationMode.COMBINED else ConversationMode.STANDARD, draftText = "Saved draft")
        every { repository.observeMessageWindow(chatId, any()) } returns messages
        every { repository.observeTurnCount(chatId) } returns flowOf(messages.value.count { it.platformType == null })
        every { repository.observeAgentRuns(chatId) } returns runs
        every { repository.observeToolEvents(chatId) } returns flowOf(emptyList())
        coEvery { repository.fetchChatListV2() } returns listOf(room)
        coEvery { repository.fetchArchivedChatListV2() } returns emptyList()
        coEvery { repository.fetchMessagesV2(chatId) } answers { messages.value }
        coEvery { repository.fetchChatPlatformModels(chatId) } returns profiles.associate { it.uid to it.model }
        every { exitWork.enqueue(any(), any()) } answers {
            exitAction = secondArg()
            kotlinx.coroutines.Job()
        }
        val settings = mockk<SettingRepository>(relaxed = true)
        coEvery { settings.fetchPlatformV2s() } returns profiles
        coEvery { settings.getFeatureSettings() } returns features
        coEvery { settings.ensureConversationIdentity(any()) } returns "conversation-7"
        every { settings.observeDebugMode() } returns flowOf(false)
        every { settings.observeFeatureSettings() } returns flowOf(features)
        val localModels = mockk<LocalModelRepository>(relaxed = true)
        every { localModels.observeAll() } returns flowOf(emptyList())
        val catalog = mockk<ModelCatalogRepository>(relaxed = true)
        coEvery { catalog.getCachedVisibleEntries() } returns emptyList()
        val completions = mockk<GenerationCompletionStore>(relaxed = true)
        every { completions.items } returns MutableStateFlow(emptyList())
        return ChatViewModel(
            savedStateHandle = SavedStateHandle(mapOf("chatRoomId" to chatId, "enabledPlatforms" to profiles.joinToString(",") { it.uid }, "conversationMode" to room.conversationMode)),
            context = ApplicationProvider.getApplicationContext(),
            chatRepository = repository,
            settingRepository = settings,
            delegationRecoveryInteractions = mockk(relaxed = true),
            attachmentUploadCoordinator = mockk(relaxed = true),
            agentRunCoordinator = coordinator,
            toolConnectionRepository = mockk(relaxed = true),
            localModelRepository = localModels,
            modelCatalogRepository = catalog,
            freeModelToolConsentStore = mockk(relaxed = true),
            conversationReadStateStore = ConversationReadStateStore(completions),
            generationCompletionStore = completions,
            researchSessionStore = research,
            conversationExitWork = exitWork,
            messageGroupingDispatcher = dispatcher
        ).also { store.put("chat", it) }
    }
}
