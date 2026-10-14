package dev.chungjungsoo.gptmobile.data.localruntime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMemoryEstimateTest {
    @Test
    fun oversizedConfiguredReserveExplainsThatNativeLoadingWasNeverAttempted() {
        val estimate = LocalMemoryEstimate(
            requiredBytes = 4_123_590_656L,
            reserveBytes = 4_294_967_296L,
            availableBytes = 6_505_603_072L,
            contextTokens = 4_224
        )

        val rejection = estimate.rejection("Not enough available memory")
        assertTrue(rejection.message.orEmpty().contains("MEMORY_ADMISSION_REJECTED"))
        assertTrue(rejection.message.orEmpty().contains("nativeLoadAttempted=false"))
        assertTrue(rejection.message.orEmpty().contains("4096 MB"))
        assertTrue(rejection.message.orEmpty().contains("Use recommended 768 MB"))
        assertTrue(rejection.isLocalMemoryAdmissionFailure())
    }

    @Test
    fun defaultReserveRecommendsFreeingMemoryInsteadOfPointlesslyResettingTheSetting() {
        val estimate = LocalMemoryEstimate(
            requiredBytes = 4_123_590_656L,
            reserveBytes = 768L * 1024 * 1024,
            availableBytes = 4_300_000_000L,
            contextTokens = 4_224
        )

        val rejection = estimate.rejection("Not enough available memory")
        assertFalse(rejection.message.orEmpty().contains("tap 'Use recommended 768 MB'"))
        assertTrue(rejection.message.orEmpty().contains("choose a smaller compatible export"))
    }

    @Test
    fun wrappedAdmissionRejectionIsNonRetryableForBenchmarkComparison() {
        val native = LocalMemoryAdmissionException("MEMORY_ADMISSION_REJECTED")
        val wrapped = IllegalStateException("Engine load failed", native)
        assertTrue(wrapped.isLocalMemoryAdmissionFailure())
        assertTrue(IllegalStateException("MEMORY_ADMISSION_REJECTED from IPC").isLocalMemoryAdmissionFailure())
        assertFalse(IllegalStateException("Temporary runtime busy").isLocalMemoryAdmissionFailure())
    }
}
