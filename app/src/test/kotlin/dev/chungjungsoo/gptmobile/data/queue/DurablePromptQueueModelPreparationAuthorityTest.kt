package dev.chungjungsoo.gptmobile.data.queue

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.agent.AgentRunCoordinator
import dev.chungjungsoo.gptmobile.data.agent.AgentRunRequest
import dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationRepository
import dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationStore
import dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityObservation
import dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityStatus
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunDraft
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import dev.chungjungsoo.gptmobile.data.database.entity.GatewaySubmissionRecord
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnRequest
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GatewayVerifiedScopeStore
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Authority changes must fence the current leased path, including after suspended ownership reads. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DurablePromptQueueModelPreparationAuthorityTest {
    @Test fun permissionRevokedDuringMetadataStopsModelLoadingAndPreservesTheAcceptedPrompt() = runBlocking {
        verifyPreparation(revokePermission = true)
    }

    @Test fun unchangedPermissionAllowsExactModelPreparationAndStartsTheAcceptedRun() = runBlocking {
        verifyPreparation(revokePermission = false)
    }

    @Test fun activationRequiredBeforePreparationStopsBeforeMetadataAndKeepsThePrompt() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.activationRequired.value = true
            assertEquals(GatewayQueuedState.BLOCKED, fixture.admit().state)
            assertEquals(0, fixture.reads.get())
            assertEquals(0, fixture.loads.get())
            fixture.assertPromptRetained(started = false)
        } finally {
            fixture.close()
        }
    }

    @Test fun permissionRevokedDuringFinalOwnershipCheckStopsTheSavedLoadBeforeDispatch() = runBlocking {
        val fixture = Fixture(pauseFinalOwnership = true)
        try {
            fixture.initialize()
            assertEquals(GatewayQueuedState.WAITING_FOR_GATEWAY, fixture.admit().state)
            withTimeout(5_000) { fixture.metadataEntered.await() }
            fixture.resumeMetadata.complete(Unit)
            // The operation is durably identified, but no load has dispatched.
            // The captured permission was checked before this ownership lookup suspended.
            withTimeout(5_000) { fixture.ownershipEntered.await() }
            assertEquals(0, fixture.loads.get())
            fixture.permission.value = "revoked"
            fixture.resumeOwnership.complete(Unit)
            assertEquals(GatewayQueuedState.BLOCKED, fixture.settled().state)
            assertEquals(0, fixture.loads.get())
            fixture.assertPromptRetained(started = false)
        } finally {
            fixture.close()
        }
    }

    private suspend fun verifyPreparation(revokePermission: Boolean) {
        val fixture = Fixture()
        try {
            fixture.initialize()
            assertEquals(GatewayQueuedState.WAITING_FOR_GATEWAY, fixture.admit().state)
            withTimeout(5_000) { fixture.metadataEntered.await() }
            assertEquals(0, fixture.loads.get())
            if (revokePermission) fixture.permission.value = "revoked"
            fixture.resumeMetadata.complete(Unit)
            val settled = fixture.settled()
            if (revokePermission) {
                assertEquals(GatewayQueuedState.BLOCKED, settled.state)
                assertEquals(1, fixture.reads.get())
                assertEquals(0, fixture.loads.get())
            } else {
                assertEquals(GatewayQueuedState.QUEUED, settled.state)
                assertEquals(2, fixture.reads.get())
                assertEquals(1, fixture.loads.get())
            }
            fixture.assertPromptRetained(started = !revokePermission)
        } finally {
            fixture.close()
        }
    }

    private class Fixture(pauseFinalOwnership: Boolean = false) {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        private val profile = PlatformV2(uid = "accepted-gateway", name = "Gateway", compatibleType = ClientType.LLAMA, apiUrl = "https://preparation-authority.example/v1", model = "exact-accepted-model", reasoning = false)
        private val selected = GatewayQueuedSelection.capture(profile, profile.model)
        private val settings = mockk<SettingRepository>()
        private val scopes = mockk<GatewayVerifiedScopeStore>()
        private val coordinator = mockk<AgentRunCoordinator>(relaxed = true)
        private val operations = mockk<GatewayModelPreparationStore>(relaxed = true)
        private lateinit var queue: DurablePromptQueue
        private lateinit var prompt: PendingPrompt
        private lateinit var lease: GatewaySubmissionRecord
        private lateinit var request: AgentRunRequest
        val permission = MutableStateFlow("accepted")
        val activationRequired = MutableStateFlow(false)
        val reads = AtomicInteger()
        val loads = AtomicInteger()
        val metadataEntered = CompletableDeferred<Unit>()
        val resumeMetadata = CompletableDeferred<Unit>()
        val ownershipEntered = CompletableDeferred<Unit>()
        val resumeOwnership = CompletableDeferred<Unit>()
        private val ready = AtomicBoolean()
        private val loadSaved = AtomicBoolean()
        private val suspendOwnership = AtomicBoolean(pauseFinalOwnership)
        private val dispatched = mutableListOf<AgentRunRequest>()

        suspend fun initialize() {
            RuntimeEnvironment.getApplication().getSharedPreferences("prompt_queue", Context.MODE_PRIVATE).edit().clear().commit()
            val initial = ChatRoomV2(title = "Accepted queued prompt", enabledPlatform = listOf(profile.uid), conversationMode = ConversationMode.GATEWAY)
            val room = initial.copy(id = database.chatRoomDao().addChatRoom(initial).toInt())
            val features = AppFeatureSettings(conversationGateway = mapOf(room.id to selected))
            val payload = PendingPromptPayload(profileUids = listOf(profile.uid), models = mapOf(profile.uid to profile.model), gateway = selected)
            prompt = PendingPrompt("accepted-prompt", room.id, "Keep this exact saved prompt", Json.encodeToString(payload), 0)
            database.pendingPromptDao().enqueue(prompt)
            coEvery { settings.fetchPlatformV2s() } returns listOf(profile)
            coEvery { settings.getFeatureSettings() } returns features
            coEvery { settings.providerActivationRequired(any()) } answers { activationRequired.value }
            coEvery { settings.providerPermissionRevision(any()) } answers { permission.value }
            coEvery { scopes.configurationRevision(any()) } coAnswers {
                if (loadSaved.get() && suspendOwnership.compareAndSet(true, false)) {
                    ownershipEntered.complete(Unit)
                    resumeOwnership.await()
                }
                "accepted-vault-credential"
            }
            coEvery { operations.read(any(), any()) } returns null
            coEvery { operations.write(any()) } answers { loadSaved.set(true) }
            every { coordinator.activeRuns } returns MutableStateFlow(emptyMap())
            every { coordinator.start(any()) } answers { dispatched += firstArg<List<AgentRunRequest>>() }
            val store = GatewayQueueStore(database, settings, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
            val persisted = requireNotNull(
                store.persist(
                    requireNotNull(database.pendingPromptDao().get(prompt.id)),
                    emptyList(),
                    PersistAgentTurnRequest(room, MessageV2(chatId = room.id, content = prompt.text, platformType = null), listOf(AgentRunDraft(UUID.randomUUID().toString(), profile.uid, profile.compatibleType.name, profile.model)), mapOf(profile.uid to profile.model), queuedPromptId = prompt.id)
                )
            )
            prompt = requireNotNull(database.pendingPromptDao().get(prompt.id))
            lease = database.gatewayJobDao().queuedDispatches().single { it.runId == persisted.runs.single().runId }
            request = requireNotNull(store.resumedRequest(lease, profile))
            val preparation = GatewayModelPreparationRepository(
                { GatewayCapabilities(gatewayVersion = "16.0.0", features = mapOf("model_preparation_v1" to JsonPrimitive(true))) },
                { observedProfile, mode ->
                    assertEquals(profile, observedProfile)
                    if (reads.incrementAndGet() == 1) {
                        metadataEntered.complete(Unit)
                        resumeMetadata.await()
                    }
                    val loaded = ready.get()
                    ReasoningCapabilityObservation(observedProfile.uid, observedProfile.model, mode, if (loaded) ReasoningCapabilityStatus.SUPPORTED else ReasoningCapabilityStatus.UNVERIFIED, "revision", "runtime", 1, if (loaded) null else "SELECTED_MODEL_NOT_LOADED", loaded, loaded)
                },
                { observedProfile, operation ->
                    assertEquals(profile, observedProfile)
                    loads.incrementAndGet()
                    ready.set(true)
                    GatewayModelPreparationReceipt("gptmobile.gateway.model-preparation.v1", operation, observedProfile.model, "METADATA_QUALIFIED", "DISPATCHED", true, 1, "LOADED")
                },
                System::currentTimeMillis,
                pause = {},
                maxPolls = 1,
                operations = operations
            )
            queue = DurablePromptQueue(database, mockk(relaxed = true), settings, coordinator, RuntimeEnvironment.getApplication(), store, mockk(relaxed = true), scopes, preparation)
        }

        suspend fun admit(): GatewayQueuedChatState = queue.startGatewayTurn(
            lease,
            request,
            GatewayAvailability(
                status = if (ready.get()) GatewayAvailabilityStatus.ONLINE else GatewayAvailabilityStatus.NOT_READY,
                gatewayProfileUid = profile.uid,
                conversationSyncSupported = true,
                observedAtElapsedMillis = SystemClock.elapsedRealtime(),
                failureCode = if (ready.get()) null else "SELECTED_MODEL_NOT_LOADED"
            )
        )

        suspend fun settled(): GatewayQueuedChatState = withTimeout(5_000) {
            var state = admit()
            while (state.state == GatewayQueuedState.WAITING_FOR_GATEWAY) {
                delay(1)
                state = admit()
            }
            state
        }

        suspend fun assertPromptRetained(started: Boolean) {
            assertEquals(prompt, database.pendingPromptDao().get(prompt.id))
            assertEquals(lease, database.gatewayJobDao().queuedDispatches().single())
            assertEquals(if (started) listOf(request) else emptyList<AgentRunRequest>(), dispatched)
            assertTrue(database.gatewayJobDao().submissionsForRun(request.runId).isEmpty())
            verify(exactly = if (started) 1 else 0) { coordinator.start(any()) }
        }

        fun close() {
            resumeMetadata.complete(Unit)
            resumeOwnership.complete(Unit)
            database.close()
        }
    }
}
