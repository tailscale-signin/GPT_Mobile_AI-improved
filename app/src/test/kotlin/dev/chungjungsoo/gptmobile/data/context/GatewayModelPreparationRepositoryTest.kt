package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayCapabilities
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayModelPreparationReceipt
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GatewayModelPreparationRepositoryTest {
    private val profile = PlatformV2(uid = "selected", name = "Gateway", compatibleType = ClientType.LLAMA, apiUrl = "https://selected.example/v1", token = "fixture-token", model = "qwen3.5-9b", enabled = true)
    private val capabilities = GatewayCapabilities(gatewayVersion = "16.0.0", features = mapOf("model_preparation_v1" to JsonPrimitive(true)))

    private fun observation(profile: PlatformV2, mode: ReasoningMode = ReasoningMode.OFF, reason: String? = "SELECTED_MODEL_NOT_LOADED", ready: Boolean = false) =
        ReasoningCapabilityObservation(profile.uid, profile.model, mode, if (ready) ReasoningCapabilityStatus.SUPPORTED else ReasoningCapabilityStatus.UNVERIFIED, "private-revision", "runtime", 1L, reason, ready, ready)

    private fun receipt(profile: PlatformV2, operationId: String, state: String = "WAITING_FOR_MODEL", modelState: String = "LOADING") =
        GatewayModelPreparationReceipt("gptmobile.gateway.model-preparation.v1", operationId, profile.model, state, "DISPATCHED", true, 1, modelState)

    @Test fun selectedExactModelLoadsOnceAndOnlyFreshReadinessMakesItReady() = runTest {
        var reads = 0
        var loads = 0
        val phases = mutableListOf<GatewayModelPreparationPhase>()
        val repository = GatewayModelPreparationRepository(
            { capabilities },
            { selected, mode ->
                assertEquals(profile, selected)
                assertEquals(ReasoningMode.OFF, mode)
                reads++
                observation(selected, mode, reason = if (reads == 1) "SELECTED_MODEL_NOT_LOADED" else "SELECTED_MODEL_LOADING", ready = reads == 3)
            },
            { selected, id ->
                loads++
                assertEquals(profile, selected)
                receipt(selected, id)
            },
            { testScheduler.currentTime },
            pause = {},
            maxPolls = 3
        )
        val result = repository.prepareSelected(profile, ReasoningMode.OFF) { state -> state?.phase?.let(phases::add) }
        assertEquals(1, loads)
        assertEquals(3, reads)
        assertEquals(GatewayModelPreparationPhase.READY, result?.phase)
        assertTrue(phases.indexOf(GatewayModelPreparationPhase.LOADING) < phases.indexOf(GatewayModelPreparationPhase.READY))
        assertEquals(profile.model, result?.observation?.model)
        assertEquals(ReasoningMode.OFF, result?.observation?.requested)
    }

    @Test fun alreadyLoadedAndAlreadyLoadingModelsNeverSendALoad() = runTest {
        for (initiallyReady in listOf(true, false)) {
            var reads = 0
            val repository = GatewayModelPreparationRepository(
                { capabilities },
                { selected, mode ->
                    reads++
                    observation(selected, mode, "SELECTED_MODEL_LOADING", ready = initiallyReady || reads > 1)
                },
                { _, _ -> error("A loaded or loading model must not be loaded again") },
                { 0L },
                pause = {}
            )
            assertEquals(GatewayModelPreparationPhase.READY, repository.prepareSelected(profile, ReasoningMode.OFF)?.phase)
            assertEquals(if (initiallyReady) 1 else 2, reads)
        }
    }

    @Test fun oldGatewayDirectBackendUnavailableModelAndUnselectedProviderCannotLoad() = runTest {
        val oldGateway = GatewayModelPreparationRepository({ capabilities.copy(features = emptyMap()) }, { p, m -> observation(p, m) }, { _, _ -> error("Not advertised") }, { 0L })
        val blocked = oldGateway.prepareSelected(profile, ReasoningMode.OFF)
        assertEquals(GatewayModelPreparationPhase.BLOCKED, blocked?.phase)
        assertTrue(blocked?.message?.contains("update") == true)
        val direct = GatewayModelPreparationRepository({ null }, { _, _ -> error("Not a Gateway") }, { _, _ -> error("Not a Gateway") }, { 0L })
        assertNull(direct.prepareSelected(profile, ReasoningMode.OFF))
        val absent = GatewayModelPreparationRepository({ capabilities }, { p, m -> observation(p, m, "SELECTED_MODEL_NOT_AVAILABLE") }, { _, _ -> error("No alternative model") }, { 0L })
        assertEquals(GatewayModelPreparationPhase.BLOCKED, absent.prepareSelected(profile, ReasoningMode.OFF)?.phase)
        val disabled = GatewayModelPreparationRepository({ error("Not selected for preparation") }, { _, _ -> error("Not selected") }, { _, _ -> error("Not selected") }, { 0L })
        assertNull(disabled.prepareSelected(profile.copy(enabled = false), ReasoningMode.OFF))
        assertNull(disabled.prepareSelected(profile.copy(compatibleType = ClientType.OPENAI), ReasoningMode.OFF))
    }

    @Test fun explicitRecheckReusesTheOriginalOperationAfterALostLoadResponse() = runTest {
        var loads = 0
        val operations = mutableListOf<String>()
        var ready = false
        val repository = GatewayModelPreparationRepository(
            { capabilities },
            { p, m -> observation(p, m, ready = ready) },
            { _, operation ->
                loads++
                operations += operation
                throw IOException("response lost")
            },
            { 0L },
            pause = {},
            maxPolls = 1
        )
        assertEquals(GatewayModelPreparationPhase.BLOCKED, repository.prepareSelected(profile, ReasoningMode.OFF)?.phase)
        assertEquals(GatewayModelPreparationPhase.BLOCKED, repository.prepareSelected(profile, ReasoningMode.OFF)?.phase)
        assertEquals(1, loads)
        assertEquals(GatewayModelPreparationPhase.BLOCKED, repository.prepareSelected(profile, ReasoningMode.OFF, forceRefresh = true)?.phase)
        assertEquals(2, loads)
        assertEquals(1, operations.distinct().size)
        ready = true
        assertEquals(GatewayModelPreparationPhase.READY, repository.prepareSelected(profile, ReasoningMode.OFF, forceRefresh = true)?.phase)
        assertEquals(2, loads)
    }

    @Test fun cancellationAfterDispatchRetainsUnresolvedLoadForTheNextScreen() = runTest {
        var loads = 0
        val operations = mutableListOf<String>()
        var waitForLoad = true
        val repository = GatewayModelPreparationRepository(
            { capabilities },
            { p, m -> observation(p, m) },
            { selected, operation ->
                loads++
                operations += operation
                if (loads == 1) awaitCancellation()
                receipt(selected, operation)
            },
            { 0L },
            pause = { if (waitForLoad) awaitCancellation() },
            maxPolls = 1
        )
        val first = launch { repository.prepareSelected(profile, ReasoningMode.OFF) }
        runCurrent()
        assertEquals(1, loads)
        first.cancel()
        first.join()
        waitForLoad = false
        assertEquals(GatewayModelPreparationPhase.BLOCKED, repository.prepareSelected(profile, ReasoningMode.OFF, forceRefresh = true)?.phase)
        assertEquals(2, loads)
        assertEquals(1, operations.distinct().size)
    }

    @Test fun busyOrLaterDeniedOperationIsRecheckedWithItsOriginalIdentityAfterRepair() = runTest {
        val operations = mutableListOf<String>()
        var ready = false
        val repository = GatewayModelPreparationRepository(
            { capabilities },
            { p, m -> observation(p, m, ready = ready) },
            { selected, operation ->
                operations += operation
                when (operations.size) {
                    1 -> throw IOException("Gateway busy; operation may have been recorded")
                    2 -> throw dev.chungjungsoo.gptmobile.data.network.gateway.GatewayModelPreparationRejectedException(403)
                    else -> {
                        ready = true
                        receipt(selected, operation, "METADATA_QUALIFIED", "LOADED")
                    }
                }
            },
            { 0L },
            pause = {},
            maxPolls = 1
        )
        assertEquals(GatewayModelPreparationPhase.BLOCKED, repository.prepareSelected(profile, ReasoningMode.OFF)?.phase)
        assertEquals(GatewayModelPreparationPhase.BLOCKED, repository.prepareSelected(profile, ReasoningMode.OFF, forceRefresh = true)?.phase)
        assertEquals(GatewayModelPreparationPhase.READY, repository.prepareSelected(profile, ReasoningMode.OFF, forceRefresh = true)?.phase)
        assertEquals(3, operations.size)
        assertEquals(1, operations.distinct().size)
    }

    @Test fun cancellationFromLoadingPresentationCannotDispatchOrCreateAnUnresolvedLoad() = runTest {
        var loads = 0
        var ready = false
        val repository = GatewayModelPreparationRepository(
            { capabilities },
            { p, m -> observation(p, m, ready = ready) },
            { selected, id ->
                loads++
                ready = true
                receipt(selected, id, "METADATA_QUALIFIED", "LOADED")
            },
            { 0L },
            pause = {}
        )
        val selection = launch {
            val owner = coroutineContext[Job]!!
            repository.prepareSelected(profile, ReasoningMode.OFF) { state ->
                if (state?.phase == GatewayModelPreparationPhase.LOADING) owner.cancel()
            }
        }
        selection.join()
        assertEquals(0, loads)
        assertEquals(GatewayModelPreparationPhase.READY, repository.prepareSelected(profile, ReasoningMode.OFF)?.phase)
        assertEquals(1, loads)
    }

    @Test fun definitePredispatchRejectionAllowsRecheckAfterAccessRepair() = runTest {
        var loads = 0
        var ready = false
        val repository = GatewayModelPreparationRepository(
            { capabilities },
            { p, m -> observation(p, m, ready = ready) },
            { selected, id ->
                loads++
                if (loads == 1) throw dev.chungjungsoo.gptmobile.data.network.gateway.GatewayModelPreparationRejectedException(403)
                ready = true
                receipt(selected, id, "METADATA_QUALIFIED", "LOADED")
            },
            { 0L },
            pause = {}
        )
        assertEquals(GatewayModelPreparationPhase.BLOCKED, repository.prepareSelected(profile, ReasoningMode.OFF)?.phase)
        assertEquals(GatewayModelPreparationPhase.READY, repository.prepareSelected(profile, ReasoningMode.OFF, forceRefresh = true)?.phase)
        assertEquals(2, loads)
    }

    @Test fun rejectionStopIsSavedBeforeTerminalPublicationCanCancelPreparation() = runTest {
        for (receiptRejection in listOf(false, true)) {
            val vault = ModelPreparationTestVault()
            val store = GatewayModelPreparationStore(vault)
            val identity = ConfiguredDispatchAuthority.profileRevision(profile)
            var savedStops = 0
            val repository = GatewayModelPreparationRepository(
                { capabilities },
                { selected, mode -> observation(selected, mode) },
                { selected, id ->
                    if (!receiptRejection) throw dev.chungjungsoo.gptmobile.data.network.gateway.GatewayModelPreparationRejectedException(403)
                    receipt(selected, id, "LOAD_REJECTED", "UNLOADED").copy(loadAccepted = false)
                },
                { 0L },
                pause = {},
                operations = store
            )
            val selection = launch {
                val owner = coroutineContext[Job]!!
                repository.prepareSelectedWhile(
                    profile,
                    ReasoningMode.OFF,
                    forceRefresh = true,
                    validateSelection = {},
                    onRejected = {
                        assertTrue(store.read(identity, profile.model) != null)
                        savedStops++
                    }
                ) { state ->
                    if (state?.retryRequiresUserAction == true) {
                        assertEquals(1, savedStops)
                        owner.cancel()
                    }
                }
            }
            selection.join()
            assertTrue(selection.isCancelled)
            assertEquals(1, savedStops)
            assertNull(store.read(identity, profile.model))
        }
    }

    @Test fun failedDurableRejectionStopRetainsTheOriginalOperationAcrossRestart() = runTest {
        val store = GatewayModelPreparationStore(ModelPreparationTestVault())
        val ids = mutableListOf<String>()
        fun repository() = GatewayModelPreparationRepository(
            { capabilities },
            { selected, mode -> observation(selected, mode) },
            { selected, id ->
                ids += id
                receipt(selected, id, "LOAD_REJECTED", "UNLOADED").copy(loadAccepted = false)
            },
            { 0L },
            pause = {},
            operations = store
        )
        val first = repository().prepareSelectedWhile(profile, ReasoningMode.OFF, forceRefresh = true, validateSelection = {}, onRejected = { error("Synthetic failed lease stop") })
        assertEquals(GatewayModelPreparationPhase.BLOCKED, first?.phase)
        assertEquals(ids.single(), store.read(ConfiguredDispatchAuthority.profileRevision(profile), profile.model)?.operationId)
        val second = repository().prepareSelectedWhile(profile, ReasoningMode.OFF, forceRefresh = true, validateSelection = {}, onRejected = {})
        assertEquals(GatewayModelPreparationPhase.BLOCKED, second?.phase)
        assertTrue(second?.retryRequiresUserAction == true)
        assertEquals(2, ids.size)
        assertEquals(1, ids.distinct().size)
    }

    @Test fun concurrentSelectionRequestsCoalesceOneLoad() = runTest {
        val finishLoad = CompletableDeferred<Unit>()
        var loads = 0
        var ready = false
        val repository = GatewayModelPreparationRepository(
            { capabilities },
            { p, m -> observation(p, m, ready = ready) },
            { selected, id ->
                loads++
                finishLoad.await()
                ready = true
                receipt(selected, id, "METADATA_QUALIFIED", "LOADED")
            },
            { 0L },
            pause = {}
        )
        val first = async { repository.prepareSelected(profile, ReasoningMode.OFF) }
        val second = async { repository.prepareSelected(profile, ReasoningMode.OFF) }
        runCurrent()
        assertEquals(1, loads)
        finishLoad.complete(Unit)
        assertEquals(GatewayModelPreparationPhase.READY, first.await()?.phase)
        assertEquals(GatewayModelPreparationPhase.READY, second.await()?.phase)
        assertEquals(1, loads)
    }

    @Test fun successfulLoadReceiptAloneDoesNotQualifyTheModelOrChangeReasoning() = runTest {
        val repository = GatewayModelPreparationRepository(
            { capabilities },
            { p, m -> observation(p, m) },
            { selected, id -> receipt(selected, id, "METADATA_QUALIFIED", "LOADED") },
            { 0L },
            pause = {}
        )
        val result = repository.prepareSelected(profile, ReasoningMode.OFF)
        assertEquals(GatewayModelPreparationPhase.BLOCKED, result?.phase)
        assertEquals(ReasoningMode.OFF, result?.observation?.requested)
        assertFalse(result?.observation?.routeVerified == true)
    }

    @Test fun rejectedLoadStillReconcilesAnIndependentlyLoadedOrLoadingExactModel() = runTest {
        for (modelState in listOf("LOADED", "LOADING")) {
            var ready = false
            var loads = 0
            val repository = GatewayModelPreparationRepository(
                { capabilities },
                { p, m -> observation(p, m, ready = ready) },
                { selected, id ->
                    loads++
                    ready = true
                    receipt(selected, id, if (modelState == "LOADED") "METADATA_QUALIFIED" else "WAITING_FOR_MODEL", modelState).copy(loadAccepted = false)
                },
                { 0L },
                pause = {}
            )
            assertEquals(GatewayModelPreparationPhase.READY, repository.prepareSelected(profile, ReasoningMode.OFF)?.phase)
            assertEquals(1, loads)
        }
    }

    @Test fun selectionChangeFencesLateOldModelResultAndCancelsBeforeItsLoad() = runTest {
        val oldRead = CompletableDeferred<Unit>()
        val requested = mutableListOf<PlatformV2>()
        val repository = GatewayModelPreparationRepository(
            { capabilities },
            { selected, mode ->
                requested += selected
                if (selected.model == profile.model) withContext(NonCancellable) { oldRead.await() }
                observation(selected, mode, ready = selected.model != profile.model)
            },
            { _, _ -> error("The cancelled selection must not dispatch a load") },
            { 0L },
            pause = {}
        )
        val controller = GatewayModelSelectionController(repository, this)
        controller.update(listOf(profile), mapOf(profile.uid to ReasoningMode.OFF))
        runCurrent()
        val replacement = profile.copy(model = "exact-replacement", apiUrl = "https://new-route.example/v1", token = "replacement-token")
        controller.update(listOf(replacement), mapOf(profile.uid to ReasoningMode.OFF))
        runCurrent()
        assertEquals(replacement.model, controller.states.value[profile.uid]?.model)
        oldRead.complete(Unit)
        runCurrent()
        assertEquals(replacement.model, controller.states.value[profile.uid]?.model)
        assertEquals(GatewayModelPreparationPhase.READY, controller.states.value[profile.uid]?.phase)
        assertEquals(listOf(profile, replacement), requested)
        controller.clear()
        assertTrue(controller.states.value.isEmpty())
    }

    @Test fun changedCredentialsOnSameModelCannotInheritPriorReadiness() = runTest {
        val seen = mutableListOf<String?>()
        val repository = GatewayModelPreparationRepository(
            { capabilities },
            { p, m ->
                seen += p.token
                observation(p, m, ready = true)
            },
            { _, _ -> error("Already loaded") },
            { 0L }
        )
        val controller = GatewayModelSelectionController(repository, this)
        controller.update(listOf(profile))
        runCurrent()
        controller.update(listOf(profile.copy(token = "changed-token")))
        runCurrent()
        assertEquals(listOf(profile.token, "changed-token"), seen)
        controller.update(emptyList())
        assertTrue(controller.states.value.isEmpty())
    }

    @Test fun processRestartAutomaticallyReconcilesThePersistedOperationWithoutAnotherLoadIdentity() = runTest {
        val vault = ModelPreparationTestVault()
        val identity = ConfiguredDispatchAuthority.profileRevision(profile)
        val ids = mutableListOf<String>()
        var ready = false
        fun repository() = GatewayModelPreparationRepository(
            { capabilities },
            { p, m -> observation(p, m, ready = ready) },
            { selected, id ->
                assertEquals(id, GatewayModelPreparationStore(vault).read(identity, profile.model)?.operationId)
                ids += id
                if (ids.size == 1) throw IOException("load response lost")
                ready = true
                receipt(selected, id)
            },
            { 0L },
            pause = {},
            maxPolls = 1,
            operations = GatewayModelPreparationStore(vault)
        )
        assertEquals(GatewayModelPreparationPhase.BLOCKED, repository().prepareSelected(profile, ReasoningMode.OFF)?.phase)
        assertEquals(GatewayModelPreparationPhase.READY, repository().prepareSelected(profile, ReasoningMode.OFF)?.phase)
        assertEquals(2, ids.size)
        assertEquals(1, ids.distinct().size)
        assertNull(GatewayModelPreparationStore(vault).read(identity, profile.model))
    }

    @Test fun completedRetainedOperationAndFreshSleepPermitExactlyOneNewWake() = runTest {
        for (readyAfterWake in listOf(true, false)) {
            val store = GatewayModelPreparationStore(ModelPreparationTestVault())
            store.write(SavedGatewayModelPreparation(configurationRevision = ConfiguredDispatchAuthority.profileRevision(profile), model = profile.model, operationId = "completed-operation", updatedAtMillis = 0))
            val ids = mutableListOf<String>()
            var ready = false
            val repository = GatewayModelPreparationRepository(
                { capabilities },
                { p, m -> observation(p, m, ready = ready) },
                { selected, id ->
                    ids += id
                    if (id != "completed-operation") ready = readyAfterWake
                    receipt(selected, id, "METADATA_QUALIFIED", "LOADED")
                },
                { 0L },
                pause = {},
                maxPolls = 1,
                operations = store
            )
            val result = repository.prepareSelected(profile, ReasoningMode.OFF)
            assertEquals(if (readyAfterWake) GatewayModelPreparationPhase.READY else GatewayModelPreparationPhase.BLOCKED, result?.phase)
            assertEquals(2, ids.size)
            assertEquals("completed-operation", ids.first())
            assertEquals(2, ids.distinct().size)
            assertEquals(ReasoningMode.OFF, result?.observation?.requested)
        }
    }

    @Test fun unavailableOrCorruptOperationStorageBlocksBeforeLoadDispatch() = runTest {
        for (corrupt in listOf(true, false)) {
            val vault = ModelPreparationTestVault()
            if (corrupt) {
                vault.records["gateway_model_preparation_${ConfiguredDispatchAuthority.profileRevision(profile)}"] = "invalid-record".encodeToByteArray()
            } else {
                vault.failWrites = true
            }
            val repository = GatewayModelPreparationRepository(
                { capabilities },
                { p, m -> observation(p, m) },
                { _, _ -> error("Storage must precede dispatch") },
                { 0L },
                operations = GatewayModelPreparationStore(vault)
            )
            val result = repository.prepareSelected(profile, ReasoningMode.OFF)
            assertEquals(GatewayModelPreparationPhase.BLOCKED, result?.phase)
            assertTrue(result?.message?.contains("storage") == true)
        }
    }

    @Test fun definiteAccessBlockerStopsPollingAndRetainsTheDispatchedIdentity() = runTest {
        val vault = ModelPreparationTestVault()
        val store = GatewayModelPreparationStore(vault)
        var checks = 0
        var id: String? = null
        val repository = GatewayModelPreparationRepository(
            { capabilities },
            { p, m -> observation(p, m, if (++checks == 1) "SELECTED_MODEL_NOT_LOADED" else "BACKEND_METADATA_DENIED") },
            { selected, operation ->
                id = operation
                receipt(selected, operation)
            },
            { 0L },
            pause = {},
            maxPolls = 30,
            operations = store
        )
        assertEquals(GatewayModelPreparationPhase.BLOCKED, repository.prepareSelected(profile, ReasoningMode.OFF)?.phase)
        assertEquals(2, checks)
        assertEquals(id, store.read(ConfiguredDispatchAuthority.profileRevision(profile), profile.model)?.operationId)
    }

    @Test fun unqualifiedLoadedCatalogueKeepsOperationAcrossRecheckAndRestartUntilExactReadiness() = runTest {
        for (accepted in listOf(null, true)) {
            val store = GatewayModelPreparationStore(ModelPreparationTestVault())
            val identity = ConfiguredDispatchAuthority.profileRevision(profile)
            val ids = mutableListOf<String>()
            var checks = 0
            var ready = false
            fun repository() = GatewayModelPreparationRepository(
                { capabilities },
                { p, m -> observation(p, m, if (++checks == 1) "SELECTED_MODEL_NOT_LOADED" else "BACKEND_METADATA_DENIED", ready = ready) },
                { p, id ->
                    ids += id
                    receipt(p, id, "LOAD_OUTCOME_UNKNOWN", "LOADED").copy(loadAccepted = accepted, preparationAction = "WAKE")
                },
                { 0L },
                pause = {},
                maxPolls = 1,
                operations = store
            )
            val initial = repository()
            assertEquals(GatewayModelPreparationPhase.BLOCKED, initial.prepareSelected(profile, ReasoningMode.OFF)?.phase)
            assertEquals(ids.single(), store.read(identity, profile.model)?.operationId)
            assertEquals(GatewayModelPreparationPhase.BLOCKED, initial.prepareSelected(profile, ReasoningMode.OFF, forceRefresh = true)?.phase)
            val restarted = repository()
            assertEquals(GatewayModelPreparationPhase.BLOCKED, restarted.prepareSelected(profile, ReasoningMode.OFF)?.phase)
            assertEquals(3, ids.size)
            assertEquals(1, ids.distinct().size)
            assertEquals(ids.first(), store.read(identity, profile.model)?.operationId)
            ready = true
            assertEquals(GatewayModelPreparationPhase.READY, restarted.prepareSelected(profile, ReasoningMode.OFF, forceRefresh = true)?.phase)
            assertEquals(3, ids.size)
            assertNull(store.read(identity, profile.model))
        }
    }

    @Test fun unchangedSelectionRechecksOnFreshnessAndReconnectWithoutChangingModelOrReasoning() = runTest {
        var clock = 0L
        var ready = true
        var loads = 0
        val network = MutableStateFlow(0L)
        val repository = GatewayModelPreparationRepository(
            { capabilities },
            { p, m -> observation(p, m, ready = ready) },
            { p, id ->
                assertEquals(profile, p)
                loads++
                ready = true
                receipt(p, id, "METADATA_QUALIFIED", "LOADED")
            },
            { clock }
        )
        val controller = GatewayModelSelectionController(repository, this, { clock }, network)
        controller.update(listOf(profile), mapOf(profile.uid to ReasoningMode.OFF))
        runCurrent()
        ready = false
        controller.update(listOf(profile), mapOf(profile.uid to ReasoningMode.OFF))
        runCurrent()
        assertEquals(0, loads)
        clock = 20_001
        controller.update(listOf(profile), mapOf(profile.uid to ReasoningMode.OFF))
        runCurrent()
        assertEquals(1, loads)
        ready = false
        network.value++
        runCurrent()
        assertEquals(2, loads)
        assertEquals(GatewayModelPreparationPhase.READY, controller.states.value[profile.uid]?.phase)
        assertEquals(ReasoningMode.OFF, controller.states.value[profile.uid]?.observation?.requested)
        controller.clear()
    }
}

internal class ModelPreparationTestVault : SecretVault {
    val records = mutableMapOf<String, ByteArray>()
    var failWrites = false
    override suspend fun put(secretRef: String, secret: ByteArray) {
        if (failWrites) throw IOException("storage unavailable")
        records[secretRef] = secret.copyOf()
    }
    override suspend fun read(secretRef: String): ByteArray? = records[secretRef]?.copyOf()
    override suspend fun delete(secretRef: String) {
        records.remove(secretRef)
    }
}
