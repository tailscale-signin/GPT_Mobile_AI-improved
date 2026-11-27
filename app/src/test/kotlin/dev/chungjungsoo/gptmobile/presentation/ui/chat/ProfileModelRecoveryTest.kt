package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityObservation
import dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityStatus
import dev.chungjungsoo.gptmobile.data.context.ReasoningMode
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileModelRecoveryTest {
    private val profile = PlatformV2(uid = "selected", name = "Fixture", compatibleType = ClientType.LLAMA, model = "exact-model", apiUrl = "https://fixture.invalid/v1", enabled = true)
    private val request = ReasoningRecoveryRequest(profile, ReasoningMode.OFF, requireRouteReady = true)
    private val sleeping = ReasoningCapabilityObservation(profile.uid, profile.model, ReasoningMode.OFF, ReasoningCapabilityStatus.UNVERIFIED, "configuration", "runtime", 1000, "SELECTED_MODEL_NOT_LOADED", false, false)
    private val ready = sleeping.copy(status = ReasoningCapabilityStatus.SUPPORTED, safeReasonCode = null, routeVerified = true, supportVerified = true)

    @Test fun explicitModelRepairPreparesOnceAndRequiresFreshQualificationWithoutChangingOff() = runTest {
        var reads = 0
        var preparations = 0
        val result = recheckProfileModel(request, {}, { if (reads++ == 0) sleeping else ready }, { preparations++ })
        assertEquals(2, reads)
        assertEquals(1, preparations)
        assertEquals(ReasoningMode.OFF, result?.requested)
        assertEquals(ready, result)
    }

    @Test fun reasoningOnlyRecheckAndUnknownRouteCannotStartAModelLoad() = runTest {
        var preparations = 0
        assertEquals(sleeping, recheckProfileModel(request.copy(requireRouteReady = false), {}, { sleeping }, { preparations++ }))
        val unrecognized = sleeping.copy(safeReasonCode = "REASONING_CAPABILITY_UNVERIFIED", routeVerified = true)
        assertEquals(unrecognized, recheckProfileModel(request, {}, { unrecognized }, { preparations++ }))
        assertEquals(0, preparations)
    }

    @Test fun selectionChangeDuringCheckCannotLoadOrQualifyTheReplacement() = runTest {
        var current = true
        var preparations = 0
        var cancelled = false
        try {
            recheckProfileModel(request, { if (!current) throw CancellationException("Selection changed") }, {
                current = false
                sleeping
            }, { preparations++ })
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(0, preparations)
    }

    @Test fun failedPreparationCannotTurnLoadedMetadataIntoVerifiedReasoningOff() = runTest {
        val loadedWithoutControl = sleeping.copy(routeVerified = true, safeReasonCode = "REASONING_CAPABILITY_UNVERIFIED")
        var reads = 0
        val result = recheckProfileModel(request, {}, { if (reads++ == 0) sleeping else loadedWithoutControl }, {})
        assertEquals(2, reads)
        assertFalse(requireNotNull(result).permitsRequest)
        assertEquals(ReasoningMode.OFF, result.requested)
    }
}
