package dev.chungjungsoo.gptmobile.data.queue

import android.app.Application
import android.os.SystemClock
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.agent.AgentRunCoordinator
import dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationRepository
import dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityObservation
import dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityStatus
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
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
import io.mockk.mockk
import io.mockk.verify
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DurablePromptQueueModelPreparationAuthorityTest {
    @Test fun permissionRevokedDuringMetadataStopsModelLoadingAndPreservesTheAcceptedPrompt() = runBlocking {
        verifyPreparation(revokePermission = true)
    }

    @Test fun unchangedPermissionAllowsExactModelPreparationWithoutConsumingThePrompt() = runBlocking {
        verifyPreparation(revokePermission = false)
    }

    @Test fun activationRequiredBeforePreparationStopsBeforeMetadataAndKeepsThePrompt() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.activationRequired.value = true
            val failure = runCatching { fixture.admit() }.exceptionOrNull()
            assertTrue(failure?.message?.startsWith("PROVIDER_ACTIVATION_REQUIRED:") == true)
            assertEquals(0, fixture.reads.get())
            assertEquals(0, fixture.loads.get())
            fixture.assertPromptRetained()
        } finally {
            fixture.database.close()
        }
    }

    private suspend fun verifyPreparation(revokePermission: Boolean) {
        val fixture = Fixture()
        try {
            fixture.initialize()
            assertEquals(GatewayQueuedState.WAITING_FOR_GATEWAY, fixture.admit()?.state)
            withTimeout(5_000) { fixture.metadataEntered.await() }
            assertEquals(0, fixture.loads.get())
            if (revokePermission) fixture.permission.value = "revoked"
            fixture.resumeMetadata.complete(Unit)
            val settled = withTimeout(5_000) {
                var state = fixture.admit()
                while (state?.state == GatewayQueuedState.WAITING_FOR_GATEWAY) {
                    delay(1)
                    state = fixture.admit()
                }
                state
            }
            if (revokePermission) {
                assertEquals(GatewayQueuedState.BLOCKED, settled?.state)
                assertEquals(1, fixture.reads.get())
                assertEquals(0, fixture.loads.get())
            } else {
                assertNull(settled)
                assertEquals(2, fixture.reads.get())
                assertEquals(1, fixture.loads.get())
            }
            fixture.assertPromptRetained()
        } finally {
            fixture.resumeMetadata.complete(Unit)
            fixture.database.close()
        }
    }

    private class Fixture {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        private val profile = PlatformV2(uid = "accepted-gateway", name = "Gateway", compatibleType = ClientType.LLAMA, apiUrl = "https://preparation-authority.example/v1", model = "exact-accepted-model", reasoning = false)
        private val selected = GatewayQueuedSelection.capture(profile, profile.model)
        private val settings = mockk<SettingRepository>()
        private val scopes = mockk<GatewayVerifiedScopeStore>()
        private val coordinator = mockk<AgentRunCoordinator>(relaxed = true)
        private lateinit var queue: DurablePromptQueue
        private lateinit var prompt: PendingPrompt
        private lateinit var payload: PendingPromptPayload
        val permission = MutableStateFlow("accepted")
        val activationRequired = MutableStateFlow(false)
        val reads = AtomicInteger()
        val loads = AtomicInteger()
        val metadataEntered = CompletableDeferred<Unit>()
        val resumeMetadata = CompletableDeferred<Unit>()
        private val ready = AtomicBoolean()

        suspend fun initialize() {
            val room = ChatRoomV2(title = "Accepted queued prompt", enabledPlatform = listOf(profile.uid))
            val chatId = database.chatRoomDao().addChatRoom(room).toInt()
            val features = AppFeatureSettings(conversationGateway = mapOf(chatId to selected))
            payload = PendingPromptPayload(profileUids = listOf(profile.uid), models = mapOf(profile.uid to profile.model), gateway = selected)
            prompt = PendingPrompt("accepted-prompt", chatId, "Keep this exact saved prompt", Json.encodeToString(payload), 0)
            database.pendingPromptDao().enqueue(prompt)
            prompt = requireNotNull(database.pendingPromptDao().get(prompt.id))
            coEvery { settings.fetchPlatformV2s() } returns listOf(profile)
            coEvery { settings.getFeatureSettings() } returns features
            coEvery { settings.providerActivationRequired(any()) } answers { activationRequired.value }
            coEvery { settings.providerPermissionRevision(any()) } answers { permission.value }
            coEvery { scopes.configurationRevision(any()) } returns "accepted-vault-credential"
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
                maxPolls = 1
            )
            queue = DurablePromptQueue(database, mockk(relaxed = true), settings, coordinator, RuntimeEnvironment.getApplication(), mockk(relaxed = true), mockk(relaxed = true), scopes, preparation)
        }

        suspend fun admit(): GatewayQueuedChatState? = queue.modelPreparationAdmission(
            "prompt:${prompt.id}",
            prompt.chatId,
            payload,
            GatewayAvailability(
                status = if (ready.get()) GatewayAvailabilityStatus.ONLINE else GatewayAvailabilityStatus.NOT_READY,
                conversationSyncSupported = true,
                observedAtElapsedMillis = SystemClock.elapsedRealtime(),
                failureCode = if (ready.get()) null else "SELECTED_MODEL_NOT_LOADED"
            )
        ) { database.pendingPromptDao().get(prompt.id) == prompt }

        suspend fun assertPromptRetained() {
            assertEquals(prompt, database.pendingPromptDao().get(prompt.id))
            assertTrue(database.gatewayJobDao().queuedDispatches().isEmpty())
            verify(exactly = 0) { coordinator.start(any()) }
        }
    }
}
