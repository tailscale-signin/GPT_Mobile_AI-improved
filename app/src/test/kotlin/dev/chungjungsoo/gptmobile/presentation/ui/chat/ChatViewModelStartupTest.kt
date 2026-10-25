package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
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
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnResult
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveRunId
import dev.chungjungsoo.gptmobile.data.database.entity.snapshotLatestAssistantRevision
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
    private val repository = mockk<ChatRepository>(relaxed = true)
    private val coordinator = mockk<AgentRunCoordinator>(relaxed = true)
    private val messages = MutableStateFlow<List<MessageV2>>(emptyList())
    private val runs = MutableStateFlow<List<AgentRun>>(emptyList())
    private val waitDeadlines = MutableStateFlow<List<ProviderWaitDeadline>>(emptyList())
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
        store.clear()
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
            store.clear()
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

    private fun createViewModel(chatId: Int = 7, combined: Boolean = true, research: ResearchSessionStore? = null, combinedSettings: ConversationCombinedSettings? = null): ChatViewModel {
        val room = ChatRoomV2(id = chatId, title = "Saved chat", enabledPlatform = profiles.map { it.uid }, activePlatform = profiles.map { it.uid }, conversationMode = if (combined) ConversationMode.COMBINED else ConversationMode.STANDARD, draftText = "Saved draft")
        every { repository.observeMessageWindow(chatId, any()) } returns messages
        every { repository.observeTurnCount(chatId) } returns flowOf(messages.value.count { it.platformType == null })
        every { repository.observeAgentRuns(chatId) } returns runs
        every { repository.observeProviderWaitDeadlines(chatId) } returns waitDeadlines
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
        val currentFeatures = combinedSettings?.let { features.copy(conversationCombined = features.conversationCombined + (chatId to it)) } ?: features
        coEvery { settings.fetchPlatformV2s() } returns profiles
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
