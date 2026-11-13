package dev.chungjungsoo.gptmobile.data.queue

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.agent.AgentRunCoordinator
import dev.chungjungsoo.gptmobile.data.agent.AgentRunRequest
import dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationRepository
import dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityObservation
import dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityStatus
import dev.chungjungsoo.gptmobile.data.context.ReasoningMode
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunDraft
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import dev.chungjungsoo.gptmobile.data.database.entity.GatewaySubmissionRecord
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnRequest
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailability
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityStatus
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayCapabilities
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayModelPreparationReceipt
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real Room lease/ownership checks and selected-model preparation, with a captured generation boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DurablePromptQueueModelPreparationTest {
    private lateinit var database: ChatDatabaseV2
    private lateinit var store: GatewayQueueStore
    private lateinit var queue: DurablePromptQueue
    private lateinit var preparation: GatewayModelPreparationRepository
    private val settings = mockk<SettingRepository>()
    private val scopes = mockk<dev.chungjungsoo.gptmobile.data.github.GatewayVerifiedScopeStore>()
    private val coordinator = mockk<AgentRunCoordinator>(relaxed = true)
    private val profile = PlatformV2(uid = "gateway", name = "Accepted gateway", compatibleType = ClientType.LLAMA, apiUrl = "https://queue-model.example/v1", model = "accepted-model", reasoning = true)
    private val selection = GatewayQueuedSelection.capture(profile, profile.model)
    private var currentProfile = profile
    private var features = AppFeatureSettings()
    private var permission = "accepted"
    private var ready = false
    private var reads = 0
    private var discoveries = 0
    private var loads = 0
    private var rejectLoad = false
    private var onRead: suspend () -> Unit = {}
    private var onLoad: suspend () -> Unit = {}
    private val dispatched = mutableListOf<AgentRunRequest>()

    @Before fun open() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        RuntimeEnvironment.getApplication().getSharedPreferences("prompt_queue", Context.MODE_PRIVATE).edit().clear().commit()
        coEvery { scopes.configurationRevision(any()) } returns "original-credential-revision"
        coEvery { settings.fetchPlatformV2s() } answers { listOf(currentProfile) }
        coEvery { settings.getFeatureSettings() } answers { features }
        coEvery { settings.providerActivationRequired(any()) } returns false
        coEvery { settings.providerPermissionRevision(any()) } answers { permission }
        every { coordinator.activeRuns } returns MutableStateFlow(emptyMap())
        every { coordinator.start(any()) } answers { dispatched += firstArg<List<AgentRunRequest>>() }
        store = GatewayQueueStore(database, settings, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        preparation = GatewayModelPreparationRepository(
            {
                discoveries++
                GatewayCapabilities(gatewayVersion = "16.0.0", features = mapOf("model_preparation_v1" to JsonPrimitive(true)))
            },
            { selected, mode ->
                reads++
                assertEquals(profile.uid, selected.uid)
                assertEquals(profile.model, selected.model)
                assertEquals(ReasoningMode.OFF, mode)
                onRead()
                ReasoningCapabilityObservation(selected.uid, selected.model, mode, if (ready) ReasoningCapabilityStatus.SUPPORTED else ReasoningCapabilityStatus.UNVERIFIED, "revision", "runtime", 1, if (ready) null else "SELECTED_MODEL_NOT_LOADED", ready, ready)
            },
            { selected, operation ->
                loads++
                assertEquals(profile.uid, selected.uid)
                assertEquals(profile.model, selected.model)
                assertTrue(dispatched.isEmpty())
                onLoad()
                ready = !rejectLoad
                if (rejectLoad) {
                    GatewayModelPreparationReceipt("gptmobile.gateway.model-preparation.v1", operation, selected.model, "LOAD_REJECTED", "NOT_DISPATCHED", false, 0, "UNLOADED")
                } else {
                    GatewayModelPreparationReceipt("gptmobile.gateway.model-preparation.v1", operation, selected.model, "WAITING_FOR_MODEL", "DISPATCHED", true, 1, "LOADING")
                }
            },
            { 0 },
            pause = {},
            maxPolls = 2
        )
        queue = newQueue()
    }

    private fun newQueue() = DurablePromptQueue(database, mockk(relaxed = true), settings, coordinator, RuntimeEnvironment.getApplication(), store, mockk(relaxed = true), scopes, preparation)

    @After fun close() = database.close()

    private suspend fun claim(): Pair<GatewaySubmissionRecord, AgentRunRequest> {
        val initial = ChatRoomV2(title = "Queued model preparation", enabledPlatform = listOf(profile.uid), conversationMode = ConversationMode.GATEWAY)
        val room = initial.copy(id = database.chatRoomDao().addChatRoom(initial).toInt())
        features = features.copy(conversationGateway = features.conversationGateway + (room.id to selection))
        val accepted = PendingPromptPayload(profileUids = listOf(profile.uid), models = mapOf(profile.uid to profile.model), tools = ChatMcpToolConfig(reasoning = false), gateway = selection)
        val prompt = PendingPrompt(UUID.randomUUID().toString(), room.id, "Accepted exact-model prompt", Json.encodeToString(accepted), 0)
        database.pendingPromptDao().enqueue(prompt)
        val result = requireNotNull(
            store.persist(
                requireNotNull(database.pendingPromptDao().get(prompt.id)),
                emptyList(),
                PersistAgentTurnRequest(room, MessageV2(chatId = room.id, content = prompt.text, platformType = null), listOf(AgentRunDraft(UUID.randomUUID().toString(), profile.uid, profile.compatibleType.name, profile.model)), mapOf(profile.uid to profile.model), queuedPromptId = prompt.id)
            )
        )
        val lease = database.gatewayJobDao().queuedDispatches().single { it.runId == result.runs.single().runId }
        return lease to requireNotNull(store.resumedRequest(lease, profile))
    }

    private fun observation(sleeping: Boolean = false) = GatewayAvailability(
        if (sleeping) GatewayAvailabilityStatus.MODEL_UNAVAILABLE else GatewayAvailabilityStatus.ONLINE,
        gatewayProfileUid = profile.uid,
        conversationSyncSupported = true,
        observedAtElapsedMillis = SystemClock.elapsedRealtime(),
        failureCode = if (sleeping) "SELECTED_MODEL_NOT_LOADED" else null
    )

    private suspend fun settledStart(dispatcher: DurablePromptQueue, lease: GatewaySubmissionRecord, request: AgentRunRequest): GatewayQueuedChatState = withTimeout(5_000) {
        var state = dispatcher.startGatewayTurn(lease, request, observation())
        while (state.state == GatewayQueuedState.WAITING_FOR_GATEWAY) {
            delay(1)
            state = dispatcher.startGatewayTurn(lease, request, observation())
        }
        state
    }

    @Test fun acceptedLeasePreparesItsFrozenModelThenFreshReadinessStartsTheSameRunOnce() = runBlocking {
        val (lease, request) = claim()
        val first = queue.startGatewayTurn(lease, request, observation(sleeping = true))
        assertEquals(GatewayQueuedState.WAITING_FOR_GATEWAY, first.state)
        withTimeout(5_000) { while (reads < 2) delay(1) }
        assertEquals(1, loads)
        assertEquals(2, reads)
        assertTrue(dispatched.isEmpty())
        assertEquals(lease, database.gatewayJobDao().queuedDispatches().single())
        assertEquals(GatewayQueuedState.QUEUED, settledStart(queue, lease, request).state)
        settledStart(queue, lease, request)
        assertEquals(listOf(request), dispatched)
        assertEquals(1, loads)
        assertEquals(2, reads)
        assertEquals(request.runId, database.agentRunDao().getById(request.runId)?.runId)
    }

    @Test fun pausedProfileBeforePreparationCannotLoadOrDispatch() = runBlocking {
        val (lease, request) = claim()
        RuntimeEnvironment.getApplication().getSharedPreferences("prompt_queue", Context.MODE_PRIVATE).edit().putStringSet("paused_${lease.chatId}", setOf(profile.uid)).commit()
        assertEquals(GatewayQueuedState.BLOCKED, settledStart(queue, lease, request).state)
        assertEquals(0, discoveries)
        assertEquals(0, loads)
        assertTrue(dispatched.isEmpty())
    }

    @Test fun cancelledLeaseWhileMetadataIsInFlightStopsBeforeModelLoad() = runBlocking {
        val (lease, request) = claim()
        onRead = { assertTrue(store.cancelUnsubmitted(lease.chatId)) }
        assertEquals(GatewayQueuedState.BLOCKED, settledStart(queue, lease, request).state)
        assertEquals(0, loads)
        assertTrue(dispatched.isEmpty())
        assertEquals("CANCELED", database.agentRunDao().getById(request.runId)?.status)
    }

    @Test fun profileChangeWhileMetadataIsInFlightCannotLoadOldOrReplacementModel() = runBlocking {
        val (lease, request) = claim()
        onRead = { currentProfile = profile.copy(token = "changed-credential", model = "replacement-model") }
        assertEquals(GatewayQueuedState.BLOCKED, settledStart(queue, lease, request).state)
        assertEquals(0, loads)
        assertTrue(dispatched.isEmpty())
        assertEquals(lease, database.gatewayJobDao().queuedDispatches().single())
    }

    @Test fun revokedPermissionBeforeLoadCannotStartAnAcceptedPrompt() = runBlocking {
        val (lease, request) = claim()
        onRead = { permission = "revoked" }
        assertEquals(GatewayQueuedState.BLOCKED, settledStart(queue, lease, request).state)
        assertEquals(0, loads)
        assertTrue(dispatched.isEmpty())
    }

    @Test fun cancellationAfterModelLoadCannotStartGeneration() = runBlocking {
        val (lease, request) = claim()
        onLoad = { assertTrue(store.cancelUnsubmitted(lease.chatId)) }
        assertEquals(GatewayQueuedState.BLOCKED, settledStart(queue, lease, request).state)
        assertEquals(1, loads)
        assertTrue(dispatched.isEmpty())
        assertEquals("CANCELED", database.agentRunDao().getById(request.runId)?.status)
    }

    @Test fun startedOrUnknownGenerationCannotEnterModelPreparation() = runBlocking {
        val (lease, request) = claim()
        val run = requireNotNull(database.agentRunDao().getById(request.runId))
        for (started in listOf(run.copy(startedAt = 1), run.copy(status = "RUNNING"), run.copy(gatewayJobId = "existing-remote-generation"))) {
            database.agentRunDao().upsert(started)
            assertEquals(GatewayQueuedState.BLOCKED, settledStart(queue, lease, request).state)
        }
        database.agentRunDao().upsert(run)
        database.gatewayJobDao().insertSubmission(lease.copy(commandId = "unknown-generation", state = "OUTCOME_UNKNOWN"))
        assertEquals(GatewayQueuedState.BLOCKED, settledStart(queue, lease, request).state)
        assertEquals(0, discoveries)
        assertEquals(0, loads)
        assertTrue(dispatched.isEmpty())
    }

    @Test fun undispatchedRestartKeepsTheOriginalAcceptedRunAndReasoning() = runBlocking {
        val (lease, original) = claim()
        val run = requireNotNull(database.agentRunDao().getById(original.runId))
        database.agentRunDao().upsert(run.copy(status = "INTERRUPTED", completedAt = 1, terminalError = "SERVICE_STOPPED"))
        val restarted = requireNotNull(store.resumedRequest(lease, profile))
        assertEquals(original, restarted)
        assertEquals(GatewayQueuedState.QUEUED, settledStart(queue, lease, restarted).state)
        assertEquals(1, loads)
        assertEquals(listOf(original), dispatched)
        assertFalse(dispatched.single().platform.reasoning)
        verify(exactly = 1) { coordinator.start(listOf(restarted)) }
    }

    @Test fun definitiveRejectionSurvivesRepeatedReadinessAndRestartUntilExplicitRetry() = runBlocking {
        val (lease, request) = claim()
        rejectLoad = true
        assertEquals(GatewayQueuedState.BLOCKED, settledStart(queue, lease, request).state)
        val blocked = database.gatewayJobDao().queuedDispatches().single()
        assertEquals(GatewayQueueStore.MODEL_PREPARATION_BLOCKED, blocked.receipt)
        repeat(3) {
            assertEquals(GatewayQueuedState.BLOCKED, settledStart(queue, blocked, request).state)
            assertEquals(GatewayQueuedState.BLOCKED, settledStart(newQueue(), blocked, request).state)
        }
        assertEquals(1, loads)
        assertEquals(1, discoveries)
        assertTrue(dispatched.isEmpty())
        assertTrue(store.resumeModelPreparation(blocked))
        rejectLoad = false
        val resumed = database.gatewayJobDao().queuedDispatches().single()
        assertEquals(GatewayQueuedState.QUEUED, settledStart(newQueue(), resumed, request).state)
        assertEquals(2, loads)
        assertEquals(listOf(request), dispatched)
    }

    @Test fun preparationRetryCannotClearAReplacedLeaseOrChangedRunOwner() = runBlocking {
        val (lease, request) = claim()
        assertTrue(store.blockModelPreparation(lease))
        val blocked = database.gatewayJobDao().queuedDispatches().single()
        assertFalse(store.resumeModelPreparation(lease))
        val replacement = blocked.copy(fingerprint = "changed-lease")
        database.gatewayJobDao().saveSubmission(replacement)
        assertFalse(store.resumeModelPreparation(blocked))
        assertEquals(replacement, database.gatewayJobDao().queuedDispatches().single())
        database.gatewayJobDao().saveSubmission(blocked)
        database.agentPersistenceDao().updateMessage(request.assistantMessage.copy(ownershipFence = request.assistantMessage.ownershipFence + 1))
        assertFalse(store.resumeModelPreparation(blocked))
        assertEquals(blocked, database.gatewayJobDao().queuedDispatches().single())
        assertEquals(0, loads)
    }

    @Test fun preparationRetryCannotClearStartedRestoredOrUnknownGeneration() = runBlocking {
        val (lease, request) = claim()
        assertTrue(store.blockModelPreparation(lease))
        val blocked = database.gatewayJobDao().queuedDispatches().single()
        val run = requireNotNull(database.agentRunDao().getById(request.runId))
        for (changed in listOf(run.copy(startedAt = 1), run.copy(status = "RUNNING"), run.copy(gatewayJobId = "saved-generation"), run.copy(terminalError = "BACKUP_RESTORED"))) {
            database.agentRunDao().upsert(changed)
            assertFalse(store.resumeModelPreparation(blocked))
            assertEquals(blocked, database.gatewayJobDao().queuedDispatches().single())
        }
        database.agentRunDao().upsert(run)
        database.gatewayJobDao().insertSubmission(lease.copy(commandId = "unknown-generation", state = "OUTCOME_UNKNOWN"))
        assertFalse(store.resumeModelPreparation(blocked))
        assertEquals(blocked, database.gatewayJobDao().queuedDispatches().single())
        assertEquals(0, loads)
        assertTrue(dispatched.isEmpty())
    }
}
