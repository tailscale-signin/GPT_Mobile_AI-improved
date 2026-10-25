package dev.chungjungsoo.gptmobile.data.agent

import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkConfigKey
import dev.chungjungsoo.gptmobile.data.context.ConfiguredDispatchAuthority
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunDraft
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentRetryRequest
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision
import dev.chungjungsoo.gptmobile.data.network.bindAuthority
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.research.researchHash
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CombinedAppendResumeTest {
    private val writer = PlatformV2(uid = "p", name = "Primary", model = "model", compatibleType = ClientType.CUSTOM, enabled = true, maxTokens = 16384, apiUrl = "https://writer.example/v1")
    private val user = MessageV2(id = 2, chatId = 1, platformType = null, content = "Explain history in 1000 words.")
    private val decision = ProviderLimitDecision("TEST", "p", "model", 1000L, "quota-scope", "PROVIDER_RATE_LIMITED", true)
    private val priorId = "combined-synthesis:prior"
    private val first = """{"type":"passage","seq":1,"id":"P1","text":"Original committed paragraph.","claims":[],"contributors":["C1"],"evidence":[]}"""

    private fun frozen(append: Boolean): CombinedInputSnapshot {
        val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(continueLongAnswers = append), listOf("p"), mapOf("p" to "model"), user.content, singlePass = true)
        val context = CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested), "accepted-root", CombinedRunRole.SYNTHESIS, "p", chatId = 1)
        val prompt = "All original frozen contributions."
        val plan = CombinedWriterRequestPlan.freeze(writer, user.content, prompt, combinedWriterSystemPrompt(null, "PASSAGES_V3", user.content, append), emptyList(), "PASSAGES_V3", 8192, appendContinuations = append)
        return CombinedInputSnapshot(
            version = 6, userMessage = user, history = emptyList(), replies = emptyList(), sources = listOf(CombinedModelResponse("p", "Primary", content = "Original committed paragraph.")),
            prompt = prompt, profileUid = "p", model = "model", systemPrompt = "", configKey = benchmarkConfigKey(writer), deliveryProtocol = "PASSAGES_V3", combined = context,
            acceptedTools = ChatMcpToolConfig(allToolsDisabled = true, combined = context), answerContract = CombinedAnswerPolicy.from(user.content), revisionId = "revision", snapshotId = "snapshot",
            dispatchIdentity = "one-frozen-writer", writerRequestPlan = plan
        ).seal()
    }

    private suspend fun database(snapshot: CombinedInputSnapshot, waiting: Boolean = true): ChatDatabaseV2 {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        db.chatRoomDao().addChatRoom(ChatRoomV2(id = 1, title = "Combined", combinedPrimaryUid = "p", activePlatform = listOf("p")))
        db.messageDao().addMessages(user, MessageV2(id = 3, chatId = 1, platformType = "p", linkedMessageId = 2, content = "", currentRunId = priorId, ownershipFence = 4, combinedSources = snapshot.sources))
        val raw = snapshot.encode()
        db.agentRunDao().upsert(AgentRun(priorId, 1, 2, 3, "p", "CUSTOM", "model", "RUNNING", ownershipFence = 4, synthesisKey = researchHash(raw), synthesisSnapshot = raw))
        if (!waiting) return db
        val dao = db.agentPersistenceDao()
        val records = CombinedRecordStore.open(dao, priorId, snapshot, "revision", 10000, false)
        records.protocol.accept(first)
        records.protocol.markAttemptFinished("OUTPUT_LIMIT")
        records.protocol.continuationContext()
        records.protocol.markAttemptStarted()
        records.protocol.markAttemptFinished("KNOWN_PROVIDER_REJECTION", decision.retryAt)
        records.save(records.protocol.checkpoint())
        val wait = ProviderWaitCheckpoint(
            chatId = 1, platform = writer, registeredRevision = "profile", policyRevision = "policy", permissionRevision = "permission", users = listOf(2), replies = listOf(emptyList()), inputDigest = "input",
            tools = snapshot.acceptedTools, stage = snapshot.bindAcceptedStage(), participants = listOf("p"), mode = ProviderWaitMode.COMBINED_APPEND, decision = decision
        )
        assertTrue(dao.deferRunningProvider(ProviderWait(priorId, 4, decision.scope, decision.retryAt, Json.encodeToString(wait))))
        return db
    }

    @Test fun quotaResumeAtomicallyKeepsTheRootCursorAndSingleAnswerSlotAndDeduplicates() = runBlocking {
        val snapshot = frozen(true)
        val db = database(snapshot)
        try {
            val dao = db.agentPersistenceDao()
            val prior = dao.recoveryRun(priorId)!!
            val owner = dao.recoveryMessage(3)!!
            val checkpoint = dao.combinedDeliveryCheckpoint(priorId, "revision")!!
            assertEquals(snapshot, CombinedAppendResume.validate(prior, owner, checkpoint, decision))
            val resumed = CombinedRecoverySnapshot.prepare(prior, owner, "p", writer, dao.answerSegments(priorId), providerWait = true)
            assertEquals(snapshot.inputDigest, resumed.inputDigest)
            assertEquals(snapshot.combined!!.rootBudgetId, resumed.combined!!.rootBudgetId)
            val raw = resumed.encode()
            val requests = listOf("a", "b").map { suffix ->
                PersistAgentRetryRequest(user, owner, AgentRunDraft("combined-synthesis:$suffix", "p", "CUSTOM", "model", synthesisKey = researchHash(raw), synthesisSnapshot = raw), resumeCombinedFrom = priorId, automaticAppendDecision = decision)
            }
            val results = requests.map { request -> async { dao.persistAgentRetry(request) } }.awaitAll()
            assertEquals(1, results.map { it.run.runId }.distinct().size)
            val next = results.first()
            assertEquals(3, next.assistantMessage.id)
            assertEquals(5L, next.assistantMessage.ownershipFence)
            assertEquals("INTERRUPTED", dao.recoveryRun(priorId)!!.status)
            assertEquals(2, db.agentRunDao().getByChatId(1).size)
            val ready = Json.decodeFromString<ProviderWaitCheckpoint>(dao.rawProviderWait(next.run.runId)!!.checkpoint)
            assertEquals(ProviderWaitMode.INITIAL, ready.mode)
            db.agentRunDao().markRunning(next.run.runId, 10)
            val recovered = CombinedRecordStore.open(dao, next.run.runId, resumed, resumed.liveRevisionId(next.run.runId), 10000, true)
            assertEquals(listOf("P1"), recovered.protocol.checkpoint().records.map { it.id })
            assertEquals("KNOWN_PROVIDER_REJECTION", recovered.protocol.checkpoint().lastAttemptOutcome)
            assertEquals(snapshot.sources, resumed.sources)
            assertFalse(dao.checkpointRunningMessage(owner.copy(content = "Late old writer")))
        } finally {
            db.close()
        }
    }

    @Test fun changedQuotaDecisionCannotRetireTheWaitingOwner() = runBlocking {
        val snapshot = frozen(true)
        val db = database(snapshot)
        try {
            val dao = db.agentPersistenceDao()
            val owner = dao.recoveryMessage(3)!!
            val resumed = snapshot.copy(resumeFromRunId = priorId, deliveryResumeFromRevisionId = "revision")
            val raw = resumed.encode()
            val bad = PersistAgentRetryRequest(user, owner, AgentRunDraft("combined-synthesis:bad", "p", "CUSTOM", "model", synthesisKey = researchHash(raw), synthesisSnapshot = raw), resumeCombinedFrom = priorId, automaticAppendDecision = decision.copy(scope = "different-quota"))
            assertTrue(runCatching { dao.persistAgentRetry(bad) }.isFailure)
            assertEquals("WAITING_PROVIDER_LIMIT", dao.recoveryRun(priorId)!!.status)
            assertEquals(priorId, dao.recoveryMessage(3)!!.currentRunId)
            assertEquals(1, db.agentRunDao().getByChatId(1).size)
            val row = dao.combinedDeliveryCheckpoint(priorId, "revision")!!
            assertTrue(runCatching { CombinedAppendResume.validate(dao.recoveryRun(priorId)!!, owner, row, decision.copy(knownNotAccepted = false)) }.isFailure)
            assertTrue(runCatching { CombinedAppendResume.validate(dao.recoveryRun(priorId)!!, owner, row, decision, now = 999) }.isFailure)
        } finally {
            db.close()
        }
    }

    @Test fun strictSavedPolicyCannotBeReopenedByAnAppendWait() = runBlocking {
        val db = database(frozen(false))
        try {
            val dao = db.agentPersistenceDao()
            assertTrue(runCatching { CombinedAppendResume.validate(dao.recoveryRun(priorId)!!, dao.recoveryMessage(3)!!, dao.combinedDeliveryCheckpoint(priorId, "revision")!!, decision) }.isFailure)
            assertEquals("WAITING_PROVIDER_LIMIT", dao.recoveryRun(priorId)!!.status)
            assertEquals(1, db.agentRunDao().getByChatId(1).size)
        } finally {
            db.close()
        }
    }

    @Test fun localPreflightBeforeDeliveryCreatesADurablePristineCursorAndWaits() = runBlocking {
        val snapshot = frozen(true)
        val db = database(snapshot, waiting = false)
        mockkObject(ProviderLimitResumeWorker.Companion)
        try {
            every { ProviderLimitResumeWorker.enqueue(any(), any(), any(), any()) } just Runs
            val settings = mockk<SettingRepository>()
            coEvery { settings.fetchPlatformV2s() } returns listOf(writer)
            coEvery { settings.getFeatureSettings() } returns AppFeatureSettings()
            coEvery { settings.providerPermissionRevision(any()) } returns "permission"
            coEvery { settings.providerActivationRequired(any()) } returns false
            val local = decision.copy(category = "LOCAL_COOLDOWN_ACTIVE").bindAuthority(ConfiguredDispatchAuthority.capture(settings, writer, 1))
            val dao = db.agentPersistenceDao()
            val owner = requireNotNull(dao.recoveryMessage(3))
            val request = AgentRunRequest(priorId, 1, owner, writer, listOf(user), listOf(listOf(owner)), snapshot.acceptedTools, snapshot.bindAcceptedStage())
            val store = ProviderWaitStore(RuntimeEnvironment.getApplication(), db, settings)
            assertTrue(store.deferRunning(request, local))
            val checkpoint = requireNotNull(dao.combinedDeliveryCheckpoint(priorId, "revision"))
            val state = Json.decodeFromString<CombinedRecordCheckpoint>(checkpoint.stateJson)
            assertTrue(state.records.isEmpty())
            assertEquals("KNOWN_PROVIDER_REJECTION", state.lastAttemptOutcome)
            assertEquals(local.retryAt, state.retryAtMillis)
            assertEquals("WAITING_PROVIDER_LIMIT", dao.recoveryRun(priorId)!!.status)
            assertEquals(snapshot, CombinedAppendResume.validate(dao.recoveryRun(priorId)!!, dao.recoveryMessage(3)!!, checkpoint, local))
            assertEquals(snapshot.sources, Json.decodeFromString<CombinedInputSnapshot>(checkpoint.snapshotJson).sources)
        } finally {
            unmockkObject(ProviderLimitResumeWorker.Companion)
            db.close()
        }
    }

    @Test fun localPreflightCannotOverwriteAnUnknownOrInflightCursorOrEnableStrictAppend() = runBlocking {
        for (previousOutcome in listOf("IN_FLIGHT", "UNKNOWN", "STRICT")) {
            val snapshot = frozen(previousOutcome != "STRICT")
            val db = database(snapshot, waiting = false)
            try {
                val dao = db.agentPersistenceDao()
                val records = CombinedRecordStore.open(dao, priorId, snapshot, "revision", 10000, false)
                records.protocol.accept(first)
                if (previousOutcome == "IN_FLIGHT") records.protocol.markAttemptStarted() else records.protocol.markAttemptFinished("UNKNOWN")
                records.save(records.protocol.checkpoint())
                val before = requireNotNull(dao.combinedDeliveryCheckpoint(priorId, "revision"))
                val local = decision.copy(category = "LOCAL_COOLDOWN_ACTIVE")
                assertEquals(null, CombinedAppendPreflight.save(dao, dao.recoveryRun(priorId)!!, snapshot, local, 10000))
                assertEquals(before.stateJson, dao.combinedDeliveryCheckpoint(priorId, "revision")!!.stateJson)
                assertEquals("RUNNING", dao.recoveryRun(priorId)!!.status)
                assertEquals(null, dao.rawProviderWait(priorId))
            } finally {
                db.close()
            }
        }
    }
}
