package dev.chungjungsoo.gptmobile.data.agent

import android.app.Application
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.context.ConfiguredDispatchAuthority
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.data.network.FreeAiRequestLimiter
import dev.chungjungsoo.gptmobile.data.network.FreeProviderPreflight
import dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision
import dev.chungjungsoo.gptmobile.data.network.bindAuthority
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.research.ResearchDeferredStage
import dev.chungjungsoo.gptmobile.data.research.researchHash
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ProviderWaitRecoveryTest {
    private class Fixture(val partial: String = "") {
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, ChatDatabaseV2::class.java).build()
        val dao = database.agentPersistenceDao()
        val original = PlatformV2(uid = "p", name = "Provider", compatibleType = ClientType.CUSTOM, apiUrl = "https://writer.example/v1", model = "model", enabled = true)
        var current = original
        var helpers = emptyList<PlatformV2>()
        var features = AppFeatureSettings()
        var permission = "permission-one"
        val settings = mockk<SettingRepository>()
        val user = MessageV2(id = 1, chatId = 1, content = "The accepted research task", platformType = null)
        val answer = MessageV2(id = 2, chatId = 1, content = partial, platformType = "p", linkedMessageId = 1, currentRunId = "run", ownershipFence = 3)
        val request = AgentRunRequest("run", 1, answer, original, listOf(user), listOf(emptyList()))
        val store = ProviderWaitStore(context, database, settings)
        val codec = Json { encodeDefaults = true }

        init {
            coEvery { settings.fetchPlatformV2s() } answers { listOf(current) + helpers }
            coEvery { settings.getFeatureSettings() } answers { features }
            coEvery { settings.providerPermissionRevision(any()) } answers { permission }
            coEvery { settings.providerActivationRequired(any()) } returns false
        }

        suspend fun initialize() {
            database.chatRoomDao().addChatRoom(ChatRoomV2(id = 1, title = "Waiting", activePlatform = listOf("p")))
            database.messageDao().addMessages(user, answer)
            database.agentRunDao().upsert(AgentRun("run", 1, 1, 2, "p", "CUSTOM", "model", "RUNNING", startedAt = 5, ownershipFence = 3))
        }

        suspend fun decision(): ProviderLimitDecision = ProviderLimitDecision("TEST", "p", "model", 1000L, "quota-scope", "PROVIDER_RATE_LIMITED", true)
            .bindAuthority(ConfiguredDispatchAuthority.capture(settings, original, 1))

        fun row(decision: ProviderLimitDecision, mode: ProviderWaitMode = ProviderWaitMode.INITIAL): ProviderWait {
            val authority = requireNotNull(decision.authority)
            val limited = (listOf(original) + helpers).single { it.uid == decision.profileUid }.copy(model = decision.model)
            val saved = ProviderWaitCheckpoint(
                chatId = 1, platform = original.copy(token = null), registeredRevision = ConfiguredDispatchAuthority.profileRevision(original), policyRevision = ConfiguredDispatchAuthority.policyRevision(features, original.uid, 1), permissionRevision = permission,
                users = listOf(1), replies = listOf(emptyList()), inputDigest = researchHash(codec.encodeToString(listOf(user.copy(activeRevisionIndex = -1)))), tools = null, stage = null, participants = listOf("p"),
                mode = mode, decision = decision, deferredStage = if (mode == ProviderWaitMode.RESEARCH_STAGE) ResearchDeferredStage("1:1", "EXTRACT", "exact-stage-prompt", decision) else null,
                limitPlatform = limited.copy(token = null), limitRegisteredRevision = authority.registeredRevision, limitPolicyRevision = authority.policyRevision, limitPermissionRevision = authority.permissionRevision,
                continuation = if (mode == ProviderWaitMode.TEXT_CONTINUATION) ProviderContinuationCheckpoint("1:1", 1, researchHash(partial)) else null
            )
            return ProviderWait("run", 3, decision.scope, decision.retryAt, codec.encodeToString(saved))
        }
    }

    private fun combinedContext(fixture: Fixture): CombinedExecutionContext {
        val requested = CombinedPolicyResolver.accept(dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings(), listOf("p", "other"), mapOf("p" to "model", "other" to "model"), fixture.user.content, singlePass = true)
        return CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested), "excluded-${java.util.UUID.randomUUID()}", profileUid = "p", chatId = 1, persistBudget = false)
    }

    private fun repository(fixture: Fixture): dev.chungjungsoo.gptmobile.data.repository.ChatRepository = mockk<dev.chungjungsoo.gptmobile.data.repository.ChatRepository>(relaxed = true).also { repository ->
        every { repository.observeAgentRuns(1) } returns flow { emit(fixture.database.agentRunDao().getByChatId(1)) }
        coEvery { repository.isCombinedTurnCancelled(any()) } returns false
        coEvery { repository.finishActiveAgentRun(any(), any(), any(), any()) } coAnswers {
            fixture.database.agentRunDao().finishActive(firstArg(), secondArg(), thirdArg(), arg(3)) == 1
        }
        coEvery { repository.markAgentRunRunning(any(), any()) } coAnswers {
            fixture.database.agentRunDao().markRunning(firstArg(), secondArg()) == 1
        }
        coEvery { repository.commitAgentRun(any(), any(), any(), any(), any()) } coAnswers {
            fixture.dao.finishRunningWithMessage(firstArg(), secondArg(), thirdArg(), arg(3), arg(4))
        }
    }

    private fun coordinator(fixture: Fixture, repository: dev.chungjungsoo.gptmobile.data.repository.ChatRepository, decisions: CombinedCollectionDecisionStore) = AgentRunCoordinator(
        fixture.context,
        repository,
        mockk(relaxed = true),
        fixture.settings,
        mockk(relaxed = true),
        providerWaits = fixture.store,
        collectionDecisions = decisions
    )

    @Test fun initialWaitDeadlineProjectionRequiresItsCurrentWaitingOwner() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.dao.updateRunStatus("run", "QUEUED", null, null, null)
            val row = fixture.row(fixture.decision())
            assertTrue(fixture.dao.deferProvider(row))
            assertNull(fixture.dao.recoveryRun("run")!!.terminalError)
            assertTrue(fixture.dao.recoveryMessage(2)!!.timeline.isEmpty())
            assertEquals(listOf(ProviderWaitDeadline("run", 3, row.retryAt)), fixture.dao.observeProviderWaitDeadlines(1).first())
            assertTrue(fixture.dao.observeProviderWaitDeadlines(2).first().isEmpty())
            fixture.dao.saveProviderWait(row.copy(fence = 4))
            assertTrue(fixture.dao.observeProviderWaitDeadlines(1).first().isEmpty())
            fixture.dao.saveProviderWait(row)
            fixture.dao.updateMessage(fixture.answer.copy(ownershipFence = 4))
            assertTrue(fixture.dao.observeProviderWaitDeadlines(1).first().isEmpty())
            fixture.dao.updateMessage(fixture.answer)
            assertEquals(1, fixture.dao.claimProviderWait("run", 3))
            assertTrue(fixture.dao.observeProviderWaitDeadlines(1).first().isEmpty())
            assertTrue(fixture.dao.deferProvider(row))
            assertEquals(1, fixture.database.agentRunDao().finishActive("run", "CANCELED", 20, null))
            assertTrue(fixture.dao.observeProviderWaitDeadlines(1).first().isEmpty())
        } finally {
            fixture.database.close()
        }
    }

    @Test fun exclusionBetweenClaimAndResumeCancelsTheQueuedWaitWithoutDispatch() = runBlocking {
        val fixture = Fixture()
        val claimed = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        try {
            fixture.initialize()
            val combined = combinedContext(fixture)
            val tools = dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig(combined = combined)
            val row = fixture.row(fixture.decision())
            val checkpoint = fixture.codec.decodeFromString<ProviderWaitCheckpoint>(row.checkpoint).copy(tools = tools)
            assertTrue(fixture.dao.deferRunningProvider(row.copy(checkpoint = fixture.codec.encodeToString(checkpoint))))
            val decisions = CombinedCollectionDecisionStore(fixture.context)
            val repository = repository(fixture)
            val coordinator = coordinator(fixture, repository, decisions)
            val worker = launch {
                val action = fixture.store.readyActions(row.scope).single()
                claimed.complete(Unit)
                resume.await()
                coordinator.resumeProviderWaits(listOf(action))
            }
            withTimeout(5000) { claimed.await() }
            assertEquals("QUEUED", fixture.dao.recoveryRun("run")!!.status)
            coordinator.withChatGate(1) { decisions.excludeProfile(combined.rootBudgetId, "p", persistent = false) }
            resume.complete(Unit)
            withTimeout(5000) { worker.join() }
            assertEquals("CANCELED", fixture.dao.recoveryRun("run")!!.status)
            assertEquals("EXCLUDED_FROM_COMBINED_TURN", fixture.dao.recoveryRun("run")!!.terminalError)
            assertTrue(fixture.store.readyActions(row.scope).isEmpty())
            coVerify(exactly = 0) { repository.markAgentRunRunning(any(), any()) }
            coVerify(exactly = 0) { repository.completeChat(any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { repository.cancelCombinedTurn(any()) }
        } finally {
            resume.complete(Unit)
            fixture.database.close()
        }
    }

    @Test fun exclusionAfterResumeStopsQueuedOrRunningSuccessorsWithoutCancellingTheTurn() = runBlocking {
        for (running in listOf(false, true)) {
            val fixture = Fixture()
            try {
                fixture.initialize()
                fixture.features = fixture.features.copy(backgroundGeneration = false)
                val combined = combinedContext(fixture)
                val tools = dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig(combined = combined)
                val accepted = CombinedAcceptedTurn(context = combined, tools = tools).encode()
                fixture.database.agentRunDao().upsert(fixture.dao.recoveryRun("run")!!.copy(status = "QUEUED", startedAt = null, synthesisSnapshot = accepted))
                val decisions = CombinedCollectionDecisionStore(fixture.context)
                val repository = repository(fixture)
                val coordinator = coordinator(fixture, repository, decisions)
                val started = CompletableDeferred<Unit>()
                var physicalRequests = 0
                coEvery { repository.completeChat(any(), any(), any(), any(), any(), any()) } returns flow {
                    physicalRequests++
                    emit(dev.chungjungsoo.gptmobile.data.dto.ApiState.Success("Retained packet from the accepted request."))
                    started.complete(Unit)
                    awaitCancellation()
                }
                if (running) {
                    coordinator.start(listOf(fixture.request.copy(chatToolConfig = tools)))
                    withTimeout(10000) { started.await() }
                    assertEquals("RUNNING", fixture.dao.recoveryRun("run")!!.status)
                }
                // A stale failure alert may still name the already retired predecessor.
                val predecessor = fixture.dao.recoveryRun("run")!!.copy(runId = "retired-predecessor", status = "INTERRUPTED")
                coordinator.withChatGate(1) {
                    decisions.excludeProfile(combined.rootBudgetId, "p", persistent = false)
                    withTimeout(10000) { coordinator.cancelExcludedProviderWait(predecessor, combined.rootBudgetId) }
                }
                assertEquals("CANCELED", fixture.dao.recoveryRun("run")!!.status)
                assertEquals("EXCLUDED_FROM_COMBINED_TURN", fixture.dao.recoveryRun("run")!!.terminalError)
                assertEquals(0, fixture.database.agentRunDao().markRunning("run", 100))
                assertEquals(if (running) 1 else 0, physicalRequests)
                if (running) assertEquals("Retained packet from the accepted request.", fixture.dao.recoveryMessage(2)!!.content)
                assertTrue(coordinator.activeRuns.value.isEmpty())
                coVerify(exactly = 0) { repository.cancelCombinedTurn(any()) }
            } finally {
                fixture.database.close()
            }
        }
    }

    @Test fun onlyTypedKnownRejectionCanDeferARunningOwnerAndCancellationPreventsResume() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            val decision = fixture.decision()
            assertFalse(fixture.store.deferRunning(fixture.request, decision.copy(knownNotAccepted = false)))
            assertTrue(runCatching { fixture.dao.deferRunningProvider(fixture.row(decision.copy(knownNotAccepted = false))) }.isFailure)
            assertEquals("RUNNING", fixture.dao.recoveryRun("run")!!.status)
            assertTrue(fixture.dao.deferRunningProvider(fixture.row(decision)))
            assertEquals("WAITING_PROVIDER_LIMIT", fixture.dao.recoveryRun("run")!!.status)
            assertEquals(1, fixture.database.agentRunDao().finishActive("run", "CANCELED", 20, null))
            assertTrue(fixture.store.readyActions(decision.scope).isEmpty())
            assertEquals(0, fixture.dao.claimProviderWait("run", 3))
        } finally {
            fixture.database.close()
        }
    }

    @Test fun aCrashAfterInitialClaimKeepsTheNeverStartedOwnerRecoverable() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            val decision = fixture.decision()
            assertTrue(fixture.dao.deferRunningProvider(fixture.row(decision)))
            val ready = fixture.store.readyActions(decision.scope).single()
            assertEquals("run", ready.request.runId)
            assertEquals("QUEUED", fixture.dao.recoveryRun("run")!!.status)
            assertNull(fixture.dao.recoveryRun("run")!!.startedAt)
            assertEquals(0, fixture.database.agentRunDao().interruptActiveRuns(20))
            assertEquals("run", fixture.store.readyActions(decision.scope).single().request.runId)
            fixture.dao.saveProviderWait(fixture.dao.rawProviderWait("run")!!.copy(checkpoint = "malformed"))
            assertTrue(fixture.store.readyActions(decision.scope).isEmpty())
            assertEquals("INTERRUPTED", fixture.dao.recoveryRun("run")!!.status)
            assertEquals("PROVIDER_WAIT_CHECKPOINT_INVALID", fixture.dao.recoveryRun("run")!!.terminalError)
            assertTrue(fixture.dao.providerWaits().isEmpty())
        } finally {
            fixture.database.close()
        }
    }

    @Test fun researchSuccessorPreservesTaskAndPartialAndDuplicateWakeCannotCreateAnotherOwner() = runBlocking {
        val fixture = Fixture("Previously completed research paragraph.")
        try {
            fixture.initialize()
            val decision = fixture.decision()
            assertTrue(fixture.dao.deferRunningProvider(fixture.row(decision, ProviderWaitMode.RESEARCH_STAGE)))
            val action = fixture.store.readyActions(decision.scope).single()
            assertEquals("WAITING_PROVIDER_LIMIT", fixture.dao.recoveryRun("run")!!.status)
            val resumed = fixture.store.resumeResearch(action)!!
            assertEquals(fixture.user, resumed.userMessages.single())
            assertEquals("1:1", resumed.assistantMessage.timeline.single { it.recovery != null }.recovery!!.rootWorkId)
            assertTrue(resumed.assistantMessage.revisions.any { it.content == fixture.partial })
            assertEquals(4L, resumed.assistantMessage.ownershipFence)
            assertEquals("INTERRUPTED", fixture.dao.recoveryRun("run")!!.status)
            assertNull(fixture.store.resumeResearch(action))
            assertEquals(2, fixture.database.agentRunDao().getByChatId(1).size)
            val wait = fixture.dao.rawProviderWait(resumed.runId)!!
            assertEquals(ProviderWaitMode.INITIAL, Json.decodeFromString<ProviderWaitCheckpoint>(wait.checkpoint).mode)
            assertEquals(resumed.assistantMessage.ownershipFence, wait.fence)
            assertEquals(0, fixture.database.agentRunDao().interruptActiveRuns(25))
            assertEquals(resumed.runId, fixture.store.readyActions(decision.scope).single().request.runId)
        } finally {
            fixture.database.close()
        }
    }

    @Test fun revokedAuthorityAndChangedInputStopWaitingBeforeAnyNewOwner() = runBlocking {
        for (change in listOf("permission", "input", "route")) {
            val fixture = Fixture()
            try {
                fixture.initialize()
                val decision = fixture.decision()
                assertTrue(fixture.dao.deferRunningProvider(fixture.row(decision)))
                when (change) {
                    "permission" -> fixture.permission = "permission-revoked"
                    "input" -> fixture.dao.updateMessage(fixture.user.copy(content = "A changed task"))
                    "route" -> fixture.current = fixture.current.copy(apiUrl = "https://different.example/v1")
                }
                assertTrue(fixture.store.readyActions(decision.scope).isEmpty())
                assertEquals("INTERRUPTED", fixture.dao.recoveryRun("run")!!.status)
                assertEquals(1, fixture.database.agentRunDao().getByChatId(1).size)
                assertEquals(if (change == "input") "PROVIDER_WAIT_INPUT_CHANGED" else "PROVIDER_WAIT_REVIEW_REQUIRED", fixture.dao.recoveryRun("run")!!.terminalError)
            } finally {
                fixture.database.close()
            }
        }
    }

    @Test fun settingsChangedAfterRejectionCannotBeCapturedAsANewWaitGrant() = runBlocking {
        for (change in listOf("permission", "profile")) {
            val fixture = Fixture()
            try {
                fixture.initialize()
                val decision = fixture.decision()
                if (change == "permission") fixture.permission = "new-grant" else fixture.current = fixture.current.copy(temperature = 0.25f)
                assertTrue(runCatching { fixture.store.deferRunning(fixture.request, decision) }.isFailure)
                assertEquals("RUNNING", fixture.dao.recoveryRun("run")!!.status)
                assertNull(fixture.dao.rawProviderWait("run"))
            } finally {
                fixture.database.close()
            }
        }
    }

    @Test fun configuredResearchReviewerOutsideChatCanResumeUntilItsAcceptedRoleChanges() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            val reviewer = fixture.original.copy(uid = "reviewer", model = "review-model")
            fixture.helpers = listOf(reviewer)
            fixture.features = fixture.features.copy(researchFeature = fixture.features.researchFeature.copy(reviewerProfileUid = reviewer.uid))
            val primaryGuard = ConfiguredDispatchAuthority.capture(fixture.settings, fixture.original, 1)
            val decision = withContext(primaryGuard) {
                ProviderLimitDecision("TEST", reviewer.uid, reviewer.model, 1000L, "reviewer-quota", "PROVIDER_RATE_LIMITED", true)
                    .bindAuthority(ConfiguredDispatchAuthority.capture(fixture.settings, reviewer, 1))
            }
            assertTrue(fixture.dao.deferRunningProvider(fixture.row(decision, ProviderWaitMode.RESEARCH_STAGE)))
            assertEquals(listOf("p"), fixture.dao.getChatRoom(1)!!.activePlatform)
            val ready = fixture.store.readyActions(decision.scope).single()
            assertEquals("reviewer", ready.saved.decision!!.profileUid)
            assertEquals("WAITING_PROVIDER_LIMIT", fixture.dao.recoveryRun("run")!!.status)
            fixture.features = fixture.features.copy(researchFeature = fixture.features.researchFeature.copy(reviewerProfileUid = null))
            assertTrue(fixture.store.readyActions(decision.scope).isEmpty())
            assertEquals("PROVIDER_WAIT_REVIEW_REQUIRED", fixture.dao.recoveryRun("run")!!.terminalError)
        } finally {
            fixture.database.close()
        }
    }

    @Test fun researchInputChangedAfterWakeValidationCannotCreateAStaleSuccessor() = runBlocking {
        val fixture = Fixture("Retained partial")
        try {
            fixture.initialize()
            val decision = fixture.decision()
            assertTrue(fixture.dao.deferRunningProvider(fixture.row(decision, ProviderWaitMode.RESEARCH_STAGE)))
            val ready = fixture.store.readyActions(decision.scope).single()
            fixture.dao.updateMessage(fixture.user.copy(content = "Edited after wake validation"))
            assertTrue(runCatching { fixture.store.resumeResearch(ready) }.isFailure)
            assertEquals("WAITING_PROVIDER_LIMIT", fixture.dao.recoveryRun("run")!!.status)
            assertEquals(fixture.partial, fixture.dao.recoveryMessage(2)!!.content)
            assertEquals(1, fixture.database.agentRunDao().getByChatId(1).size)
            assertTrue(fixture.store.readyActions(decision.scope).isEmpty())
            assertEquals("PROVIDER_WAIT_INPUT_CHANGED", fixture.dao.recoveryRun("run")!!.terminalError)
        } finally {
            fixture.database.close()
        }
    }

    @Test fun permissionChangedAfterWakeValidationCannotCreateAResearchSuccessor() = runBlocking {
        val fixture = Fixture("Retained partial")
        try {
            fixture.initialize()
            val decision = fixture.decision()
            assertTrue(fixture.dao.deferRunningProvider(fixture.row(decision, ProviderWaitMode.RESEARCH_STAGE)))
            val ready = fixture.store.readyActions(decision.scope).single()
            assertEquals(decision.authority, ready.request.providerWaitAuthority)
            fixture.permission = "permission-revoked-after-wake"
            val rejected = runCatching { fixture.store.resumeResearch(ready) }.exceptionOrNull()
            assertEquals("PROVIDER_WAIT_PERMISSION_CHANGED", rejected?.message)
            assertEquals("WAITING_PROVIDER_LIMIT", fixture.dao.recoveryRun("run")!!.status)
            assertEquals(fixture.partial, fixture.dao.recoveryMessage(2)!!.content)
            assertEquals(1, fixture.database.agentRunDao().getByChatId(1).size)
            assertTrue(fixture.store.readyActions(decision.scope).isEmpty())
            assertEquals("PROVIDER_WAIT_REVIEW_REQUIRED", fixture.dao.recoveryRun("run")!!.terminalError)
        } finally {
            fixture.database.close()
        }
    }

    @Test fun clockSamplingDriftBetweenPreflightAndDeferralKeepsTheDurableWait() = runBlocking {
        val fixture = Fixture()
        mockkObject(FreeAiRequestLimiter.Companion, ProviderLimitResumeWorker.Companion)
        try {
            var wall = System.currentTimeMillis()
            val elapsed = 5_000L
            val limiter = FreeAiRequestLimiter(elapsedMillis = { elapsed }, nowMillis = { wall })
            every { FreeAiRequestLimiter.shared } returns limiter
            every { ProviderLimitResumeWorker.enqueue(any(), any(), any(), any()) } just Runs
            fixture.initialize()
            val provider = FreeAiProvider.KILO
            val profile = fixture.original.copy(compatibleType = ClientType.FREE, apiUrl = provider.apiUrl, model = provider.model)
            fixture.current = profile
            fixture.database.agentRunDao().upsert(fixture.dao.recoveryRun("run")!!.copy(status = "QUEUED", startedAt = null, modelSnapshot = profile.model))
            val request = fixture.request.copy(platform = profile)
            limiter.defer(provider, "120", profile.model)
            val originalEvidence = limiter.savedCooldown(provider, profile.model)
            val callerDeadline = requireNotNull(FreeProviderPreflight.check(profile)?.retryAt)
            wall++ // The next clock sample advances wall time before elapsed time.
            assertEquals(callerDeadline + 1, FreeProviderPreflight.check(profile)?.retryAt)
            assertTrue(fixture.store.defer(request))
            val retained = requireNotNull(fixture.dao.rawProviderWait("run"))
            val saved = Json.decodeFromString<ProviderWaitCheckpoint>(retained.checkpoint)
            assertEquals(callerDeadline + 1, retained.retryAt)
            assertEquals(retained.retryAt, saved.decision!!.retryAt)
            assertEquals(limiter.activeQuotaScopeKey(provider, profile.model), retained.scope)
            assertEquals(originalEvidence, limiter.savedCooldown(provider, profile.model))
            assertEquals("WAITING_PROVIDER_LIMIT", fixture.dao.recoveryRun("run")!!.status)
            assertNull(fixture.dao.recoveryRun("run")!!.startedAt)
            fixture.database.agentRunDao().updateStatus("run", "RUNNING", 5, null, null)
            assertFalse(fixture.store.defer(request))
        } finally {
            unmockkObject(FreeAiRequestLimiter.Companion, ProviderLimitResumeWorker.Companion)
            fixture.database.close()
        }
    }

    @Test fun previewBackedHistoryHasTheSameDigestWhenSavingWakingAndCreatingASuccessor() = runBlocking {
        mockkObject(ProviderLimitResumeWorker.Companion)
        try {
            every { ProviderLimitResumeWorker.enqueue(any(), any(), any(), any()) } just Runs
            for (researchStage in listOf(false, true)) {
                val fixture = Fixture(if (researchStage) "Retained new paragraph." else "")
                try {
                    fixture.initialize()
                    val text = "A complete archived paragraph with dates, quantities and source details.\n\n".repeat(1000)
                    val previousUser = MessageV2(id = 3, chatId = 1, content = "Earlier question", platformType = null)
                    val previous = MessageV2(id = 4, chatId = 1, content = text, platformType = "p", linkedMessageId = 3, currentRunId = "combined-synthesis:history", ownershipFence = 1)
                    fixture.database.messageDao().addMessages(previousUser, previous)
                    val priorRun = AgentRun("combined-synthesis:history", 1, 3, 4, "p", "CUSTOM", "model", "COMPLETED", ownershipFence = 1)
                    fixture.database.agentRunDao().upsert(priorRun)
                    val staged = requireNotNull(CombinedDocuments.stage(fixture.dao, previous, priorRun, "COMPLETED", force = true))
                    val compact = CombinedDocuments.commit(fixture.dao, staged, priorRun, previous)
                    fixture.dao.updateMessage(compact)
                    assertTrue(compact.content.length < text.length)
                    val request = fixture.request.copy(userMessages = listOf(previousUser, fixture.user), assistantMessages = listOf(listOf(compact), listOf(fixture.answer)))
                    val decision = fixture.decision()
                    val stage = if (researchStage) ResearchDeferredStage("1:1", "EXTRACT", "accepted-prompt", decision) else null
                    assertTrue(fixture.store.deferRunning(request, decision, stage))
                    val ready = fixture.store.readyActions(decision.scope).single()
                    assertEquals(text, ready.request.assistantMessages.flatten().single { it.id == 4 }.content)
                    if (researchStage) {
                        val resumed = requireNotNull(fixture.store.resumeResearch(ready))
                        assertEquals(text, resumed.assistantMessages.flatten().single { it.id == 4 }.content)
                        assertTrue(resumed.assistantMessage.revisions.any { it.content == fixture.partial })
                    }
                } finally {
                    fixture.database.close()
                }
            }
        } finally {
            unmockkObject(ProviderLimitResumeWorker.Companion)
        }
    }

    @Test fun siblingStreamingDoesNotInvalidateAQuotaWaitOrBecomeItsPromptHistory() = runBlocking {
        val fixture = Fixture()
        mockkObject(ProviderLimitResumeWorker.Companion)
        try {
            every { ProviderLimitResumeWorker.enqueue(any(), any(), any(), any()) } just Runs
            fixture.initialize()
            val sibling = MessageV2(id = 3, chatId = 1, content = "Still writing", platformType = "other", linkedMessageId = 1, currentRunId = "other-run")
            fixture.database.messageDao().addMessages(sibling)
            val request = fixture.request.copy(assistantMessages = listOf(listOf(fixture.answer, sibling)))
            val decision = fixture.decision()
            assertTrue(fixture.store.deferRunning(request, decision))
            val saved = Json.decodeFromString<ProviderWaitCheckpoint>(fixture.dao.rawProviderWait("run")!!.checkpoint)
            assertEquals(listOf(emptyList<Int>()), saved.replies)
            fixture.dao.updateMessage(sibling.copy(content = "The other provider finished a much longer answer.", isFavorite = true))
            val resumed = fixture.store.readyActions(decision.scope).single()
            assertEquals(listOf(fixture.user), resumed.request.userMessages)
            assertEquals(listOf(fixture.answer), resumed.request.assistantMessages.flatten())
            assertEquals("QUEUED", fixture.dao.recoveryRun("run")!!.status)
        } finally {
            unmockkObject(ProviderLimitResumeWorker.Companion)
            fixture.database.close()
        }
    }

    @Test fun editingActualPriorAnswerStillInvalidatesAQuotaWait() = runBlocking {
        val fixture = Fixture()
        mockkObject(ProviderLimitResumeWorker.Companion)
        try {
            every { ProviderLimitResumeWorker.enqueue(any(), any(), any(), any()) } just Runs
            fixture.initialize()
            val priorUser = MessageV2(id = 3, chatId = 1, content = "Earlier question", platformType = null)
            val priorAnswer = MessageV2(id = 4, chatId = 1, content = "Earlier answer", platformType = "p", linkedMessageId = 3)
            fixture.database.messageDao().addMessages(priorUser, priorAnswer)
            val request = fixture.request.copy(userMessages = listOf(priorUser, fixture.user), assistantMessages = listOf(listOf(priorAnswer), listOf(fixture.answer)))
            val decision = fixture.decision()
            assertTrue(fixture.store.deferRunning(request, decision))
            fixture.dao.updateMessage(priorAnswer.copy(content = "Edited history changes the request."))
            assertTrue(fixture.store.readyActions(decision.scope).isEmpty())
            assertEquals("PROVIDER_WAIT_INPUT_CHANGED", fixture.dao.recoveryRun("run")!!.terminalError)
        } finally {
            unmockkObject(ProviderLimitResumeWorker.Companion)
            fixture.database.close()
        }
    }

    @Test fun aKnownRejectedTextContinuationPreservesItsPrefixAcrossRestartAndDuplicateWake() = runBlocking {
        val fixture = Fixture("First complete paragraph.\n\nSecond retained paragraph.")
        mockkObject(ProviderLimitResumeWorker.Companion)
        try {
            every { ProviderLimitResumeWorker.enqueue(any(), any(), any(), any()) } just Runs
            fixture.initialize()
            val decision = fixture.decision().copy(continuationPrefixDigest = researchHash(fixture.partial))
            assertTrue(fixture.store.deferRunning(fixture.request, decision))
            val waiting = fixture.dao.rawProviderWait("run")!!
            assertEquals(ProviderWaitMode.TEXT_CONTINUATION, Json.decodeFromString<ProviderWaitCheckpoint>(waiting.checkpoint).mode)
            val ready = fixture.store.readyActions(decision.scope).single()
            assertEquals("WAITING_PROVIDER_LIMIT", fixture.dao.recoveryRun("run")!!.status)
            val resumed = fixture.store.resumeTextContinuation(ready)!!
            val descriptor = resumed.assistantMessage.timeline.single { it.recovery != null }.recovery!!
            assertEquals(TaskRecoveryAction.CONTINUE_RUN, descriptor.action)
            assertEquals("1:1", descriptor.rootWorkId)
            assertEquals(researchHash(fixture.partial), descriptor.continuationPrefixDigest)
            assertEquals(fixture.partial, resumed.assistantMessage.revisions.single { it.runId == "run" }.content)
            assertEquals(4L, resumed.assistantMessage.ownershipFence)
            assertEquals("INTERRUPTED", fixture.dao.recoveryRun("run")!!.status)
            assertNull(fixture.store.resumeTextContinuation(ready))
            assertEquals(2, fixture.database.agentRunDao().getByChatId(1).size)
            // Simulate process loss after inserting the successor, before dispatch.
            assertEquals(0, fixture.database.agentRunDao().interruptActiveRuns(25))
            val restarted = ProviderWaitStore(fixture.context, fixture.database, fixture.settings).readyActions(decision.scope).single()
            assertEquals(ProviderWaitMode.INITIAL, restarted.saved.mode)
            assertEquals(resumed.runId, restarted.request.runId)
            assertEquals(descriptor, restarted.request.assistantMessage.timeline.single { it.recovery != null }.recovery)
            assertEquals(fixture.partial, restarted.request.assistantMessage.revisions.single { it.runId == "run" }.content)
        } finally {
            unmockkObject(ProviderLimitResumeWorker.Companion)
            fixture.database.close()
        }
    }

    @Test fun aPartialResponseCannotCreateAnAutomaticWaitWithoutItsExactTypedPrefix() = runBlocking {
        val fixture = Fixture("Own saved answer.")
        try {
            fixture.initialize()
            val decision = fixture.decision()
            assertFalse(fixture.store.deferRunning(fixture.request, decision))
            assertFalse(fixture.store.deferRunning(fixture.request, decision.copy(continuationPrefixDigest = researchHash("A sibling profile's answer."))))
            assertFalse(fixture.store.deferRunning(fixture.request, decision.copy(knownNotAccepted = false, continuationPrefixDigest = researchHash(fixture.partial))))
            assertFalse(fixture.store.deferRunning(fixture.request, decision.copy(model = "another-model", continuationPrefixDigest = researchHash(fixture.partial))))
            assertEquals("RUNNING", fixture.dao.recoveryRun("run")!!.status)
            assertNull(fixture.dao.rawProviderWait("run"))
        } finally {
            fixture.database.close()
        }
    }

    @Test fun anEditedContinuationPrefixCannotBeResumedBeforeOrAfterWakeValidation() = runBlocking {
        for (editAfterWake in listOf(false, true)) {
            val fixture = Fixture("Retained prefix before provider rejection.")
            try {
                fixture.initialize()
                val decision = fixture.decision().copy(continuationPrefixDigest = researchHash(fixture.partial))
                assertTrue(fixture.dao.deferRunningProvider(fixture.row(decision, ProviderWaitMode.TEXT_CONTINUATION)))
                val ready = if (editAfterWake) fixture.store.readyActions(decision.scope).single() else null
                fixture.dao.updateMessage(fixture.answer.copy(content = "An edited answer."))
                if (ready != null) assertTrue(runCatching { fixture.store.resumeTextContinuation(ready) }.isFailure)
                assertTrue(fixture.store.readyActions(decision.scope).isEmpty())
                assertEquals("PROVIDER_WAIT_PREFIX_CHANGED", fixture.dao.recoveryRun("run")!!.terminalError)
                assertEquals(1, fixture.database.agentRunDao().getByChatId(1).size)
                assertEquals("An edited answer.", fixture.dao.recoveryMessage(2)!!.content)
            } finally {
                fixture.database.close()
            }
        }
    }
}
