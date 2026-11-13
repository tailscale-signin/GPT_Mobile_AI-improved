package dev.chungjungsoo.gptmobile.data.queue

import dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationPhase
import dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationRepository
import dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationState
import dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationStore
import dev.chungjungsoo.gptmobile.data.context.ModelPreparationTestVault
import dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityObservation
import dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityStatus
import dev.chungjungsoo.gptmobile.data.context.ReasoningMode
import dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailability
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityStatus
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayCapabilities
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayModelPreparationReceipt
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayModelPreparationRejectedException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Executes queue admission and the real selected-model coordinator; only network/persistence boundaries are fake. */
class GatewayQueueModelAdmissionTest {
    private val profile = PlatformV2(uid = "selected-gateway", name = "Gateway", compatibleType = ClientType.LLAMA, apiUrl = "https://queued-gateway.example/v1", model = "accepted-exact-model")
    private val capabilities = GatewayCapabilities(gatewayVersion = "16.0.0", features = mapOf("model_preparation_v1" to JsonPrimitive(true)))

    private fun availability(status: GatewayAvailabilityStatus = GatewayAvailabilityStatus.ONLINE, reason: String? = null) =
        GatewayAvailability(status, gatewayProfileUid = profile.uid, conversationSyncSupported = true, observedAtElapsedMillis = 0, failureCode = reason)

    private fun observation(selected: PlatformV2, mode: ReasoningMode, ready: Boolean, reason: String = "SELECTED_MODEL_NOT_LOADED") =
        ReasoningCapabilityObservation(selected.uid, selected.model, mode, if (ready) ReasoningCapabilityStatus.SUPPORTED else ReasoningCapabilityStatus.UNVERIFIED, "revision", "runtime", 1, reason, ready, ready)

    private fun receipt(selected: PlatformV2, operation: String) =
        GatewayModelPreparationReceipt("gptmobile.gateway.model-preparation.v1", operation, selected.model, "WAITING_FOR_MODEL", "DISPATCHED", true, 1, "LOADING")

    @Test fun sleepingQueueLoadsOnceThenWaitsForFreshConversationReadinessBeforeDispatch() = runTest {
        val events = mutableListOf<String>()
        val states = mutableListOf<GatewayModelPreparationState>()
        var reads = 0
        val repository = GatewayModelPreparationRepository(
            { capabilities },
            { selected, mode ->
                assertEquals(profile, selected)
                assertEquals(ReasoningMode.ON, mode)
                events += "read-${++reads}"
                observation(selected, mode, reads >= 3, if (reads == 1) "SELECTED_MODEL_NOT_LOADED" else "SELECTED_MODEL_LOADING")
            },
            { selected, operation ->
                events += "load"
                receipt(selected, operation)
            },
            { 0 },
            pause = {},
            maxPolls = 3
        )
        assertFalse(GatewayQueueModelAdmission.prepareAndStart(repository, profile, ReasoningMode.ON, availability(GatewayAvailabilityStatus.MODEL_UNAVAILABLE, "SELECTED_MODEL_NOT_LOADED"), { 0 }, {}, { it?.let(states::add) }, { events += "dispatch" }))
        assertEquals(listOf("read-1", "load", "read-2", "read-3"), events)
        assertEquals(GatewayModelPreparationPhase.READY, states.last().phase)
        assertTrue(GatewayQueueModelAdmission.prepareAndStart(repository, profile, ReasoningMode.ON, availability(), { 0 }, {}, {}, { events += "dispatch" }))
        assertEquals(listOf("read-1", "load", "read-2", "read-3", "read-4", "dispatch"), events)
    }

    @Test fun modelThatSleptAfterAnOnlineObservationIsPreparedBeforeStarting() = runTest {
        val events = mutableListOf<String>()
        var ready = false
        val repository = GatewayModelPreparationRepository(
            { capabilities },
            { selected, mode ->
                events += if (ready) "ready" else "sleeping"
                observation(selected, mode, ready)
            },
            { selected, operation ->
                events += "load"
                ready = true
                receipt(selected, operation)
            },
            { 0 },
            pause = {}
        )
        assertTrue(GatewayQueueModelAdmission.prepareAndStart(repository, profile, ReasoningMode.OFF, availability(), { 0 }, {}, {}, { events += "dispatch" }))
        assertEquals(listOf("sleeping", "load", "ready", "dispatch"), events)
    }

    @Test fun onlyFreshConnectedConversationModelReadinessAllowsPreparation() = runTest {
        val sleeping = availability(GatewayAvailabilityStatus.MODEL_UNAVAILABLE, "SELECTED_MODEL_NOT_LOADED")
        val rejected = listOf(
            null,
            sleeping.copy(conversationSyncSupported = false),
            sleeping.copy(observedAtElapsedMillis = null),
            sleeping.copy(observedAtElapsedMillis = -20_000),
            sleeping.copy(failureCode = null),
            sleeping.copy(failureCode = "SELECTED_MODEL_NOT_AVAILABLE"),
            sleeping.copy(failureCode = "BACKEND_METADATA_DENIED"),
            sleeping.copy(status = GatewayAvailabilityStatus.DRAINING),
            sleeping.copy(status = GatewayAvailabilityStatus.OFFLINE),
            sleeping.copy(status = GatewayAvailabilityStatus.ACCESS_DENIED),
            sleeping.copy(status = GatewayAvailabilityStatus.CHECKING),
            sleeping.copy(gatewayProfileUid = "another-gateway")
        )
        val repository = GatewayModelPreparationRepository({ error("No discovery for an unadmitted queue") }, { _, _ -> error("No metadata") }, { _, _ -> error("No load") }, { 0 })
        for (observed in rejected) {
            assertFalse(GatewayQueueModelAdmission.prepareAndStart(repository, profile, ReasoningMode.OFF, observed, { 0 }, {}, {}, { error("No generation") }))
        }
        assertTrue(GatewayQueueModelAdmission.mayPrepare(sleeping, 0))
        assertTrue(GatewayQueueModelAdmission.mayPrepare(sleeping.copy(status = GatewayAvailabilityStatus.MODEL_LOADING, failureCode = "SELECTED_MODEL_LOADING"), 0))
    }

    @Test fun staleOnlineObservationAfterPreparationCannotStartGeneration() = runTest {
        var now = 0L
        var ready = false
        var loads = 0
        val repository = GatewayModelPreparationRepository(
            { capabilities },
            { selected, mode -> observation(selected, mode, ready) },
            { selected, operation ->
                loads++
                ready = true
                now = 20_001
                receipt(selected, operation)
            },
            { now },
            pause = {}
        )
        assertFalse(GatewayQueueModelAdmission.prepareAndStart(repository, profile, ReasoningMode.OFF, availability(), { now }, {}, {}, { error("Readiness expired") }))
        assertEquals(1, loads)
    }

    @Test fun changedLeaseAtTheLoadBoundaryCannotSendTheModelLoadOrGeneration() = runTest {
        var eligible = true
        var checks = 0
        var loads = 0
        val repository = GatewayModelPreparationRepository(
            { capabilities },
            { selected, mode ->
                checks++
                observation(selected, mode, false)
            },
            { selected, operation ->
                loads++
                receipt(selected, operation)
            },
            { 0 },
            pause = {}
        )
        val result = runCatching {
            GatewayQueueModelAdmission.prepareAndStart(
                repository,
                profile,
                ReasoningMode.OFF,
                availability(),
                { 0 },
                { check(eligible) { "Lease cancelled" } },
                { if (it?.phase == GatewayModelPreparationPhase.LOADING) eligible = false },
                { error("No generation after cancellation") }
            )
        }
        assertTrue(result.isFailure)
        assertEquals(1, checks)
        assertEquals(0, loads)
    }

    @Test fun inheritedPermissionRevocationWhileReadingMetadataPreventsLoad() = runTest {
        var admitted = true
        val repository = GatewayModelPreparationRepository(
            { capabilities },
            { selected, mode ->
                admitted = false
                observation(selected, mode, false)
            },
            { _, _ -> error("Permission was revoked before the load boundary") },
            { 0 },
            pause = {}
        )
        val result = runCatching {
            withContext(RequestAuthorizationGuard("accepted-policy") { check(admitted) }) {
                GatewayQueueModelAdmission.prepareAndStart(repository, profile, ReasoningMode.OFF, availability(), { 0 }, {}, {}, { error("No generation") })
            }
        }
        assertTrue(result.isFailure)
    }

    @Test fun cancellationAfterLoadRetainsOneDurableOperationAcrossQueueRestart() = runTest {
        val store = GatewayModelPreparationStore(ModelPreparationTestVault())
        val operations = mutableListOf<String>()
        var ready = false
        fun repository() = GatewayModelPreparationRepository(
            { capabilities },
            { selected, mode -> observation(selected, mode, ready) },
            { selected, operation ->
                operations += operation
                if (operations.size == 1) throw CancellationException("Process stopped after dispatch")
                ready = true
                receipt(selected, operation)
            },
            { 0 },
            pause = {},
            operations = store
        )
        val first = runCatching { GatewayQueueModelAdmission.prepareAndStart(repository(), profile, ReasoningMode.OFF, availability(), { 0 }, {}, {}, { error("Cancelled dispatch") }) }
        assertTrue(first.exceptionOrNull() is CancellationException)
        var starts = 0
        assertTrue(GatewayQueueModelAdmission.prepareAndStart(repository(), profile, ReasoningMode.OFF, availability(), { 0 }, {}, {}, { starts++ }))
        assertEquals(2, operations.size)
        assertEquals(1, operations.distinct().size)
        assertEquals(1, starts)
    }

    @Test fun unknownLoadResponseRechecksTheSameOperationAndCannotStartGeneration() = runTest {
        val operations = mutableListOf<String>()
        val repository = GatewayModelPreparationRepository(
            { capabilities },
            { selected, mode -> observation(selected, mode, false) },
            { _, operation ->
                operations += operation
                throw IOException("Response lost")
            },
            { 0 },
            pause = {},
            maxPolls = 1
        )
        var state: GatewayModelPreparationState? = null
        repeat(2) {
            assertFalse(GatewayQueueModelAdmission.prepareAndStart(repository, profile, ReasoningMode.OFF, availability(), { 0 }, {}, { state = it }, { error("No generation without readiness") }))
            assertFalse(requireNotNull(state).retryRequiresUserAction)
        }
        assertEquals(2, operations.size)
        assertEquals(1, operations.distinct().size)
    }

    @Test fun definitiveLoadRejectionRequiresUserActionButUnknownOperationRejectionRetainsReconciliation() = runTest {
        for (unknownFirst in listOf(false, true)) {
            var loads = 0
            var state: GatewayModelPreparationState? = null
            val operations = mutableListOf<String>()
            val repository = GatewayModelPreparationRepository(
                { capabilities },
                { selected, mode -> observation(selected, mode, false) },
                { _, operation ->
                    operations += operation
                    if (++loads == 1 && unknownFirst) throw IOException("Original outcome unknown")
                    throw GatewayModelPreparationRejectedException(403)
                },
                { 0 },
                pause = {},
                maxPolls = 1
            )
            repeat(if (unknownFirst) 2 else 1) {
                assertFalse(GatewayQueueModelAdmission.prepareAndStart(repository, profile, ReasoningMode.OFF, availability(), { 0 }, {}, { state = it }, { error("Rejected load cannot generate") }))
            }
            assertEquals(!unknownFirst, requireNotNull(state).retryRequiresUserAction)
            assertEquals(1, operations.distinct().size)
        }
    }

    @Test fun unavailableExactModelNeverLoadsAnAlternativeOrStartsGeneration() = runTest {
        val repository = GatewayModelPreparationRepository(
            { capabilities },
            { selected, mode -> observation(selected, mode, false, "SELECTED_MODEL_NOT_AVAILABLE") },
            { _, _ -> error("No substitute model") },
            { 0 }
        )
        assertFalse(GatewayQueueModelAdmission.prepareAndStart(repository, profile, ReasoningMode.OFF, availability(), { 0 }, {}, {}, { error("No generation") }))
    }
}
