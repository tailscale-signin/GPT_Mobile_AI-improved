package dev.chungjungsoo.gptmobile.data.localruntime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMemoryBudgetTest {
    @Test fun logValuesShowExactShortfallAndReserveRecovery() {
        val budget = LocalMemoryBudget(4_123_590_656L, 4_294_967_296L, 6_505_603_072L)
        assertTrue(budget.hasShortfall)
        assertEquals(8_418_557_952L, budget.totalBytes)
        assertEquals(1_912_954_880L, budget.shortfallBytes)
        assertTrue(budget.fitsWithReserve(768L * 1024 * 1024))
    }

    @Test fun overflowCannotAdmitAnImpossibleAllocation() {
        val budget = LocalMemoryBudget(Long.MAX_VALUE, 1, Long.MAX_VALUE)
        assertTrue(budget.hasShortfall)
        assertEquals(Long.MAX_VALUE, budget.totalBytes)
        assertEquals(1L, budget.shortfallBytes)
        assertFalse(budget.fitsWithReserve(1))
        assertFalse(LocalMemoryBudget(Long.MAX_VALUE, 0, Long.MAX_VALUE).hasShortfall)
    }

    @Test fun exactBoundaryIsAdmittedButOneByteLessIsRejected() {
        assertFalse(LocalMemoryBudget(100, 200, 300).hasShortfall)
        assertTrue(LocalMemoryBudget(100, 200, 299).hasShortfall)
        assertTrue(LocalMemoryBudget(0, 301, 300).hasShortfall)
    }

    @Test fun unknownMemoryIsNotMisrepresentedAsKnownHeadroom() {
        val budget = LocalMemoryBudget(100, 200, null)
        assertFalse(budget.hasShortfall)
        assertNull(budget.shortfallBytes)
        assertFalse(budget.fitsWithReserve(0))
    }

    @Test fun negativeInputCannotProduceAnOverflowOrNegativeShortfall() {
        assertFalse(LocalMemoryBudget(-1, -1, 0).hasShortfall)
        assertEquals(0L, LocalMemoryBudget(0, 0, -1).shortfallBytes)
        assertTrue(LocalMemoryBudget(1, 0, -1).hasShortfall)
    }

    @Test fun generationFailureRetainsItsPhaseAndLoadedEngineIdentity() {
        val failure = LocalMemoryEstimate(64L * 1024 * 1024, 768L * 1024 * 1024, 100L, 4224)
            .rejection("No generation headroom", stage = "generate", nativeLoadAttempted = true)
        assertTrue(failure.isLocalMemoryAdmissionFailure())
        assertTrue(failure.message.orEmpty().contains("stage=generate"))
        assertTrue(failure.message.orEmpty().contains("nativeLoadAttempted=true"))
        assertFalse(failure.message.orEmpty().contains("engine was not loaded"))
    }

    @Test fun resettingReserveIsNotRecommendedWhenTheModelStillWillNotFit() {
        val failure = LocalMemoryEstimate(4_123_590_656L, 4_294_967_296L, 1_000_000_000L, 4224).rejection("Low memory")
        assertFalse(failure.message.orEmpty().contains("Use recommended 768 MB"))
        assertTrue(failure.message.orEmpty().contains("recommendedReserveFits=false"))
    }

    @Test fun heatHasPriorityOverMemoryAndOverflowIsClassifiedAsMemory() {
        val admission = LocalWorkloadAdmission { 0L }
        assertEquals(LocalAdmissionReason.THERMAL, admission.evaluate(DeviceHardwareState(thermalState = DeviceThermalState.SEVERE), 0, 100, 100, false)?.reason)
        assertEquals(LocalAdmissionReason.MEMORY, admission.evaluate(DeviceHardwareState(), Long.MAX_VALUE, Long.MAX_VALUE, 1, false)?.reason)
    }
}
