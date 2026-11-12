package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.agent.ActiveAgentRun
import dev.chungjungsoo.gptmobile.data.agent.AgentRunCoordinator
import dev.chungjungsoo.gptmobile.data.agent.AgentRunNotice
import dev.chungjungsoo.gptmobile.data.agent.AgentRunRequest
import dev.chungjungsoo.gptmobile.data.agent.CombinedAcceptedTurn
import dev.chungjungsoo.gptmobile.data.agent.CombinedAnswerRevisionTarget
import dev.chungjungsoo.gptmobile.data.agent.CombinedExecutionContext
import dev.chungjungsoo.gptmobile.data.agent.CombinedPolicyResolver
import dev.chungjungsoo.gptmobile.data.agent.CombinedQualityReport
import dev.chungjungsoo.gptmobile.data.agent.ProviderWaitDeadline
import dev.chungjungsoo.gptmobile.data.chat.ConversationReadStateStore
import dev.chungjungsoo.gptmobile.data.chat.GenerationCompletionStore
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunDraft
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnRequest
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnResult
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveRunId
import dev.chungjungsoo.gptmobile.data.database.entity.snapshotLatestAssistantRevision
import dev.chungjungsoo.gptmobile.data.github.GitHubChatProfileOverride
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.LocalModelRepository
import dev.chungjungsoo.gptmobile.data.repository.ModelCatalogRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    private val viewModelJobs = mutableListOf<Job>()
    private val repository = mockk<ChatRepository>(relaxed = true)
    private val coordinator = mockk<AgentRunCoordinator>(relaxed = true)
    private val messages = MutableStateFlow<List<MessageV2>>(emptyList())
    private val runs = MutableStateFlow<List<AgentRun>>(emptyList())
    private val waitDeadlines = MutableStateFlow<List<ProviderWaitDeadline>>(emptyList())
    private val activeRuns = MutableStateFlow<Map<String, ActiveAgentRun>>(emptyMap())
    private var profiles = (1..3).map { PlatformV2(uid = "profile-$it", name = "Profile $it", enabled = true, model = "model-$it") }
    private val features = AppFeatureSettings(
        automaticConversationTitles = false,
        profileAttributionColors = dev.chungjungsoo.gptmobile.data.model.ProfileColorRegistry.ensure(emptyMap(), profiles.map { it.uid })
    )

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        every { coordinator.activeRuns } returns activeRuns
        every { coordinator.streamMessages } returns MutableStateFlow(emptyMap())
        every { coordinator.notices } returns MutableSharedFlow<AgentRunNotice>()
        coEvery { coordinator.gatewayConversationWriteBlocker(any()) } returns null
        coEvery { coordinator.gatewayOwnsConversation(any()) } returns false
        coEvery { coordinator.gatewayOwnsRequest(any()) } returns false
    }

    @After fun cleanup() = runTest(dispatcher) {
        try {
            clearViewModels()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private suspend fun clearViewModels() = withContext(NonCancellable) {
        // Clearing cancels the scopes but real IO can still be returning. Keep
        // the current Main delegate and Room connection until every owner ends.
        store.clear()
        viewModelJobs.joinAll()
        viewModelJobs.clear()
    }

    @Test fun freshStandardChatHandlesImmediateEmptyRunEmission() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        val model = createViewModel(chatId = 0, combined = false)
        assertEquals(0, model.chatRoom.value.id)
        assertTrue(model.combinedFailures.value.isEmpty())
        assertTrue(model.loadingStates.value.all { it == ChatViewModel.LoadingState.Idle })
    }

    @Test fun ordinaryTranscriptExportRunsOffTheUiCallerAndRetainsOnlyTheSelectedAnswer() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        messages.value = listOf(
            MessageV2(id = 1, chatId = 7, content = "Private user prompt", platformType = null),
            MessageV2(id = 2, chatId = 7, content = "<think>Internal thinking</think>\n# Saved answer\n\n**Useful** result.", platformType = profiles.first().uid)
        )
        val model = createViewModel(combined = false)
        val caller = Thread.currentThread()
        val preparationThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        coEvery { repository.fetchMessagesV2(7) } answers {
            preparationThread.set(Thread.currentThread())
            messages.value
        }
        val (name, content) = model.exportChat(ChatExportFormat.PLAIN_TEXT)
        assertTrue(name.endsWith(".txt"))
        assertEquals("Saved answer\n\nUseful result.", content)
        assertNotEquals(caller, preparationThread.get())
        assertFalse(content.contains("Private user prompt"))
        assertFalse(content.contains("Internal thinking"))
    }

    @Test fun normalGatewayTurnIsPersistedBeforeItsCoordinatorHandoff() = runTest(dispatcher) {
        verifyGatewayCoordinatorHandoff(backgroundScope)
    }

    @Test fun chatMasterToolDisableIsCapturedBeforeGatewayRouting() = runTest(dispatcher) {
        verifyGatewayCoordinatorHandoff(backgroundScope, disabledTools = true)
    }

    @Test fun anUnconfirmedGatewaySubmissionKeepsTheNextDraftWithoutDispatchingIt() = runTest(dispatcher) {
        verifyGatewayCoordinatorHandoff(backgroundScope, pendingSubmission = true)
    }

    @Test fun anImportedGatewayConversationKeepsItsDraftWhileAdmissionIsUnconfirmed() = runTest(dispatcher) {
        verifyGatewayCoordinatorHandoff(backgroundScope, pendingSubmission = true, imported = true)
    }

    @Test fun retryCannotReplaceAnUnacknowledgedGatewayCommandWithANewRun() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        coEvery { coordinator.gatewayConversationWriteBlocker(any()) } returns null
        coEvery { coordinator.gatewayOwnsConversation(any()) } returns false
        val user = MessageV2(id = 1, chatId = 7, content = "Original saved request", platformType = null)
        val answer = MessageV2(id = 2, chatId = 7, content = "", platformType = profiles.first().uid, currentRunId = "saved-run")
        messages.value = listOf(user, answer)
        runs.value = listOf(AgentRun("saved-run", 7, 1, 2, profiles.first().uid, profiles.first().compatibleType.name, profiles.first().model, AgentRunStatus.WAITING_REMOTE_JOB))
        val checked = CompletableDeferred<Unit>()
        coEvery { coordinator.gatewayOwnsRequest("saved-run") } coAnswers {
            checked.complete(Unit)
            true
        }
        coEvery { coordinator.withChatGate<Unit>(7, any()) } coAnswers { secondArg<suspend () -> Unit>().invoke() }
        val model = createViewModel(combined = false)
        model.retryChat(0, 0)
        withTimeout(10_000) {
            checked.await()
            model.attachmentNotice.first { it.orEmpty().contains("Use Jobs to confirm") }
        }
        coVerify(exactly = 1) { coordinator.gatewayOwnsRequest("saved-run") }
        coVerify(exactly = 0) { repository.persistAgentRetry(any()) }
        verify(exactly = 0) { coordinator.start(any()) }
        assertTrue(model.attachmentNotice.value.orEmpty().contains("Use Jobs to confirm"))
    }

    @Test fun aChangedGatewayBindingCannotFallBackToLocalSendAndKeepsTheDraft() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        val reason = "Restore the original gateway profile before sending."
        coEvery { coordinator.gatewayConversationWriteBlocker(7) } returns reason
        val model = createViewModel(combined = false)
        model.question.edit { replace(0, length, "Continue the original task") }
        model.askQuestion()
        withTimeout(10_000) { model.attachmentNotice.first { it == reason } }
        assertEquals("Continue the original task", model.question.text.toString())
        assertEquals(reason, model.gatewayWriteBlocker.value)
        coVerify(exactly = 0) { repository.persistAgentTurn(any()) }
        verify(exactly = 0) { coordinator.start(any()) }
    }

    @Test fun aForeignGatewayConversationCannotRegenerateThroughLocalRetry() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        coEvery { coordinator.gatewayConversationWriteBlocker(7) } returns null
        coEvery { coordinator.gatewayOwnsConversation(7) } returns true
        messages.value = listOf(
            MessageV2(id = 1, chatId = 7, content = "Saved remote request", platformType = null),
            MessageV2(id = 2, chatId = 7, content = "Saved answer", platformType = profiles.first().uid)
        )
        coEvery { coordinator.withChatGate<Unit>(7, any()) } coAnswers { secondArg<suspend () -> Unit>().invoke() }
        val model = createViewModel(combined = false)
        model.retryChat(0, 0)
        withTimeout(10_000) { model.attachmentNotice.first { it.orEmpty().contains("Use Jobs to confirm") } }
        coVerify(exactly = 0) { repository.persistAgentRetry(any()) }
        verify(exactly = 0) { coordinator.start(any()) }
    }

    @Test fun ordinarySavedConversationObservationDoesNotNeedAGitHubWorkspaceOrSubmitWork() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        coEvery { coordinator.gatewayConversationWriteBlocker(7) } returns "This retained ordinary transcript is read-only."
        val model = createViewModel(combined = false)
        val observing = backgroundScope.launch(dispatcher) { model.observeGatewayConversation(7) }
        withTimeout(10_000) { model.gatewayWriteBlocker.first { it != null } }
        observing.cancel()
        observing.join()
        coVerify(exactly = 1) { repository.refreshGatewayConversation(7) }
        verify(exactly = 0) { coordinator.start(any()) }
    }

    private suspend fun verifyGatewayCoordinatorHandoff(scope: CoroutineScope, disabledTools: Boolean = false, pendingSubmission: Boolean = false, imported: Boolean = false) {
        StartupRecoveryGate.start(scope) {}
        profiles = listOf(profiles.first().copy(compatibleType = dev.chungjungsoo.gptmobile.data.model.ClientType.LLAMA, apiUrl = "http://127.0.0.1:8090"))
        val profile = profiles.single()
        val workspace = GitHubConversationContext("github", "owner", "repository", "feature/original", "a".repeat(40), profile.uid)
        coEvery { coordinator.hasUnconfirmedGatewayAdmission(7, profile.uid) } returns pendingSubmission
        val persisted = CompletableDeferred<PersistAgentTurnResult>()
        coEvery { repository.persistAgentTurn(any()) } answers {
            val request = firstArg<PersistAgentTurnRequest>()
            val draft = request.runs.single()
            val user = request.userMessage.copy(id = 71)
            val assistant = MessageV2(id = 72, chatId = 7, content = "", platformType = profile.uid, linkedMessageId = user.id, currentRunId = draft.runId)
            val run = AgentRun(draft.runId, 7, user.id, assistant.id, profile.uid, profile.compatibleType.name, profile.model, AgentRunStatus.QUEUED)
            PersistAgentTurnResult(request.chatRoom, user, listOf(assistant), listOf(run)).also {
                persisted.complete(it)
                messages.value = listOf(user, assistant)
                runs.value = listOf(run)
            }
        }
        val model = createViewModel(
            combined = false,
            testFeatures = features.copy(
                conversationGitHub = if (imported) emptyMap() else mapOf(7 to workspace),
                githubProfileOverrides = if (imported) emptyMap() else mapOf(profile.uid to GitHubChatProfileOverride(engineeringGatewayProfileUid = "gateway"))
            )
        )
        val dispatched = CompletableDeferred<AgentRunRequest>()
        every { coordinator.start(any()) } answers {
            assertTrue(persisted.isCompleted)
            dispatched.complete(firstArg<List<AgentRunRequest>>().single())
            Unit
        }
        if (disabledTools) model.disableAllChatTools()
        model.question.edit { replace(0, length, "What is the repository status?") }
        model.askQuestion()
        if (pendingSubmission) {
            withTimeout(10_000) { model.attachmentNotice.first { it.orEmpty().contains("previous gateway submission") } }
            assertEquals("What is the repository status?", model.question.text.toString())
            coVerify(exactly = 0) { repository.persistAgentTurn(any()) }
            verify(exactly = 0) { coordinator.start(any()) }
            return
        }
        val handoff = withTimeout(10_000) { dispatched.await() }
        val accepted = persisted.await()
        assertEquals(accepted.runs.single().runId, handoff.runId)
        assertEquals(accepted.userMessage.id, handoff.userMessages.last().id)
        assertEquals(accepted.assistantMessages.single().id, handoff.assistantMessage.id)
        assertEquals(workspace, handoff.chatToolConfig?.github)
        assertEquals(profile.model, handoff.platform.model)
        assertEquals(disabledTools, handoff.chatToolConfig?.allToolsDisabled)
        assertEquals(!disabledTools, requireNotNull(handoff.gatewayAdmissionAllowed).invoke())
        model.disableAllChatTools()
        assertFalse(requireNotNull(handoff.gatewayAdmissionAllowed).invoke())
        coVerify(exactly = 0) { repository.finishQueuedAgentRun(any(), any(), any(), any()) }
    }

    @Test fun rejectedFreeProfileShowsItsNoticeBeforeOffMainDiagnosticPersistenceCompletes() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        val caller = Thread.currentThread()
        val entered = CompletableDeferred<Thread>()
        val releaseStorage = java.util.concurrent.CountDownLatch(1)
        val folder = java.nio.file.Files.createTempDirectory("preflight-thread").toFile()
        try {
            dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.initialize {
                entered.complete(Thread.currentThread())
                check(releaseStorage.await(10, java.util.concurrent.TimeUnit.SECONDS))
                java.io.File(folder, "failures.json")
            }
            val model = createViewModel(combined = false)
            val profile = profiles.first().copy(
                apiUrl = dev.chungjungsoo.gptmobile.data.model.FreeAiProvider.OPENCODE.apiUrl,
                compatibleType = dev.chungjungsoo.gptmobile.data.model.ClientType.FREE
            )
            val reject = ChatViewModel::class.java.getDeclaredMethod("rejectUnavailableFreeProfile", PlatformV2::class.java).apply { isAccessible = true }
            assertEquals(true, reject.invoke(model, profile))
            assertTrue(model.freeProviderNotice.value.orEmpty().contains("Your draft and conversation are kept"))
            verify(exactly = 0) { coordinator.start(any()) }
            val storageThread = withContext(Dispatchers.IO) { withTimeout(10_000) { entered.await() } }
            assertNotEquals(caller, storageThread)
            releaseStorage.countDown()
            val saved = withContext(Dispatchers.IO) { dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.snapshot(7) }
            assertEquals(1, saved.size)
            assertEquals(profile.uid, saved.single().profileUid)
            assertEquals(7, saved.single().chatId)
            assertEquals("preflight", saved.single().stage)
        } finally {
            releaseStorage.countDown()
            clearViewModels()
            withContext(Dispatchers.IO) { dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.clear() }
            folder.deleteRecursively()
        }
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

    @Test fun initialPreflightWaitRestoresItsObservableDeadlineWithoutAnErrorOrTimelineFailure() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        seedTurn(listOf(AgentRunStatus.COMPLETED, AgentRunStatus.WAITING_PROVIDER_LIMIT, AgentRunStatus.COMPLETED))
        val waiting = runs.value[1]
        assertNull(waiting.terminalError)
        assertTrue(messages.value.single { it.currentRunId == waiting.runId }.timeline.isEmpty())
        val model = createViewModel()
        val pending = model.combinedFailures.value.single()
        assertEquals(waiting.runId, pending.attemptId)
        assertFalse(pending.retryAllowed)
        assertNull(pending.retryAfterMillis)
        assertEquals("Paused by provider limit", pending.shortStatus(System.currentTimeMillis()))

        val deadline = ProviderWaitDeadline(waiting.runId, waiting.ownershipFence, System.currentTimeMillis() + 120_000)
        waitDeadlines.value = listOf(deadline)
        assertEquals(deadline.retryAt, model.combinedFailures.value.single().retryAfterMillis)
        assertEquals("RATE_LIMIT", model.combinedFailures.value.single().category)
        model.retryCombinedProfile(1, waiting.profileUid)
        verify(exactly = 0) { coordinator.start(any()) }
        coVerify(exactly = 0) { repository.persistAgentRetry(any()) }

        val extended = deadline.copy(retryAt = deadline.retryAt + 60_000)
        waitDeadlines.value = listOf(extended)
        assertEquals(extended.retryAt, model.combinedFailures.value.single().retryAfterMillis)
        clearViewModels()
        val restored = createViewModel()
        assertEquals(extended.retryAt, restored.combinedFailures.value.single().retryAfterMillis)
        assertEquals(waiting.runId, restored.combinedFailures.value.single().attemptId)
        verify(exactly = 0) { coordinator.start(any()) }
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
        clearViewModels()
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

    @Test fun viewModelCleanupFinishesIoBeforeReplacingMainDispatcher() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        val ioStarted = CompletableDeferred<Unit>()
        val cancellationStarted = CompletableDeferred<Unit>()
        val releaseIo = CompletableDeferred<Unit>()
        val ioFinished = CompletableDeferred<Unit>()
        val connections = mockk<ToolConnectionRepository>(relaxed = true)
        coEvery { connections.getAllConnections() } coAnswers {
            try {
                ioStarted.complete(Unit)
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cancellationStarted.complete(Unit)
                    releaseIo.await()
                    ioFinished.complete(Unit)
                }
            }
        }
        try {
            val model = createViewModel(combined = false, toolConnections = connections)
            assertEquals("Saved draft", model.question.text.toString())
            val owner = requireNotNull(model.viewModelScope.coroutineContext[Job])
            ioStarted.await()
            val clearing = async { clearViewModels() }
            cancellationStarted.await()
            assertFalse("Main must remain installed while cancelled IO is finishing", clearing.isCompleted)
            assertFalse(ioFinished.isCompleted)
            releaseIo.complete(Unit)
            clearing.await()
            assertTrue(ioFinished.isCompleted)
            assertTrue(owner.isCompleted)
            Dispatchers.setMain(StandardTestDispatcher(dispatcher.scheduler))
        } finally {
            releaseIo.complete(Unit)
        }
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

    @Test fun completedEditorialAnswerCreatesNewAcceptedTurnBeforeDispatchInsteadOfResuming() = runTest(dispatcher) {
        verifyExplicitRevision(archived = false, scope = backgroundScope)
    }

    @Test fun archivedEditorialSelectionCreatesRevisionOfThatAnswerWhileRetainingLatestPartial() = runTest(dispatcher) {
        verifyExplicitRevision(archived = true, scope = backgroundScope)
    }

    @Test fun explicitLongAnswerOptOutRemainsStrictWhenRevisingAnArchivedAnswer() = runTest(dispatcher) {
        verifyExplicitRevision(archived = true, scope = backgroundScope, combinedSettings = ConversationCombinedSettings(continueLongAnswers = false))
    }

    @Test fun aStaleDisplayedRevisionCallbackCannotSilentlyTargetTheLatestAnswer() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        val selectedRun = seedEditorialRevision(archived = true)
        val model = createViewModel()
        val owner = model.groupedMessages.value.assistantMessages.single().first { it.id == selectedRun.assistantMessageId }
        val target = CombinedAnswerRevisionTarget.capture(owner, selectedRun)
        model.showNextAssistantRevision(0, 0)
        model.reviseCombinedAnswer(0, target)
        coVerify(exactly = 0) { repository.prepareCombinedTurn(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.regenerateCombinedTurn(any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.prepareCombinedRetry(any(), any()) }
        verify(exactly = 0) { coordinator.start(any()) }
        assertTrue(model.attachmentNotice.value.orEmpty().contains("selected answer changed"))
    }

    private suspend fun verifyExplicitRevision(archived: Boolean, scope: CoroutineScope, combinedSettings: ConversationCombinedSettings? = null) {
        // Room callbacks must rejoin serialized Main before a UI action reads startup state.
        // Other startup tests keep Unconfined Main to exercise immediate cached emissions.
        Dispatchers.setMain(StandardTestDispatcher(dispatcher.scheduler))
        StartupRecoveryGate.start(scope) {}
        val selectedRun = seedEditorialRevision(archived)
        val legacyPolicy = requireNotNull(CombinedAcceptedTurn.decode(selectedRun.synthesisSnapshot)).context.requested
        assertEquals(1, legacyPolicy.version)
        assertFalse(legacyPolicy.appendContinuations)
        assertFalse(legacyPolicy.settings.continueLongAnswers)
        val originalOwner = messages.value.single { it.id == selectedRun.assistantMessageId }
        val room = ChatRoomV2(id = 7, title = "Saved chat", conversationMode = ConversationMode.COMBINED, enabledPlatform = profiles.map { it.uid }, activePlatform = profiles.map { it.uid })
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ChatDatabaseV2::class.java).build()
        try {
            db.chatRoomDao().addChatRoom(room)
            db.messageDao().addMessages(*messages.value.toTypedArray())
            runs.value.forEach { db.agentRunDao().upsert(it) }
            val dao = db.agentPersistenceDao()
            val expectedTarget = CombinedAnswerRevisionTarget.capture(originalOwner, selectedRun)
            val restoredOwner = dao.getMessages(7).single { it.id == originalOwner.id }
            assertEquals("The completed assessment must survive the same Room restoration used during startup", expectedTarget, CombinedAnswerRevisionTarget.capture(restoredOwner, selectedRun))
            var committed: PersistAgentTurnResult? = null
            var savedAcceptance: CombinedAcceptedTurn? = null
            val started = CompletableDeferred<List<AgentRunRequest>>()
            coEvery { repository.prepareCombinedTurn(any(), any(), any(), any(), any()) } answers {
                firstArg<CombinedExecutionContext>().let { it.copy(plan = CombinedPolicyResolver.resolve(it.requested)) }
            }
            coEvery { repository.regenerateCombinedTurn(any(), any(), any(), any()) } coAnswers {
                val accepted = arg<CombinedAcceptedTurn>(3)
                savedAcceptance = accepted
                dao.regenerateCombinedTurn(arg(0), arg(1), arg<List<AgentRunDraft>>(2), accepted).also { committed = it }
            }
            coEvery { repository.fetchMessageReferencesV2(7) } coAnswers { dao.getMessages(7) }
            every { coordinator.start(any()) } answers {
                assertTrue("No provider starts before the Room transaction completes", committed != null)
                started.complete(firstArg())
                Unit
            }
            val model = createViewModel(combinedSettings = combinedSettings)
            // Messages and run receipts are restored independently. The revision
            // action is available only when its selected answer and run agree.
            withContext(Dispatchers.Default) {
                withTimeout(20_000) {
                    combine(model.groupedMessages, model.agentRunsById) { messages, runs -> messages to runs }
                        .first { (messages, runs) ->
                            runs[selectedRun.runId] == selectedRun &&
                                messages.userMessages.any { it.id == selectedRun.userMessageId } &&
                                messages.assistantMessages.flatten().any { answer ->
                                    answer.id == selectedRun.assistantMessageId &&
                                        answer.currentRunId == originalOwner.currentRunId &&
                                        answer.ownershipFence == originalOwner.ownershipFence &&
                                        answer.activeRevisionIndex == originalOwner.activeRevisionIndex &&
                                        answer.effectiveRunId() == selectedRun.runId
                                }
                        }.first
                }
            }
            val target = withContext(Dispatchers.Main) {
                val owner = model.groupedMessages.value.assistantMessages.single().first { it.id == selectedRun.assistantMessageId }
                CombinedAnswerRevisionTarget.capture(owner, selectedRun).also { target ->
                    assertEquals(expectedTarget, target)
                    model.reviseCombinedAnswer(0, target)
                    assertNull("The selected revision action must be accepted before waiting for dispatch", model.attachmentNotice.value)
                }
            }
            val requests = withContext(Dispatchers.Default) { withTimeout(20_000) { started.await() } }
            val accepted = requireNotNull(savedAcceptance)
            assertEquals(target, accepted.revisionOf)
            assertEquals("selected-root", accepted.parentRootBudgetId)
            assertNotEquals("selected-root", accepted.context.rootBudgetId)
            assertNotEquals("latest-root", accepted.context.rootBudgetId)
            assertTrue(accepted.context.requested.singlePass)
            assertEquals("A new revision uses current preferences without changing its frozen predecessor", combinedSettings?.continueLongAnswers ?: true, accepted.context.requested.appendContinuations)
            assertEquals(profiles.map { it.uid }, requests.map { it.platform.uid })
            assertTrue(requests.all { it.runId != selectedRun.runId && it.runId != originalOwner.currentRunId })
            assertTrue(requests.all { it.chatToolConfig?.combined?.rootBudgetId == accepted.context.rootBudgetId })
            val retained = requireNotNull(dao.recoveryMessage(originalOwner.id)).revisions
            assertEquals(originalOwner.content, retained.first().content)
            assertEquals(originalOwner.effectiveContent(), retained.last().content)
            assertEquals(selectedRun, dao.recoveryRun(selectedRun.runId))
            if (archived) {
                assertEquals(2, retained.size)
                assertEquals("combined-synthesis:latest", accepted.revisionOf?.currentRunId)
                assertEquals("combined-synthesis:selected", accepted.revisionOf?.runId)
            }
            coVerify(exactly = 1) { repository.regenerateCombinedTurn(any(), any(), any(), any()) }
            coVerify(exactly = 0) { repository.prepareCombinedRetry(any(), any()) }
            coVerify(exactly = 0) { repository.persistAgentRetry(any()) }
        } finally {
            clearViewModels()
            db.close()
            Dispatchers.setMain(dispatcher)
        }
    }

    private fun seedEditorialRevision(archived: Boolean): AgentRun {
        seedTurn(List(3) { AgentRunStatus.COMPLETED }, ConversationCombinedSettings(continueLongAnswers = false))
        val original = messages.value.first { it.platformType != null }.copy(linkedMessageId = 1)
        val body = "The original complete answer needs more detail."
        val report = CombinedQualityReport(answerDigest = researchHash(body), status = "CORRECTION_REQUIRED", finalAssessment = true, protocolComplete = true, correctionRequired = true, lengthCompliance = "UNMET")
        val selected = original.copy(
            content = body,
            currentRunId = "combined-synthesis:selected",
            ownershipFence = 2,
            combinedSources = listOf(CombinedModelResponse("profile-1", "Source", content = "Saved source body.")),
            timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.TEXT, content = body), AssistantTimelineItem(AssistantTimelineItemType.NOTICE, combinedQuality = report))
        )
        val originalAcceptance = requireNotNull(CombinedAcceptedTurn.decode(runs.value.first().synthesisSnapshot))
        val lineage = originalAcceptance.copy(context = originalAcceptance.context.copy(rootBudgetId = "selected-root"))
        val selectedRun = runs.value.first().copy(runId = requireNotNull(selected.currentRunId), status = AgentRunStatus.FAILED, ownershipFence = selected.ownershipFence, terminalError = "COMBINED_PARTIAL", synthesisKey = researchHash(lineage.encode()), synthesisSnapshot = lineage.encode())
        val current = if (archived) {
            selected.copy(
                content = "Latest unfinished answer",
                currentRunId = "combined-synthesis:latest",
                ownershipFence = 3,
                activeRevisionIndex = 0,
                revisions = listOf(requireNotNull(selected.snapshotLatestAssistantRevision(1))),
                timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.TEXT, content = "Latest unfinished answer"))
            )
        } else {
            selected
        }
        messages.value = messages.value.map { row ->
            if (row.id == current.id) {
                current
            } else if (row.platformType != null) {
                row.copy(linkedMessageId = 1)
            } else {
                row
            }
        }
        runs.value = runs.value.drop(1) + selectedRun + if (archived) listOf(selectedRun.copy(runId = requireNotNull(current.currentRunId), ownershipFence = current.ownershipFence, terminalError = "NETWORK_ERROR", synthesisSnapshot = lineage.copy(context = lineage.context.copy(rootBudgetId = "latest-root")).encode())) else emptyList()
        return selectedRun
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

    @Test fun restoredGatewaySelectionAndModelChangePrepareWithoutSubmittingTheDraft() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        profiles = profiles.take(1).map { it.copy(compatibleType = dev.chungjungsoo.gptmobile.data.model.ClientType.LLAMA) }
        val selected = profiles.single()
        val preparation = mockk<dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationRepository>()
        val models = mutableListOf<String>()
        coEvery { preparation.prepareSelected(any(), any(), any(), any()) } coAnswers {
            val profile = firstArg<PlatformV2>()
            models += profile.model
            arg<(dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationState?) -> Unit>(3)(
                dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationState(profile.uid, profile.model, dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationPhase.LOADING, "Loading exact model")
            )
            kotlinx.coroutines.awaitCancellation()
        }
        val model = createViewModel(combined = false, modelPreparation = preparation)
        assertEquals(listOf(selected.model), models)
        assertEquals("Saved draft", model.question.text.toString())
        model.updateChatPlatformModels(mapOf(selected.uid to "exact-chat-override"))
        assertEquals(listOf(selected.model, "exact-chat-override"), models)
        assertEquals("exact-chat-override", model.gatewayModelPreparation.value[selected.uid]?.model)
        assertEquals("Saved draft", model.question.text.toString())
        verify(exactly = 0) { coordinator.start(any()) }
        coVerify(exactly = 0) { repository.persistAgentTurn(any()) }
        model.togglePlatformDisabled(selected.uid)
        assertTrue(model.gatewayModelPreparation.value.isEmpty())
    }

    @Test fun selectedModelPreparationUsesTheSameConversationReasoningOverrideAsSend() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        profiles = profiles.take(1).map { it.copy(compatibleType = dev.chungjungsoo.gptmobile.data.model.ClientType.LLAMA) }
        val selected = profiles.single()
        for (conversation in listOf(false, true)) {
            val preparation = mockk<dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationRepository>()
            val requested = mutableListOf<dev.chungjungsoo.gptmobile.data.context.ReasoningMode>()
            coEvery { preparation.prepareSelected(any(), any(), any(), any()) } coAnswers {
                requested += secondArg<dev.chungjungsoo.gptmobile.data.context.ReasoningMode>()
                null
            }
            val settings = features.copy(
                llamaReasoningModes = mapOf(selected.uid to if (conversation) dev.chungjungsoo.gptmobile.data.context.ReasoningMode.OFF else dev.chungjungsoo.gptmobile.data.context.ReasoningMode.ON),
                conversationReasoning = mapOf(7 to conversation)
            )
            createViewModel(combined = false, modelPreparation = preparation, testFeatures = settings)
            assertEquals(listOf(if (conversation) dev.chungjungsoo.gptmobile.data.context.ReasoningMode.ON else dev.chungjungsoo.gptmobile.data.context.ReasoningMode.OFF), requested)
            clearViewModels()
        }
        verify(exactly = 0) { coordinator.start(any()) }
    }

    @Test fun strictOffSendPreservesDraftAndExplicitRecheckNeverAutomaticallyDispatches() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        profiles = profiles.take(1).map { it.copy(compatibleType = dev.chungjungsoo.gptmobile.data.model.ClientType.LLAMA) }
        val profile = profiles.single().copy(model = "exact-chat-override")
        val capabilities = mockk<dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityRepository>()
        val unsupported = dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityObservation(profile.uid, profile.model, dev.chungjungsoo.gptmobile.data.context.ReasoningMode.OFF, dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityStatus.UNSUPPORTED, "opaque", "runtime", System.currentTimeMillis(), "REASONING_OFF_UNSUPPORTED", true, false)
        val supported = unsupported.copy(status = dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityStatus.SUPPORTED, safeReasonCode = null, supportVerified = true)
        // Cached support cannot admit Send; both Send and manual Recheck require fresh evidence.
        coEvery { capabilities.check(any(), any(), false) } returns supported
        coEvery { capabilities.check(any(), any(), true) } returnsMany listOf(unsupported, unsupported, supported, supported)
        coEvery { repository.persistAgentTurn(any()) } answers {
            val request = firstArg<PersistAgentTurnRequest>()
            val draft = request.runs.single()
            val user = request.userMessage.copy(id = 71)
            val answer = MessageV2(id = 72, chatId = 7, content = "", platformType = profile.uid, linkedMessageId = 71, currentRunId = draft.runId)
            val run = AgentRun(draft.runId, 7, 71, 72, profile.uid, profile.compatibleType.name, profile.model)
            PersistAgentTurnResult(request.chatRoom, user, listOf(answer), listOf(run))
        }
        val dispatched = CompletableDeferred<Unit>()
        every { coordinator.start(any()) } answers {
            dispatched.complete(Unit)
            Unit
        }
        val model = createViewModel(combined = false, reasoningCapabilities = capabilities)
        model.updateChatPlatformModels(mapOf(profile.uid to profile.model))
        model.question.edit { replace(0, length, "Keep this draft") }
        model.askQuestion()
        model.askQuestion()
        assertEquals("Keep this draft", model.question.text.toString())
        assertEquals(dev.chungjungsoo.gptmobile.data.context.ReasoningMode.OFF, model.reasoningAdmissionNotice.value?.requested)
        assertEquals(profile.model, model.reasoningAdmissionNotice.value?.model)
        model.recheckReasoningAdmission()
        assertEquals(dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityStatus.SUPPORTED, model.reasoningAdmissionNotice.value?.state?.observation?.status)
        assertEquals("Keep this draft", model.question.text.toString())
        coVerify(exactly = 0) { capabilities.check(any(), any(), false) }
        coVerify(exactly = 3) { capabilities.check(match { it.uid == profile.uid && it.model == profile.model }, dev.chungjungsoo.gptmobile.data.context.ReasoningMode.OFF, true) }
        verify(exactly = 0) { coordinator.start(any()) }
        coVerify(exactly = 0) { repository.persistAgentTurn(any()) }

        model.askQuestion()
        withTimeout(5000) { dispatched.await() }
        assertEquals("", model.question.text.toString())
        coVerify(exactly = 4) { capabilities.check(match { it.uid == profile.uid && it.model == profile.model }, dev.chungjungsoo.gptmobile.data.context.ReasoningMode.OFF, true) }
        coVerify(exactly = 1) { repository.persistAgentTurn(match { it.userMessage.content == "Keep this draft" && it.runs.single().modelSnapshot == profile.model }) }
        verify(exactly = 1) { coordinator.start(any()) }
    }

    @Test fun draftChangedDuringReasoningCheckCannotSendTheOldRequest() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        profiles = profiles.take(1).map { it.copy(compatibleType = dev.chungjungsoo.gptmobile.data.model.ClientType.LLAMA) }
        val profile = profiles.single()
        val result = CompletableDeferred<dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityObservation>()
        val capabilities = mockk<dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityRepository>()
        coEvery { capabilities.check(any(), any(), any()) } coAnswers { result.await() }
        val model = createViewModel(combined = false, reasoningCapabilities = capabilities)
        model.question.edit { replace(0, length, "Original draft") }
        model.askQuestion()
        assertEquals(setOf(ChatViewModel.SendPreparation.MODEL_CHECK), model.sendPreparation.value)
        repeat(7) { model.askQuestion() }
        coVerify(exactly = 1) { capabilities.check(any(), any(), any()) }
        model.question.edit { replace(0, length, "Newer draft") }
        result.complete(dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityObservation(profile.uid, profile.model, dev.chungjungsoo.gptmobile.data.context.ReasoningMode.OFF, dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityStatus.SUPPORTED, "opaque", "runtime", System.currentTimeMillis(), null, true, true))
        assertEquals("Newer draft", model.question.text.toString())
        assertTrue(model.sendPreparation.value.isEmpty())
        verify(exactly = 0) { coordinator.start(any()) }
        coVerify(exactly = 0) { repository.persistAgentTurn(any()) }
    }

    @Test fun preparingSendCoalescesTapsAndAutomaticallySubmitsOnceAfterBothChecks() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        profiles = profiles.take(1).map { it.copy(compatibleType = dev.chungjungsoo.gptmobile.data.model.ClientType.LLAMA) }
        val profile = profiles.single()
        val check = CompletableDeferred<dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityObservation>()
        val gateway = CompletableDeferred<String?>()
        val capabilities = mockk<dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityRepository>()
        coEvery { capabilities.check(any(), any(), any()) } coAnswers { check.await() }
        coEvery { repository.persistAgentTurn(any()) } answers {
            val request = firstArg<PersistAgentTurnRequest>()
            val draft = request.runs.single()
            val user = request.userMessage.copy(id = 71)
            val answer = MessageV2(id = 72, chatId = 7, content = "", platformType = profile.uid, linkedMessageId = 71, currentRunId = draft.runId)
            val run = AgentRun(draft.runId, 7, 71, 72, profile.uid, profile.compatibleType.name, profile.model)
            PersistAgentTurnResult(request.chatRoom, user, listOf(answer), listOf(run))
        }
        val dispatched = CompletableDeferred<Unit>()
        every { coordinator.start(any()) } answers {
            dispatched.complete(Unit)
            Unit
        }
        val model = createViewModel(combined = false, reasoningCapabilities = capabilities)
        coEvery { coordinator.gatewayConversationWriteBlocker(7) } coAnswers { gateway.await() }
        model.question.edit { replace(0, length, "Send this once") }
        model.askQuestion()
        repeat(7) { model.askQuestion() }
        assertEquals("Send this once", model.question.text.toString())
        assertEquals(setOf(ChatViewModel.SendPreparation.MODEL_CHECK), model.sendPreparation.value)
        check.complete(dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityObservation(profile.uid, profile.model, dev.chungjungsoo.gptmobile.data.context.ReasoningMode.OFF, dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityStatus.SUPPORTED, "configuration", "runtime", System.currentTimeMillis(), null, true, true))
        withTimeout(5000) { model.sendPreparation.first { it == setOf(ChatViewModel.SendPreparation.GATEWAY_CHECK) } }
        repeat(7) { model.askQuestion() }
        gateway.complete(null)
        withTimeout(5000) { dispatched.await() }
        assertTrue(model.sendPreparation.value.isEmpty())
        assertEquals("", model.question.text.toString())
        coVerify(exactly = 1) { capabilities.check(any(), any(), any()) }
        coVerify(exactly = 1) { repository.persistAgentTurn(any()) }
        verify(exactly = 1) { coordinator.start(any()) }
    }

    @Test fun connectionChangedDuringGatewayCheckCannotReuseTheEarlierModelAdmission() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        profiles = profiles.take(1).map { it.copy(compatibleType = dev.chungjungsoo.gptmobile.data.model.ClientType.LLAMA) }
        val profile = profiles.single()
        val gateway = CompletableDeferred<String?>()
        val capabilities = mockk<dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityRepository>()
        coEvery { capabilities.check(any(), any(), any()) } returns dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityObservation(profile.uid, profile.model, dev.chungjungsoo.gptmobile.data.context.ReasoningMode.OFF, dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityStatus.SUPPORTED, "configuration", "runtime", System.currentTimeMillis(), null, true, true)
        val model = createViewModel(combined = false, reasoningCapabilities = capabilities)
        coEvery { coordinator.gatewayConversationWriteBlocker(7) } coAnswers { gateway.await() }
        model.question.edit { replace(0, length, "Keep the original request") }
        model.askQuestion()
        withTimeout(5000) { model.sendPreparation.first { ChatViewModel.SendPreparation.GATEWAY_CHECK in it } }
        profiles = listOf(profile.copy(apiUrl = "https://changed-connection.example/v1", token = "changed-test-account"))
        model.refreshLocalNetworkRequirement()
        withTimeout(5000) { model.platformsInApp.first { it == profiles } }
        gateway.complete(null)
        withTimeout(5000) { model.sendPreparation.first { it.isEmpty() } }
        assertEquals("Keep the original request", model.question.text.toString())
        assertTrue(model.attachmentNotice.value.orEmpty().contains("connection changed"))
        coVerify(exactly = 0) { repository.persistAgentTurn(any()) }
        verify(exactly = 0) { coordinator.start(any()) }
    }

    @Test fun unverifiedStrictModeIsExplainedWithoutClaimingTheModelIsUnsupported() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        profiles = profiles.take(1).map { it.copy(compatibleType = dev.chungjungsoo.gptmobile.data.model.ClientType.LLAMA) }
        val profile = profiles.single()
        val capabilities = mockk<dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityRepository>()
        coEvery { capabilities.check(any(), any(), any()) } returns dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityObservation(profile.uid, profile.model, dev.chungjungsoo.gptmobile.data.context.ReasoningMode.OFF, dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityStatus.UNVERIFIED, "opaque", null, System.currentTimeMillis(), "REASONING_CAPABILITY_UNVERIFIED", false, false)
        val model = createViewModel(combined = false, reasoningCapabilities = capabilities)
        model.question.edit { replace(0, length, "Retain me") }
        model.askQuestion()
        assertEquals(dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityStatus.UNVERIFIED, model.reasoningAdmissionNotice.value?.state?.observation?.status)
        assertEquals("Retain me", model.question.text.toString())
        verify(exactly = 0) { coordinator.start(any()) }
        coVerify(exactly = 0) { repository.persistAgentTurn(any()) }
    }

    @Test fun reasoningRecoveryChecksFrozenOffWithoutDispatchOrChangingSavedReplies() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        profiles = profiles.map { if (it.uid == "profile-2") it.copy(compatibleType = dev.chungjungsoo.gptmobile.data.model.ClientType.LLAMA) else it }
        seedTurn(listOf(AgentRunStatus.COMPLETED, AgentRunStatus.FAILED, AgentRunStatus.COMPLETED))
        runs.value = runs.value.map { if (it.profileUid == "profile-2") it.copy(terminalError = "REASONING_OFF_UNSUPPORTED") else it }
        val capabilities = mockk<dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityRepository>()
        coEvery { capabilities.check(any(), any(), any()) } answers {
            val profile = firstArg<PlatformV2>()
            dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityObservation(profile.uid, profile.model, secondArg(), dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityStatus.UNSUPPORTED, "checked-config", "runtime", System.currentTimeMillis(), "REASONING_OFF_UNSUPPORTED", true, false)
        }
        val model = createViewModel(testFeatures = features.copy(llamaReasoningModes = mapOf("profile-2" to dev.chungjungsoo.gptmobile.data.context.ReasoningMode.OFF)), reasoningCapabilities = capabilities)
        model.retryChat(0, 1)
        model.recheckReasoningFailure(1, "profile-2")
        coVerify(exactly = 1) { capabilities.check(match { it.uid == "profile-2" && it.model == "model-2" }, dev.chungjungsoo.gptmobile.data.context.ReasoningMode.OFF, true) }
        assertFalse(model.reasoningRecoveries.value.getValue("run-2").qualified)
        assertFalse(model.combinedFailures.value.single().retryAllowed)
        model.retryCombinedProfile(1, "profile-2")
        assertEquals("A complete useful response.", model.groupedMessages.value.assistantMessages.single().first().content)
        verify(exactly = 0) { coordinator.start(any()) }
        coVerify(exactly = 0) { repository.persistAgentTurn(any()) }
    }

    @Test fun changedAutoPreferenceCannotReplaceTheFrozenOffRequest() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        profiles = profiles.map { if (it.uid == "profile-2") it.copy(compatibleType = dev.chungjungsoo.gptmobile.data.model.ClientType.LLAMA) else it }
        seedTurn(listOf(AgentRunStatus.COMPLETED, AgentRunStatus.FAILED, AgentRunStatus.COMPLETED))
        runs.value = runs.value.map { if (it.profileUid == "profile-2") it.copy(terminalError = "REASONING_OFF_UNSUPPORTED") else it }
        val capabilities = mockk<dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityRepository>()
        val model = createViewModel(testFeatures = features.copy(llamaReasoningModes = mapOf("profile-2" to dev.chungjungsoo.gptmobile.data.context.ReasoningMode.AUTO)), reasoningCapabilities = capabilities)
        model.recheckReasoningFailure(1, "profile-2")
        model.retryCombinedProfile(1, "profile-2")
        coVerify(exactly = 0) { capabilities.check(any(), any(), any()) }
        verify(exactly = 0) { coordinator.start(any()) }
        assertFalse(model.combinedFailures.value.single().retryAllowed)
        assertTrue(model.attachmentNotice.value.orEmpty().contains("start a new request"))
    }

    @Test fun onlyVerifiedExactReasoningQualificationUnlocksTheFailedContributor() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        profiles = profiles.map { if (it.uid == "profile-2") it.copy(compatibleType = dev.chungjungsoo.gptmobile.data.model.ClientType.LLAMA) else it }
        seedTurn(listOf(AgentRunStatus.COMPLETED, AgentRunStatus.FAILED, AgentRunStatus.COMPLETED))
        runs.value = runs.value.map { if (it.profileUid == "profile-2") it.copy(terminalError = "REASONING_OFF_UNSUPPORTED") else it }
        val capabilities = mockk<dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityRepository>()
        coEvery { capabilities.check(any(), any(), any()) } answers {
            val profile = firstArg<PlatformV2>()
            dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityObservation(profile.uid, profile.model, secondArg(), dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityStatus.SUPPORTED, "checked-config", "runtime", System.currentTimeMillis(), null, true, true)
        }
        val model = createViewModel(reasoningCapabilities = capabilities)
        assertFalse(model.combinedFailures.value.single().retryAllowed)
        model.recheckReasoningFailure(1, "profile-2")
        assertTrue(model.reasoningRecoveries.value.getValue("run-2").qualified)
        assertTrue(model.combinedFailures.value.single().retryAllowed)
        assertEquals(listOf("profile-2"), model.combinedFailures.value.map { it.profileUid })
        verify(exactly = 0) { coordinator.start(any()) }
    }

    @Test fun billingUnavailableCannotBeReplayedByTheGenericRetryControl() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        seedTurn(listOf(AgentRunStatus.FAILED, AgentRunStatus.COMPLETED, AgentRunStatus.COMPLETED))
        runs.value = runs.value.map { if (it.profileUid == "profile-1") it.copy(terminalError = "BILLING_UNAVAILABLE: credit balance too low") else it }
        val model = createViewModel(combined = false)
        model.retryChat(0, 0)
        verify(exactly = 0) { coordinator.start(any()) }
        coVerify(exactly = 0) { repository.persistAgentTurn(any()) }
        assertTrue(model.attachmentNotice.value.orEmpty().contains("provider credits"))
        assertEquals("A complete useful response.", model.groupedMessages.value.assistantMessages.single()[1].content)
    }

    @Test fun aRequalificationForAReplacedAttemptCannotUnlockTheNewAttempt() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        profiles = profiles.map { if (it.uid == "profile-2") it.copy(compatibleType = dev.chungjungsoo.gptmobile.data.model.ClientType.LLAMA) else it }
        seedTurn(listOf(AgentRunStatus.COMPLETED, AgentRunStatus.FAILED, AgentRunStatus.COMPLETED))
        runs.value = runs.value.map { if (it.profileUid == "profile-2") it.copy(terminalError = "REASONING_OFF_UNSUPPORTED") else it }
        val result = CompletableDeferred<dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityObservation>()
        val capabilities = mockk<dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityRepository>()
        coEvery { capabilities.check(any(), any(), any()) } coAnswers { result.await() }
        val model = createViewModel(reasoningCapabilities = capabilities)
        model.recheckReasoningFailure(1, "profile-2")
        assertTrue(model.reasoningRecoveries.value.getValue("run-2").checking)
        runs.value = runs.value.map { if (it.profileUid == "profile-2") it.copy(runId = "replacement") else it }
        messages.value = messages.value.map { if (it.platformType == "profile-2") it.copy(currentRunId = "replacement") else it }
        result.complete(dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityObservation("profile-2", "model-2", dev.chungjungsoo.gptmobile.data.context.ReasoningMode.OFF, dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityStatus.SUPPORTED, "checked-config", "runtime", System.currentTimeMillis(), null, true, true))
        assertFalse(model.reasoningRecoveries.value.values.any { it.qualified })
        assertFalse(model.combinedFailures.value.single().retryAllowed)
        verify(exactly = 0) { coordinator.start(any()) }
    }

    @Test fun dictatedTurnKeepsItsDraftDuringCheckingAndBindsOnlyTheAcceptedRun() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        profiles = profiles.take(1).map { it.copy(compatibleType = dev.chungjungsoo.gptmobile.data.model.ClientType.LLAMA) }
        val profile = profiles.single()
        val check = CompletableDeferred<dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityObservation>()
        val capabilities = mockk<dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityRepository>()
        coEvery { capabilities.check(any(), any(), any()) } coAnswers { check.await() }
        val persisted = CompletableDeferred<PersistAgentTurnResult>()
        coEvery { repository.persistAgentTurn(any()) } answers {
            val request = firstArg<PersistAgentTurnRequest>()
            val draft = request.runs.single()
            val user = request.userMessage.copy(id = 71)
            val answer = MessageV2(id = 72, chatId = 7, content = "", platformType = profile.uid, linkedMessageId = 71, currentRunId = draft.runId)
            val run = AgentRun(draft.runId, 7, 71, 72, profile.uid, profile.compatibleType.name, profile.model)
            PersistAgentTurnResult(request.chatRoom, user, listOf(answer), listOf(run)).also { persisted.complete(it) }
        }
        val model = createViewModel(combined = false, reasoningCapabilities = capabilities, testFeatures = features.withToolPluginEnabled(dev.chungjungsoo.gptmobile.data.model.ToolPluginId.VOICE_CHAT, true))
        assertTrue(model.submitVoiceTranscript("These are my dictated words"))
        assertEquals(dev.chungjungsoo.gptmobile.data.voice.VoiceAdmissionStage.CHECKING, model.voiceAdmission.value?.stage)
        assertEquals("These are my dictated words", model.question.text.toString())
        assertTrue(model.attachmentNotice.value.orEmpty().contains("Checking the selected model"))
        check.complete(dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityObservation(profile.uid, profile.model, dev.chungjungsoo.gptmobile.data.context.ReasoningMode.OFF, dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityStatus.SUPPORTED, "configuration", "runtime", System.currentTimeMillis(), null, true, true))
        val accepted = withTimeout(5000) { persisted.await() }
        assertEquals(dev.chungjungsoo.gptmobile.data.voice.VoiceAdmissionStage.ACCEPTED, model.voiceAdmission.value?.stage)
        assertEquals(71, model.voiceAdmission.value?.userMessageId)
        assertEquals(accepted.runs.map { it.runId }, model.voiceAdmission.value?.runIds)
        assertEquals("", model.question.text.toString())
    }

    @Test fun deletedVoiceRoomIsRecoverableAndCannotAdmitSpeech() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        val model = createViewModel(combined = false, roomAvailable = false)
        assertTrue(model.roomLoadError.value.orEmpty().contains("no longer available"))
        assertFalse(model.voiceRoomReady.value)
        assertFalse(model.submitVoiceTranscript("Do not create a replacement chat"))
        coVerify(exactly = 0) { repository.persistAgentTurn(any()) }
    }

    @Test fun voiceCannotAdmitBeforeItsSelectedProfileLoads() = runTest(dispatcher) {
        StartupRecoveryGate.start(backgroundScope) {}
        val delayed = CompletableDeferred<List<PlatformV2>>()
        val model = createViewModel(combined = false, profileLoad = delayed)
        assertTrue(model.platformsInApp.value.isEmpty())
        assertFalse(model.submitVoiceTranscript("Wait for my private profile"))
        coVerify(exactly = 0) { repository.persistAgentTurn(any()) }
        delayed.complete(profiles)
    }

    private fun seedTurn(statuses: List<String>, combinedSettings: ConversationCombinedSettings = ConversationCombinedSettings()) {
        val requested = CombinedPolicyResolver.accept(
            combinedSettings,
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

    private fun createViewModel(chatId: Int = 7, combined: Boolean = true, research: ResearchSessionStore? = null, combinedSettings: ConversationCombinedSettings? = null, toolConnections: ToolConnectionRepository = mockk(relaxed = true), testFeatures: AppFeatureSettings? = null, reasoningCapabilities: dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityRepository? = null, roomAvailable: Boolean = true, profileLoad: CompletableDeferred<List<PlatformV2>>? = null, modelPreparation: dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationRepository? = null): ChatViewModel {
        val room = ChatRoomV2(id = chatId, title = "Saved chat", enabledPlatform = profiles.map { it.uid }, activePlatform = profiles.map { it.uid }, conversationMode = if (combined) ConversationMode.COMBINED else ConversationMode.STANDARD, draftText = "Saved draft")
        every { repository.observeMessageWindow(chatId, any()) } returns messages
        every { repository.observeTurnCount(chatId) } returns flowOf(messages.value.count { it.platformType == null })
        every { repository.observeAgentRuns(chatId) } returns runs
        every { repository.observeProviderWaitDeadlines(chatId) } returns waitDeadlines
        every { repository.observeToolEvents(chatId) } returns flowOf(emptyList())
        coEvery { repository.fetchChatListV2() } returns if (roomAvailable) listOf(room) else emptyList()
        coEvery { repository.fetchArchivedChatListV2() } returns emptyList()
        coEvery { repository.fetchMessagesV2(chatId) } answers { messages.value }
        coEvery { repository.fetchChatPlatformModels(chatId) } returns profiles.associate { it.uid to it.model }
        every { exitWork.enqueue(any(), any()) } answers {
            exitAction = secondArg()
            kotlinx.coroutines.Job()
        }
        val settings = mockk<SettingRepository>(relaxed = true)
        val currentFeatures = testFeatures ?: combinedSettings?.let { features.copy(conversationCombined = features.conversationCombined + (chatId to it)) } ?: features
        coEvery { settings.fetchPlatformV2s() } coAnswers { profileLoad?.await() ?: profiles }
        coEvery { settings.getFeatureSettings() } returns currentFeatures
        coEvery { settings.ensureConversationIdentity(any()) } returns "conversation-7"
        every { settings.observeDebugMode() } returns flowOf(false)
        every { settings.observeFeatureSettings() } returns flowOf(currentFeatures)
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
            toolConnectionRepository = toolConnections,
            localModelRepository = localModels,
            modelCatalogRepository = catalog,
            freeModelToolConsentStore = mockk(relaxed = true),
            conversationReadStateStore = ConversationReadStateStore(completions),
            generationCompletionStore = completions,
            researchSessionStore = research,
            conversationExitWork = exitWork,
            messageGroupingDispatcher = dispatcher,
            reasoningCapabilities = reasoningCapabilities,
            modelPreparationRepository = modelPreparation
        ).also {
            viewModelJobs += requireNotNull(it.viewModelScope.coroutineContext[Job])
            store.put("chat", it)
        }
    }
}
