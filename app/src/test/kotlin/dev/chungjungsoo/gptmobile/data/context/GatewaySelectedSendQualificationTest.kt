package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayCapabilities
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayModelPreparationReceipt
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewaySelectedSendQualificationTest {
    private val profile = PlatformV2(uid = "pc", name = "Gateway", compatibleType = ClientType.LLAMA, apiUrl = "http://192.168.1.3:8090/v1", model = "selected")
    private val mode = ReasoningMode.AUTO
    private val sleeping = ReasoningCapabilityObservation(profile.uid, profile.model, mode, ReasoningCapabilityStatus.SUPPORTED, "config", "runtime", 1, "SELECTED_MODEL_NOT_LOADED", false, true)
    private val loaded = sleeping.copy(routeVerified = true, safeReasonCode = null, verifiedContextTokens = 5632)

    @Test fun queuedSleepingModelWakesOnceAndTheNextSendReusesItsExactQualification() = runTest {
        var observed = sleeping
        var loads = 0
        val readiness = mockk<ReasoningCapabilityRepository>()
        coEvery { readiness.check(profile, mode, false) } answers { observed }
        val preparation = GatewayModelPreparationRepository(
            discover = { GatewayCapabilities(features = mapOf("model_preparation_v1" to JsonPrimitive(true))) },
            check = { selected, requested ->
                assertEquals(profile, selected)
                assertEquals(mode, requested)
                observed
            },
            load = { selected, operation ->
                loads++
                observed = loaded
                GatewayModelPreparationReceipt("gptmobile.gateway.model-preparation.v1", operation, selected.model, "WAITING_FOR_MODEL", "DISPATCHED", true, 1, "LOADING")
            },
            now = { 1L },
            pause = {},
            maxPolls = 1
        )
        repeat(2) {
            val result = readiness.checkForSelectedSend(profile, mode, preparation, forceRefresh = false, validateSelection = {})
            assertEquals(loaded, result)
            assertTrue(result.permitsRequest)
        }
        assertEquals(1, loads)
    }

    @Test fun revokedSelectionStopsBeforeLoadingAndAMismatchedObservationCannotWake() = runTest {
        val readiness = mockk<ReasoningCapabilityRepository>()
        val preparation = mockk<GatewayModelPreparationRepository>()
        coEvery { readiness.check(profile, mode, false) } returns sleeping
        assertTrue(runCatching { readiness.checkForSelectedSend(profile, mode, preparation, false, { error("profile changed") }) }.isFailure)
        val other = sleeping.copy(model = "different")
        coEvery { readiness.check(profile, mode, false) } returns other
        assertEquals(other, readiness.checkForSelectedSend(profile, mode, preparation, false, { error("wrong target") }))
        io.mockk.confirmVerified(preparation)
    }
}
