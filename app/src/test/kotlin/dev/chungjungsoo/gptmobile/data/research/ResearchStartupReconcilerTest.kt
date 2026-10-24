package dev.chungjungsoo.gptmobile.data.research

import android.content.ContextWrapper
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.research.delivery.ResearchDeliveryReceipt
import dev.chungjungsoo.gptmobile.data.research.delivery.ResearchReportValidity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ResearchStartupReconcilerTest {
    @get:Rule val directory = TemporaryFolder()
    private lateinit var context: ContextWrapper
    private lateinit var db: ChatDatabaseV2
    private lateinit var store: ResearchSessionStore
    private val root = "1:10"
    private val task = "Compare the captured evidence"

    @Before fun setup() = runBlocking {
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = directory.root
            override fun getNoBackupFilesDir(): File = directory.root
        }
        db = Room.inMemoryDatabaseBuilder(context, ChatDatabaseV2::class.java).build()
        db.agentPersistenceDao().insertChatRoom(ChatRoomV2(id = 1, title = "Research recovery"))
        db.agentPersistenceDao().insertMessage(MessageV2(id = 10, chatId = 1, content = task, platformType = null))
        store = ResearchSessionStore(context)
    }

    @After fun cleanup() {
        db.close()
    }

    private suspend fun accept(profiles: List<String> = listOf("a")): ResearchAttempt {
        store.acceptTask(
            1, root, 10, task, profiles, profiles.first(), "settings", "authority", true,
            acceptedPolicyJson = "{\"sourceGoal\":90,\"contentScope\":\"WEB_ONLY\"}", sourceScope = "WEB_ONLY", targetSources = 90,
            requestedModels = profiles.associateWith { "model" }
        )
        return requireNotNull(store.execution(root, true)).attempt
    }

    private suspend fun run(
        id: String = "a-run",
        profile: String = "a",
        messageId: Int = 20,
        status: String = "FAILED",
        body: String = "",
        receipt: Boolean = false,
        incompatible: Boolean = false
    ) {
        val timeline = buildList {
            if (receipt) {
                add(
                    AssistantTimelineItem(
                        AssistantTimelineItemType.NOTICE,
                        researchDeliveryReceipt = ResearchDeliveryReceipt(ResearchReportValidity.ACCEPTED, true, "packet", researchHash(body))
                    )
                )
            }
            if (incompatible) add(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, profileFailure = ProfileFailureRecord("INCOMPATIBLE_CONFIGURATION", false)))
        }
        db.agentPersistenceDao().insertMessage(MessageV2(id = messageId, chatId = 1, linkedMessageId = 10, platformType = profile, currentRunId = id, content = body, timeline = timeline))
        db.agentRunDao().upsert(AgentRun(id, 1, 10, messageId, profile, "CUSTOM", "model", status, terminalError = "fixture reason".takeIf { status == "FAILED" }))
        val attempt = requireNotNull(store.execution(root, true)).attempt
        store.updateExecution(root, attempt, ResearchAttemptState.ACCEPTED, true, runId = id, profileUid = profile)
    }

    private suspend fun evidence(attempt: ResearchAttempt) {
        val journal = store.journal("a-run", 1, true, root, ownerScope = "a", expectedAttempt = attempt)
        journal.save(ResearchSnapshot(task, phase = "Analyzing", sources = listOf(ResearchSource("S1", "https://example.org/source", "Source", "Read", "The exact supporting passage remains captured.", canonicalUrl = "https://example.org/source"))))
    }

    private suspend fun restart(): ResearchExecutionRecord {
        val restarted = ResearchSessionStore(context)
        ResearchStartupReconciler(restarted, db.agentRunDao(), db.agentPersistenceDao()).reconcile()
        return requireNotNull(restarted.execution(root, true))
    }

    @Test fun processLossAfterDispatchRetainsUnknownOutcomeAndNeverCreatesAnotherRun() = runBlocking {
        val attempt = accept()
        run(status = "RUNNING")
        store.updateExecution(root, attempt, ResearchAttemptState.SEARCHING, true, runId = "a-run", profileUid = "a", dispatched = true)
        db.agentRunDao().interruptActiveRuns(100)
        val first = restart()
        assertEquals(ResearchAttemptState.INTERRUPTED, first.attempt.state)
        assertEquals(ResearchNextAction.RECONCILE_REQUESTS, first.attempt.nextAction)
        assertEquals(attempt.attemptId, first.attempt.attemptId)
        assertEquals(first, restart())
        assertEquals(1, db.agentRunDao().getByChatId(1).size)
        assertTrue(runCatching { store.resumeExecution(root, "retry", true) }.isFailure)
    }

    @Test fun committedDeliveryRepairsTheFileGapOnlyWhenItsEvidenceAndRevisionExist() = runBlocking {
        val attempt = accept()
        run(status = "COMPLETED", body = "Supported finding [S1].", receipt = true)
        evidence(attempt)
        val restored = restart()
        assertEquals(ResearchAttemptState.COMPLETED, restored.attempt.state)
        assertEquals(researchHash("Supported finding [S1]."), restored.attempt.checkpointId)
        assertEquals(1, restored.attempt.readableSources)
        assertEquals(restored, restart())
    }

    @Test fun successfulProviderTextWithoutCapturedEvidenceDoesNotCompleteResearch() = runBlocking {
        accept()
        run(status = "COMPLETED", body = "Unsubstantiated finding.", receipt = true)
        val restored = restart()
        assertEquals(ResearchAttemptState.FAILED_NO_EVIDENCE, restored.attempt.state)
        assertEquals(0, restored.attempt.readableSources)
        assertEquals(ResearchNextAction.RETRY_ACQUISITION, restored.attempt.nextAction)
    }

    @Test fun failedWriterPreservesEvidenceForDeliveryOnlyRetry() = runBlocking {
        val attempt = accept()
        run()
        evidence(attempt)
        val restored = restart()
        assertEquals(ResearchAttemptState.FAILED_DELIVERY, restored.attempt.state)
        assertEquals(ResearchNextAction.RETRY_DELIVERY, restored.attempt.nextAction)
        assertEquals(1, restored.attempt.readableSources)
        assertEquals(1, store.exportTurn(1, setOf(root)).flatMap { it.snapshot.sources }.count { it.readable })
    }

    @Test fun aNewUndispatchedGenerationCannotInheritThePreviousSuccessfulAnswer() = runBlocking {
        val attempt = accept()
        run(status = "COMPLETED", body = "Supported finding [S1].", receipt = true)
        evidence(attempt)
        assertEquals(ResearchAttemptState.COMPLETED, restart().attempt.state)
        store.resumeExecution(root, "new-attempt", true)
        val restored = restart()
        assertEquals(1L, restored.attempt.generation)
        assertEquals(ResearchAttemptState.INTERRUPTED, restored.attempt.state)
        assertEquals(ResearchNextAction.EXPLICIT_RESUME, restored.attempt.nextAction)
        assertTrue(restored.attempt.dispatchedRunIds.isEmpty())
    }

    @Test fun oneFailedSiblingCannotTerminalizeAnotherLiveOwner() = runBlocking {
        accept(listOf("a", "b"))
        run()
        run("b-run", "b", 21, "RUNNING")
        val retained = restart()
        assertFalse(retained.attempt.state.terminal)
        assertEquals("RUNNING", db.agentRunDao().getById("b-run")?.status)
    }

    @Test fun allIncompatibleProfilesRecoverOneConfigurationStateWithNoDispatch() = runBlocking {
        accept(listOf("a", "b"))
        run(incompatible = true)
        run("b-run", "b", 21, incompatible = true)
        val restored = restart()
        assertEquals(ResearchAttemptState.BLOCKED_CONFIGURATION, restored.attempt.state)
        assertEquals("ALL_SELECTED_PROFILES_INCOMPATIBLE", restored.attempt.blockedReason)
        assertTrue(restored.attempt.dispatchedRunIds.isEmpty())
    }

    @Test fun allIncompatibleProfilesSettleImmediatelyWithoutRunningOrCallingAProvider() = runBlocking {
        accept(listOf("a", "b"))
        run(status = "QUEUED")
        run("b-run", "b", 21, "QUEUED")
        val profiles = listOf("a", "b").map { uid ->
            dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2(
                uid = uid,
                name = uid,
                compatibleType = dev.chungjungsoo.gptmobile.data.model.ClientType.FREE,
                apiUrl = dev.chungjungsoo.gptmobile.data.model.FreeAiProvider.HUGGING_FACE.apiUrl,
                model = "qwen3-235b-a22b"
            )
        }
        val requested = dev.chungjungsoo.gptmobile.data.agent.CombinedPolicyResolver.accept(
            dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings(),
            listOf("a", "b"),
            profiles.associate { it.uid to it.model },
            task,
            acceptedResearch = dev.chungjungsoo.gptmobile.data.agent.CombinedResearchRequirement(50, 90, "WEB_ONLY")
        )
        val combined = dev.chungjungsoo.gptmobile.data.agent.CombinedExecutionContext(requested, dev.chungjungsoo.gptmobile.data.agent.CombinedPolicyResolver.resolve(requested), root)
        val repository = mockk<dev.chungjungsoo.gptmobile.data.repository.ChatRepository>(relaxed = true)
        every { repository.observeAgentRuns(1) } returns db.agentRunDao().observeByChatId(1)
        coEvery { repository.researchPersistenceAllowed(1) } returns true
        coEvery { repository.finishQueuedAgentRun(any(), any(), any(), any()) } coAnswers {
            db.agentPersistenceDao().finishQueuedWithFailure(firstArg(), secondArg(), thirdArg(), arg(3))
        }
        val settings = mockk<dev.chungjungsoo.gptmobile.data.repository.SettingRepository>(relaxed = true)
        coEvery { settings.getFeatureSettings() } returns dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings(backgroundGeneration = false)
        coEvery { settings.fetchPlatformV2s() } returns profiles
        val coordinator = dev.chungjungsoo.gptmobile.data.agent.AgentRunCoordinator(context, repository, mockk(relaxed = true), settings, mockk(relaxed = true), researchSessions = store)
        val original = requireNotNull(db.agentPersistenceDao().recoveryMessage(10))
        val answers = listOf(requireNotNull(db.agentPersistenceDao().recoveryMessage(20)), requireNotNull(db.agentPersistenceDao().recoveryMessage(21)))
        coordinator.start(
            profiles.mapIndexed { index, profile ->
                dev.chungjungsoo.gptmobile.data.agent.AgentRunRequest(
                    "${profile.uid}-run",
                    1,
                    answers[index],
                    profile,
                    listOf(original),
                    listOf(answers),
                    dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig(combined = combined.forRole(dev.chungjungsoo.gptmobile.data.agent.CombinedRunRole.CONTRIBUTOR, profile.uid))
                )
            }
        )
        withTimeout(10_000) {
            store.executionState.first { it[root]?.attempt?.state == ResearchAttemptState.BLOCKED_CONFIGURATION }
            coordinator.activeRuns.first { it.isEmpty() }
        }
        coVerify(exactly = 0) { repository.markAgentRunRunning(any(), any()) }
        coVerify(exactly = 0) { repository.completeChat(any(), any(), any(), any(), any(), any()) }
        val persisted = db.agentRunDao().getByChatId(1)
        assertTrue(persisted.all { it.status == "FAILED" && it.startedAt == null })
        for (row in persisted) {
            val message = requireNotNull(db.agentPersistenceDao().recoveryMessage(row.assistantMessageId))
            val failure = requireNotNull(message.timeline.last().profileFailure)
            assertEquals("", message.content)
            assertEquals(false, failure.dispatched)
            assertEquals(dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance.NO, failure.remoteAcceptance)
            assertEquals("INCOMPATIBLE_CONFIGURATION", failure.category)
        }
    }
}
