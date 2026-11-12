package dev.chungjungsoo.gptmobile.data.queue

import dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationRepository
import dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityObservation
import dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityStatus
import dev.chungjungsoo.gptmobile.data.context.ReasoningMode
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailability
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityStatus
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayCapabilities
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayModelPreparationReceipt
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GatewayQueuedModelPreparationTest {
    private val profile = PlatformV2(uid = "saved-gateway", name = "Gateway", compatibleType = ClientType.LLAMA, apiUrl = "https://gateway.example/v1", token = "original-credential", model = "exact-saved-model", reasoning = false)
    private val capabilities = GatewayCapabilities(gatewayVersion = "16.0.0", features = mapOf("model_preparation_v1" to JsonPrimitive(true)))

    @Test fun pendingSendAndRetainedUnsubmittedLeaseLoadOnceAndWaitForFreshReadinessBeforeDispatch() = runTest {
        for (owner in listOf("prompt:accepted-send", "lease:retained-unsubmitted")) {
            var loaded = false
            var prepares = 0
            var dispatches = 0
            val repository = GatewayModelPreparationRepository(
                { selected ->
                    assertEquals(profile, selected)
                    capabilities
                },
                { selected, mode -> observation(selected, mode, loaded) },
                { selected, operation ->
                    assertEquals(profile, selected)
                    prepares++
                    receipt(operation)
                },
                { testScheduler.currentTime },
                pause = {
                    delay(2_000)
                    loaded = true
                },
                maxPolls = 1
            )
            val preparation = GatewayQueuedModelPreparation(repository, backgroundScope) { testScheduler.currentTime }
            val request = GatewayQueuedModelPreparation.Request(owner, profile, ReasoningMode.OFF, "original-vault-record")
            val sleeping = availability(testScheduler.currentTime)
            assertEquals(GatewayQueuedState.WAITING_FOR_GATEWAY, preparation.admission(request, sleeping) { true }?.state)
            runCurrent()
            repeat(3) { assertNotNull(preparation.admission(request, sleeping) { true }) }
            assertEquals(1, prepares)
            assertEquals(0, dispatches)
            val oldReady = availability(testScheduler.currentTime, ready = true)
            advanceTimeBy(2_000)
            runCurrent()
            assertNotNull(preparation.admission(request, sleeping) { true })
            assertNotNull(preparation.admission(request, oldReady) { true })
            val fresh = availability(testScheduler.currentTime, ready = true)
            if (preparation.admission(request, fresh) { true } == null && fresh.isOnline) dispatches++
            assertEquals(1, dispatches)
            assertEquals(1, prepares)
            preparation.retain(emptySet())
        }
    }

    @Test fun rejectedAndUnknownLoadsStayBlockedWithoutRepeatedLoadsOrGenerationOnPassiveRefresh() = runTest {
        for (unknown in listOf(false, true)) {
            val operations = mutableListOf<String>()
            var modelReady = false
            val repository = GatewayModelPreparationRepository(
                { capabilities },
                { selected, mode -> observation(selected, mode, modelReady) },
                { _, operation ->
                    operations += operation
                    if (unknown) throw IOException("Synthetic lost response")
                    receipt(operation).copy(state = "LOAD_REJECTED", loadAccepted = false, modelState = "UNLOADED")
                },
                { testScheduler.currentTime },
                pause = {},
                maxPolls = 1
            )
            val preparation = GatewayQueuedModelPreparation(repository, backgroundScope) { testScheduler.currentTime }
            val request = GatewayQueuedModelPreparation.Request("prompt:accepted-$unknown", profile, ReasoningMode.OFF, "original-vault-record")
            preparation.admission(request, availability(testScheduler.currentTime)) { true }
            runCurrent()
            repeat(4) {
                assertEquals(GatewayQueuedState.BLOCKED, preparation.admission(request, availability(testScheduler.currentTime)) { true }?.state)
                // A route-only online observation cannot erase a failed preparation's exact-model admission.
                assertEquals(GatewayQueuedState.BLOCKED, preparation.admission(request, availability(testScheduler.currentTime, ready = true)) { true }?.state)
            }
            assertEquals(1, operations.size)
            advanceTimeBy(1)
            assertNotNull(preparation.admission(request, availability(testScheduler.currentTime, ready = true)) { true })
            runCurrent()
            assertEquals(GatewayQueuedState.BLOCKED, preparation.admission(request, availability(testScheduler.currentTime)) { true }?.state)
            assertEquals("A newer route observation only permits a read-only exact-model recheck", 1, operations.size)
            if (unknown) {
                // An explicit pause/resume can reconcile, but uses the retained operation ID.
                preparation.retain(emptySet())
                preparation.admission(request, availability(testScheduler.currentTime)) { true }
                runCurrent()
                assertEquals(2, operations.size)
                assertEquals(1, operations.distinct().size)
            }
            val loadRequests = operations.size
            modelReady = true
            advanceTimeBy(1)
            assertNotNull(preparation.admission(request, availability(testScheduler.currentTime, ready = true)) { true })
            runCurrent()
            assertNull(preparation.admission(request, availability(testScheduler.currentTime, ready = true)) { true })
            assertEquals("Late readiness cannot dispatch another load", loadRequests, operations.size)
            preparation.retain(emptySet())
        }
    }

    @Test fun removalPauseOrCredentialRevocationBeforeLoadPreventsDispatch() = runTest {
        for (cancelOwner in listOf(false, true)) {
            val discovered = CompletableDeferred<Unit>()
            var current = true
            var prepares = 0
            val repository = GatewayModelPreparationRepository(
                {
                    discovered.await()
                    capabilities
                },
                { selected, mode ->
                    if (!cancelOwner) current = false
                    observation(selected, mode)
                },
                { _, operation ->
                    prepares++
                    receipt(operation)
                },
                { testScheduler.currentTime },
                pause = {},
                maxPolls = 1
            )
            val preparation = GatewayQueuedModelPreparation(repository, backgroundScope) { testScheduler.currentTime }
            val request = GatewayQueuedModelPreparation.Request("prompt:accepted-$cancelOwner", profile, ReasoningMode.OFF, "original-vault-record")
            preparation.admission(request, availability(testScheduler.currentTime)) { current }
            runCurrent()
            if (cancelOwner) preparation.retain(emptySet())
            discovered.complete(Unit)
            runCurrent()
            assertEquals(0, prepares)
            if (!cancelOwner) assertEquals(GatewayQueuedState.BLOCKED, preparation.admission(request, availability(testScheduler.currentTime)) { current }?.state)
            preparation.retain(emptySet())
        }
    }

    @Test fun alreadyLoadingModelIsObservedAndDifferentQueuedModelsDoNotLoadConcurrently() = runTest {
        var loaded = false
        var activeChecks = 0
        val repository = GatewayModelPreparationRepository(
            { capabilities },
            { selected, mode ->
                activeChecks++
                observation(selected, mode, loaded, "SELECTED_MODEL_LOADING")
            },
            { _, _ -> error("An existing model load must not be dispatched again") },
            { testScheduler.currentTime },
            pause = {
                delay(2_000)
                loaded = true
            },
            maxPolls = 1
        )
        val preparation = GatewayQueuedModelPreparation(repository, backgroundScope) { testScheduler.currentTime }
        val first = GatewayQueuedModelPreparation.Request("lease:first", profile, ReasoningMode.OFF, "original-vault-record")
        val second = first.copy(owner = "prompt:second", profile = profile.copy(model = "other-accepted-model"))
        val loading = availability(0).copy(failureCode = "SELECTED_MODEL_LOADING")
        assertNotNull(preparation.admission(first, loading) { true })
        runCurrent()
        assertTrue(preparation.admission(second, loading) { true }?.message?.contains("earlier") == true)
        assertEquals(1, activeChecks)
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(preparation.admission(second, loading.copy(observedAtElapsedMillis = testScheduler.currentTime)) { true }?.message?.contains("earlier") == true)
        assertTrue(preparation.admission(second, availability(testScheduler.currentTime, ready = true)) { true }?.message?.contains("earlier") == true)
        assertEquals(2, activeChecks)
        assertNull(preparation.admission(first, availability(testScheduler.currentTime, ready = true)) { true })
        assertNotNull(preparation.admission(second, availability(testScheduler.currentTime, ready = true)) { true })
        preparation.cancel(first.owner)
        assertNull(preparation.admission(second, availability(testScheduler.currentTime, ready = true)) { true })
        preparation.retain(emptySet())
    }

    private fun availability(time: Long, ready: Boolean = false) = GatewayAvailability(
        status = if (ready) GatewayAvailabilityStatus.ONLINE else GatewayAvailabilityStatus.NOT_READY,
        conversationSyncSupported = true,
        observedAtElapsedMillis = time,
        failureCode = if (ready) null else "SELECTED_MODEL_NOT_LOADED"
    )

    private fun observation(selected: PlatformV2, mode: ReasoningMode, ready: Boolean = false, reason: String = "SELECTED_MODEL_NOT_LOADED") = ReasoningCapabilityObservation(
        selected.uid, selected.model, mode,
        if (ready) ReasoningCapabilityStatus.SUPPORTED else ReasoningCapabilityStatus.UNVERIFIED,
        "model-revision", "gateway-runtime", 1L, if (ready) null else reason, ready, ready
    )

    private fun receipt(operation: String) = GatewayModelPreparationReceipt("gptmobile.gateway.model-preparation.v1", operation, profile.model, "WAITING_FOR_MODEL", "DISPATCHED", true, 1, "LOADING")
}
