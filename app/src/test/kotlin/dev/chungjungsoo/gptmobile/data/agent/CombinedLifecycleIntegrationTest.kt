package dev.chungjungsoo.gptmobile.data.agent

import android.content.ContextWrapper
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.accounting.InvocationLedger
import dev.chungjungsoo.gptmobile.data.context.ContextBuilder
import dev.chungjungsoo.gptmobile.data.context.canonicalCombinedAssistant
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunDraft
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentRetryRequest
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnRequest
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnResult
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionUsage
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Choice
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Delta
import dev.chungjungsoo.gptmobile.data.knowledge.MemoryScope
import dev.chungjungsoo.gptmobile.data.localruntime.FakeLocalRuntime
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import dev.chungjungsoo.gptmobile.data.network.AnthropicAPI
import dev.chungjungsoo.gptmobile.data.network.GoogleAPI
import dev.chungjungsoo.gptmobile.data.network.GroqAPI
import dev.chungjungsoo.gptmobile.data.network.ModelRequestPreparation
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.OpenAIAPI
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.rag.FactRecall
import dev.chungjungsoo.gptmobile.data.rag.FactVaultRepository
import dev.chungjungsoo.gptmobile.data.rag.FactVaultSnapshot
import dev.chungjungsoo.gptmobile.data.repository.AttachmentUploadCoordinator
import dev.chungjungsoo.gptmobile.data.repository.ChatRepositoryImpl
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolEventRecorder
import dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore
import dev.chungjungsoo.gptmobile.data.research.researchHash
import dev.chungjungsoo.gptmobile.util.ApiStateFlowOutcome
import dev.chungjungsoo.gptmobile.util.collectApiStateUpdates
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises Room ownership and task identity with the same ledger and budget admission as production. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CombinedLifecycleIntegrationTest {
    @get:Rule val files = TemporaryFolder()
    private lateinit var db: ChatDatabaseV2
    private lateinit var settings: SettingRepository
    private lateinit var api: OpenAIAPI
    private lateinit var context: ContextWrapper
    private lateinit var budgets: CombinedTurnBudgetStore
    private lateinit var sessions: ResearchSessionStore
    private lateinit var repository: ChatRepositoryImpl
    private var profiles = (1..5).map { index ->
        PlatformV2(uid = "p$index", name = "Profile $index", compatibleType = ClientType.CUSTOM, apiUrl = "https://model.example/v1", model = "model-$index", token = "fixture", maxTokens = 4096, disableAllTools = true, disableLocalTools = true)
    }
    private val requests = mutableListOf<ChatCompletionRequest>()
    private var answer: suspend FlowCollector<ChatCompletionChunk>.(ChatCompletionRequest) -> Unit = { emitText("Ready.") }

    @Before fun setup() {
        CombinedWriterObservations.clearForTest()
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = files.root
            override fun getNoBackupFilesDir(): File = files.root
        }
        db = Room.inMemoryDatabaseBuilder(context, ChatDatabaseV2::class.java).build()
        runBlocking { db.chatRoomDao().addChatRoom(ChatRoomV2(id = 1, title = "Lifecycle", enabledPlatform = profiles.map { it.uid }, conversationMode = "COMBINED", combinedPrimaryUid = "p1")) }
        settings = mockk(relaxed = true)
        coEvery { settings.getFeatureSettings() } returns AppFeatureSettings()
        coEvery { settings.fetchPlatformV2s() } answers { profiles }
        api = mockk(relaxed = true)
        val requestPreparation = ModelRequestPreparation(mockk(relaxed = true))
        every { api.streamChatCompletion(any(), any(), any()) } answers {
            val request = firstArg<ChatCompletionRequest>()
            val config = thirdArg<ProviderRequestConfig>()
            flow {
                // Keep the production serialization, capacity check and physical admission;
                // only the remote response is deterministic in this lifecycle fixture.
                val wire = requestPreparation.prepare(NetworkClient.openAIJson.encodeToString(ChatCompletionRequest.serializer(), request), config, request.model, null)
                val admitted = NetworkClient.openAIJson.decodeFromString(ChatCompletionRequest.serializer(), wire)
                requests += admitted
                answer(admitted)
            }
        }
        restart()
    }

    @After fun close() = db.close()

    private fun restart() {
        budgets = CombinedTurnBudgetStore(context)
        sessions = ResearchSessionStore(context)
        val anthropic = mockk<AnthropicAPI>(relaxed = true)
        val google = mockk<GoogleAPI>(relaxed = true)
        val vault = mockk<FactVaultRepository>(relaxed = true)
        val memoryState = MutableStateFlow(FactVaultSnapshot())
        every { vault.state } returns memoryState
        coEvery { vault.scopeForChat(any()) } returns MemoryScope()
        coEvery { vault.recallGeneration() } returns "fixture-empty-memory"
        coEvery { vault.recallConsent() } returns memoryState.value
        coEvery { vault.prepareTurn(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns FactRecall()
        repository = ChatRepositoryImpl(
            context, db.chatRoomDao(), db.messageDao(), db.chatPlatformModelDao(), db.agentPersistenceDao(), db.agentRunDao(), settings,
            api, mockk<GroqAPI>(relaxed = true), anthropic, google,
            AttachmentUploadCoordinator(api, anthropic, google), ContextBuilder(), mockk(relaxed = true),
            ToolEventRecorder(db.agentPersistenceDao(), db.agentRunDao()), FakeLocalRuntime(), mockk(relaxed = true), mockk(relaxed = true), "",
            factVault = vault, invocationLedger = InvocationLedger(db, combinedBudgets = budgets), researchSessions = sessions, combinedBudgets = budgets
        )
    }

    private suspend fun turn(text: String, contribution: String, status: String = "COMPLETED", contributions: Map<String, String> = emptyMap()): PersistAgentTurnResult {
        val policy = CombinedPolicyResolver.accept(ConversationCombinedSettings(effort = 100), profiles.map { it.uid }, profiles.associate { it.uid to it.model }, text, preferredOwnerUid = "p1")
        val execution = CombinedExecutionContext(policy, CombinedPolicyResolver.resolve(policy, 1024, toolsAllowed = false), "root-${System.nanoTime()}", chatId = 1)
        val tools = ChatMcpToolConfig(allToolsDisabled = true, combined = execution)
        val accepted = CombinedAcceptedTurn(context = execution, tools = tools)
        val persisted = repository.persistAgentTurn(
            PersistAgentTurnRequest(
                ChatRoomV2(id = 1, title = "Lifecycle", enabledPlatform = profiles.map { it.uid }, conversationMode = "COMBINED", combinedPrimaryUid = "p1"),
                MessageV2(chatId = 1, platformType = null, content = text),
                profiles.map { AgentRunDraft("contributor-${execution.rootBudgetId}-${it.uid}", it.uid, it.compatibleType.name, it.model, synthesisSnapshot = accepted.encode()) },
                profiles.associate { it.uid to it.model }
            )
        )
        persisted.runs.zip(persisted.assistantMessages).forEach { (run, message) ->
            answer = {
                emitText(contributions[run.profileUid] ?: contribution, complete = status == "COMPLETED")
                if (status != "COMPLETED") throw IOException("503 contributor stream ended after useful text")
            }
            val bound = requireNotNull(CombinedAcceptedTurn.decode(run.synthesisSnapshot))
            val request = AgentRunRequest(run.runId, 1, message, profiles.single { it.uid == run.profileUid }, listOf(persisted.userMessage), listOf(emptyList()), bound.tools.copy(combined = bound.context.forRole(CombinedRunRole.CONTRIBUTOR, run.profileUid)))
            val result = execute(request)
            val recorded = db.agentRunDao().getById(run.runId)
            assertEquals("Contributor outcome=$result; terminal=${recorded?.terminalError}", status, recorded?.status)
            if (status == "COMPLETED") {
                assertEquals(ApiStateFlowOutcome.Completed, result)
            } else {
                assertTrue("Contributor failed before the simulated stream interruption: $result", recorded?.terminalError.orEmpty().contains("503 contributor stream ended after useful text"))
            }
            assertEquals(contributions[run.profileUid] ?: contribution, requireNotNull(db.agentPersistenceDao().recoveryMessage(message.id)).effectiveContent())
        }
        assertEquals(text, sessions.acceptedTask("1:${persisted.userMessage.id}")?.taskText)
        return persisted
    }

    private fun root(turn: PersistAgentTurnResult) = requireNotNull(CombinedAcceptedTurn.decode(turn.runs.first().synthesisSnapshot)).context
    private fun planner() = CombinedSynthesisPlanner(repository, settings, sessions)
    private suspend fun finalRequest(turn: PersistAgentTurnResult) = requireNotNull(planner().prepare(1, turn.userMessage.id, root(turn).rootBudgetId))
    private suspend fun frozen(runId: String) = Json { ignoreUnknownKeys = true }.decodeFromString<CombinedInputSnapshot>(requireNotNull(db.agentRunDao().getById(runId)?.synthesisSnapshot))

    private suspend fun execute(request: AgentRunRequest, commit: Boolean = true): ApiStateFlowOutcome {
        assertTrue(repository.markAgentRunRunning(request.runId, 1))
        var current = request.assistantMessage
        val outcome = repository.completeChat(request.userMessages, request.assistantMessages, request.platform, request.runId, request.chatToolConfig, request.stage)
            .collectApiStateUpdates(
                onUpdate = { content, thoughts, timeline ->
                    current = current.copy(content = content, thoughts = thoughts, timeline = request.assistantMessage.timeline.filter { it.combinedTurn != null || it.writerIdentity != null } + timeline)
                    repository.updateAgentMessage(current)
                },
                onCombinedReplacement = { candidate ->
                    repository.replaceCombinedAnswer(request.runId, current.ownershipFence, candidate)?.also { current = it } != null
                },
                onResearchReplacement = { digest, text ->
                    repository.replaceResearchDraft(request.runId, current.ownershipFence, digest, text)?.also { current = it } != null
                },
                publishIntervalMillis = 1
            )
        if (commit) {
            val status = if (outcome == ApiStateFlowOutcome.Completed) "COMPLETED" else "FAILED"
            assertTrue(repository.commitAgentRun(current, request.runId, status, 2, (outcome as? ApiStateFlowOutcome.Failed)?.message))
        }
        return outcome
    }

    private suspend fun FlowCollector<ChatCompletionChunk>.emitText(text: String, complete: Boolean = true) {
        emit(ChatCompletionChunk(choices = listOf(Choice(delta = Delta(content = text), finishReason = if (complete) "stop" else null)), usage = if (complete) ChatCompletionUsage(30, 20, 50) else null))
    }

    private fun record(snapshot: CombinedInputSnapshot, text: String, end: Boolean = true): String {
        val passage = buildJsonObject {
            put("type", "passage")
            put("seq", 1)
            put("id", "P1")
            put("text", text)
            put("claims", JsonArray(snapshot.claimInventory?.claims.orEmpty().flatMap { it.candidateIds }.distinct().map(::JsonPrimitive)))
            put("contributors", JsonArray(snapshot.sources.indices.map { JsonPrimitive("C${it + 1}") }))
            put("evidence", JsonArray(emptyList()))
        }
        return passage.toString() + if (end) "\n{\"type\":\"end\",\"seq\":2,\"id\":\"END\",\"lastSeq\":1}\n" else "\n"
    }

    @Test fun franceHiAndTestKeepOriginalIdentityAcrossRestartBeforeFirstFinalization() = runBlocking {
        listOf("history of France" to "France developed from the Frankish kingdoms into a monarchy. The French Revolution transformed its political institutions, and the Fifth Republic began in 1958.", "hi" to "Hi!", "test" to "Test received.").forEach { (question, reply) ->
            val accepted = turn(question, reply)
            restart()
            val request = finalRequest(accepted)
            assertEquals(question, request.userMessages.last().content)
            assertEquals(question, request.stage?.acceptedTask?.let { sessions.acceptedTask(it.rootWorkId)?.taskText })
            val snapshot = frozen(request.runId)
            assertEquals(AcceptedTaskRef.from(accepted.userMessage), snapshot.acceptedTask)
            answer = { emitText(record(snapshot, reply)) }
            assertEquals(ApiStateFlowOutcome.Completed, execute(request))
            assertEquals(reply, repository.fetchMessagesV2(1).single { it.id == request.assistantMessage.id }.effectiveContent())
            assertEquals(1, db.agentRunDao().getByChatId(1).count { it.userMessageId == accepted.userMessage.id && it.synthesisKey != null })
            assertNull(planner().prepare(1, accepted.userMessage.id, root(accepted).rootBudgetId))
        }
        assertEquals("Two short acknowledgments require no final provider invocation", 16, db.invocationDao().forChat(1).size)
    }

    @Test fun fiveFailedButUsableContributionsProduceOneOwnerAndPreserveFirstWriterFailure() = runBlocking {
        val accepted = turn("Explain the panel finding", "Panel finding remains useful.", "FAILED")
        val request = finalRequest(accepted)
        assertEquals(5, frozen(request.runId).sources.size)
        assertTrue(frozen(request.runId).sources.all { it.completionStatus == "FAILED" })
        answer = { throw IOException("503 original writer unavailable before first token") }
        val outcome = execute(request)
        assertTrue(outcome is ApiStateFlowOutcome.Failed)
        val run = requireNotNull(db.agentRunDao().getById(request.runId))
        assertTrue(run.terminalError.orEmpty().contains("503 original writer unavailable"))
        assertFalse(run.terminalError.orEmpty().contains("EMPTY_COMBINED_DELIVERY"))
        val selected = repository.fetchMessagesV2(1).single { it.id == run.assistantMessageId }
        assertEquals("Panel finding remains useful.", selected.effectiveContent())
        val budget = requireNotNull(budgets.snapshot(root(accepted).rootBudgetId))
        assertFalse(budget.closed)
        assertTrue(budget.retryEligible)
        assertTrue(budget.firstFailure.orEmpty().contains("503"))
        assertNull(listOf(selected).canonicalCombinedAssistant())
        val current = MessageV2(id = 99, chatId = 1, content = "hi", platformType = null)
        assertEquals(listOf(current), ContextBuilder().build(listOf(accepted.userMessage, current), listOf(listOf(selected), emptyList()), profiles.first()).map { it.userMessage })
    }

    @Test fun explicitProviderTestSummarizesSelectedAttemptsWithoutWriterRequest() = runBlocking {
        val accepted = turn("test all providers", "Test received.")
        val before = requests.size
        val request = finalRequest(accepted)
        assertEquals(ApiStateFlowOutcome.Completed, execute(request))
        assertEquals(before, requests.size)
        val content = repository.fetchMessagesV2(1).single { it.id == request.assistantMessage.id }.effectiveContent()
        assertTrue(content.contains("Provider test results"))
        profiles.forEach { assertTrue(content.contains(it.name)) }
        assertFalse(content.contains("France"))
    }

    @Test fun toolFreeFreeContributorReceivesRoleAndProviderAdjustedAllowance() = runBlocking {
        profiles = listOf(dev.chungjungsoo.gptmobile.data.model.FreeAiProvider.AI_HORDE.applyTo(profiles.first()).copy(model = "horde-text"))
        val accepted = turn("History of France in 250 words", "France developed through monarchy, revolution and republican government.")
        val wire = requests.single()
        assertEquals(256, wire.maxTokens)
        val text = wire.messages.toString()
        assertTrue(text.contains("contributor to a Combined answer"))
        assertTrue(text.contains("256 generation tokens"))
        assertTrue(text.contains("Complementary assignment"))
        assertEquals(1, accepted.runs.size)
    }

    @Test fun repeatedMalformedRecordsRotateAndPreserveValidatedPassageAcrossRestart() = runBlocking {
        val accepted = turn("Summarize this finding", "The retained finding is useful.")
        val first = finalRequest(accepted)
        val initial = frozen(first.runId)
        val invalid = "{\"type\":\"passage\",\"seq\":2,\"id\":\"P2\",\"text\":[],\"claims\":[],\"contributors\":[],\"evidence\":[]}"
        var calls = 0
        answer = { emitText(if (calls++ == 0) record(initial, "The retained finding is useful.", end = false) + invalid else invalid) }
        assertTrue(execute(first) is ApiStateFlowOutcome.Failed)
        restart()
        val second = requireNotNull(planner().prepareFallback(1, first.runId))
        answer = { emitText("{\"type\":\"end\",\"seq\":2,\"id\":\"END\",\"lastSeq\":1}") }
        assertEquals(ApiStateFlowOutcome.Completed, execute(second))
        val saved = repository.fetchMessagesV2(1).single { it.id == second.assistantMessage.id }
        assertEquals("The retained finding is useful.", saved.effectiveContent())
        assertEquals(second.platform.uid, saved.timeline.firstNotNullOf { it.writerIdentity }.writerProfileUid)
        assertFalse(repository.commitAgentRun(first.assistantMessage.copy(content = "late result"), first.runId, "COMPLETED", 4, null))
    }

    @Test fun providerFailureCreatesOneAuthorizedSuccessorWithoutReplacingOriginalSnapshotOrTask() = runBlocking {
        val accepted = turn("Summarize this finding", "The retained finding is useful.")
        val first = finalRequest(accepted)
        val raw = requireNotNull(db.agentRunDao().getById(first.runId)?.synthesisSnapshot)
        answer = { throw IOException("503 writer unavailable") }
        execute(first)
        restart()
        val second = requireNotNull(planner().prepareFallback(1, first.runId))
        val successor = frozen(second.runId)
        assertEquals("p2", second.platform.uid)
        assertEquals(first.assistantMessage.ownershipFence + 1, second.assistantMessage.ownershipFence)
        assertEquals(frozen(first.runId).sources, successor.sources)
        assertEquals(first.runId, successor.predecessorRunId)
        assertNotEquals(frozen(first.runId).snapshotId, successor.snapshotId)
        assertEquals(raw, db.agentRunDao().getById(first.runId)?.synthesisSnapshot)
        assertEquals(accepted.userMessage.content, sessions.acceptedTask("1:${accepted.userMessage.id}")?.taskText)
        assertNull(planner().prepareFallback(1, first.runId))
        answer = { emitText(record(successor, "The retained finding is useful.")) }
        assertEquals(ApiStateFlowOutcome.Completed, execute(second))
        assertEquals("FAILED", db.agentRunDao().getById(first.runId)?.status)
        assertFalse(repository.commitAgentRun(first.assistantMessage.copy(content = "late writer"), first.runId, "COMPLETED", 4, null))
    }

    @Test fun revokedAlternatesAndCancelledRootCannotBeUsedAsFallbackAuthority() = runBlocking {
        val accepted = turn("Explain this result", "The result is retained.")
        val first = finalRequest(accepted)
        answer = { throw IOException("503 writer unavailable") }
        execute(first)
        profiles = profiles.map { if (it.uid == "p1") it else it.copy(enabled = false) }
        assertNull(planner().prepareFallback(1, first.runId))
        profiles = profiles.map { it.copy(enabled = true) }
        repository.cancelCombinedTurn(root(accepted).rootBudgetId)
        assertNull(planner().prepareFallback(1, first.runId))
        assertEquals(1, db.agentRunDao().getByChatId(1).count { it.synthesisKey != null })
    }

    @Test fun unavailableWritersKeepAReadableDeterministicFallbackWithoutNetworkDispatch() = runBlocking {
        val accepted = turn("Explain this result", "The result is retained.")
        val before = requests.size
        profiles = profiles.map { it.copy(enabled = false) }
        assertNull(planner().prepare(1, accepted.userMessage.id, root(accepted).rootBudgetId))
        val owner = db.agentRunDao().getByChatId(1).single { it.synthesisKey != null }
        val message = repository.fetchMessagesV2(1).single { it.id == owner.assistantMessageId }
        assertEquals(before, requests.size)
        assertEquals("FAILED", owner.status)
        assertEquals("NO_AUTHORIZED_WRITER", owner.terminalError)
        assertTrue(message.effectiveContent().contains("The result is retained."))
        assertTrue(message.timeline.any { it.combinedFallback != null })
        assertFalse(requireNotNull(budgets.snapshot(root(accepted).rootBudgetId)).closed)
    }

    @Test fun startupReconcilesUnknownReservationWithoutFreeingUnmeasuredUsage() = runBlocking {
        val accepted = turn("Explain the evidence", "The evidence is retained.")
        val request = finalRequest(accepted)
        val snapshot = frozen(request.runId)
        val execution = requireNotNull(snapshot.combined)
        val budget = budgets.open(execution)
        val quota = CombinedResourceQuota(200, 100)
        assertTrue(budget.reserve(execution, "interrupted-physical-attempt", quota) is CombinedBudgetAdmission.Granted)
        assertTrue(repository.markAgentRunRunning(request.runId, 1))
        assertEquals(1, db.agentRunDao().finishRunning(request.runId, "INTERRUPTED", 2, "SERVICE_STOPPED"))
        restart()
        repository.reconcileCombinedLifecycle()
        val restored = requireNotNull(budgets.snapshot(root(accepted).rootBudgetId))
        val reservation = restored.reservations.getValue("interrupted-physical-attempt")
        assertEquals(CombinedReservationStatus.UNKNOWN, reservation.status)
        assertEquals(quota, reservation.charged)
        assertNull(reservation.usage)
        assertTrue(restored.retryEligible)
        assertFalse(restored.closed)
        assertEquals("The evidence is retained.", repository.fetchMessagesV2(1).single { it.id == request.assistantMessage.id }.effectiveContent())
    }

    @Test fun modifiedOriginalIdentityIsBlockedBeforeDispatchAndCannotPublish() = runBlocking {
        val accepted = turn("Keep this accepted request", "The original result is retained.")
        val request = finalRequest(accepted)
        val before = requests.size
        db.messageDao().editMessages(accepted.userMessage.copy(content = "An unrelated replacement task"))
        val outcome = execute(request, commit = false)
        assertTrue(outcome is ApiStateFlowOutcome.Failed)
        assertEquals(before, requests.size)
        assertEquals("Keep this accepted request", sessions.acceptedTask("1:${accepted.userMessage.id}")?.taskText)
        assertFalse(repository.commitAgentRun(request.assistantMessage.copy(content = "forged final"), request.runId, "COMPLETED", 3, null))
    }

    @Test fun explicitUseReplyPublishesExactlyFrozenReplyWithNewFenceAndPreservesRawFailures() = runBlocking {
        val accepted = turn("Explain the result", "The previous primary reply.", "FAILED", mapOf("p2" to "The exact retained result."))
        val request = finalRequest(accepted)
        answer = { throw IOException("503 final writer unavailable") }
        execute(request)
        assertNull(repository.useCombinedReply(request.runId, request.assistantMessage.ownershipFence + 1, "p2"))
        val selected = requireNotNull(repository.useCombinedReply(request.runId, request.assistantMessage.ownershipFence, "p2"))
        assertEquals("The exact retained result.", selected.effectiveContent())
        assertTrue(selected.ownershipFence > request.assistantMessage.ownershipFence)
        assertEquals("USE_REPLY", selected.timeline.mapNotNull { it.combinedFallback }.last().kind)
        val reloaded = repository.fetchMessagesV2(1).single { it.id == selected.id }
        assertEquals(selected.effectiveContent(), reloaded.effectiveContent())
        assertEquals("The exact retained result.", reloaded.effectiveContent())
        assertEquals("FAILED", db.agentRunDao().getById(request.runId)?.status)
        assertTrue(accepted.runs.all { db.agentRunDao().getById(it.runId)?.status == "FAILED" })
        val budget = requireNotNull(budgets.snapshot(root(accepted).rootBudgetId))
        assertTrue(budget.closed)
        assertEquals("PARTIAL", budget.terminalOutcome)
        assertNull(repository.useCombinedReply(request.runId, request.assistantMessage.ownershipFence, "p2"))
        restart()
        repository.reconcileCombinedLifecycle()
        val restored = requireNotNull(budgets.snapshot(root(accepted).rootBudgetId))
        assertEquals("PARTIAL", restored.deliveryStatus)
        assertEquals("PARTIAL", restored.terminalOutcome)
        assertFalse(restored.retryEligible)
        assertEquals("The exact retained result.", repository.fetchMessagesV2(1).single { it.id == selected.id }.effectiveContent())
    }

    @Test fun validatedPartialSurvivesRestartAndFrozenRetryWithoutReplayingContributors() = runBlocking {
        val accepted = turn("Explain the retained result", "The result has a preserved explanation.")
        val request = finalRequest(accepted)
        val original = frozen(request.runId)
        answer = {
            emitText(record(original, "The result has a preserved explanation.", end = false), complete = false)
            throw IOException("503 stream interrupted after the first committed passage")
        }
        execute(request)
        val partial = repository.fetchMessagesV2(1).single { it.id == request.assistantMessage.id }
        assertEquals("The result has a preserved explanation.", partial.effectiveContent())
        assertEquals(dev.chungjungsoo.gptmobile.data.database.entity.ACTIVE_REVISION_LATEST, partial.activeRevisionIndex)
        val contributorRequests = requests.size
        restart()
        val retryId = "combined-synthesis:retry-frozen"
        val recovery = requireNotNull(repository.prepareCombinedRetry(request.runId, profiles.single { it.uid == request.platform.uid }))
        val recoveryRaw = recovery.encode()
        val retried = repository.persistAgentRetry(PersistAgentRetryRequest(accepted.userMessage, partial, AgentRunDraft(retryId, request.platform.uid, request.platform.compatibleType.name, request.platform.model, synthesisKey = researchHash(recoveryRaw), synthesisSnapshot = recoveryRaw), resumeCombinedFrom = request.runId))
        val resumed = frozen(retryId)
        assertEquals(original.sources, resumed.sources)
        assertEquals(original.acceptedTask, resumed.acceptedTask)
        assertEquals(original.inputDigest, resumed.inputDigest)
        answer = { emitText(record(original, "The result has a preserved explanation.")) }
        val retry = request.copy(runId = retryId, assistantMessage = retried.assistantMessage, stage = resumed.bindAcceptedStage())
        assertEquals(ApiStateFlowOutcome.Completed, execute(retry))
        assertEquals(contributorRequests + 1, requests.size)
        assertTrue(accepted.runs.all { db.agentRunDao().getById(it.runId)?.status == "COMPLETED" })
        assertEquals(original.encode(), db.agentRunDao().getById(request.runId)?.synthesisSnapshot)
        val canonical = requireNotNull(repository.fetchMessagesV2(1).filter { it.linkedMessageId == accepted.userMessage.id }.canonicalCombinedAssistant())
        assertEquals(retryId, canonical.currentRunId)
        assertEquals("The result has a preserved explanation.", canonical.content)
        val quality = requireNotNull(canonical.timeline.lastOrNull { it.combinedQuality != null }?.combinedQuality)
        assertTrue(quality.complete)
        assertEquals(researchHash(canonical.content), quality.answerDigest)
    }
}
