package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCpuSchedulingPolicyTest {
    @Test fun nativeCalibrationKeepsTheChosenModelIdentityWithoutOpeningNativeWeights() {
        val compact = TimedKokoroPhraseEngineFactory.forVariant(KokoroModelVariant.INT8)
        val original = TimedKokoroPhraseEngineFactory.forVariant(KokoroModelVariant.ORIGINAL_FP32)
        assertNotEquals(compact.identity, original.identity)
        assertTrue(original.identity.contains(KokoroModelVariant.ORIGINAL_FP32.modelSha256))
        assertSame(original, original.cpuCalibrationFactory)
        assertSame(compact, compact.cpuCalibrationFactory)
    }

    @Test fun explicitBenchmarkAndUserThreadsRemainFixedUnderPressure() {
        for (requested in 1..4) {
            assertEquals(requested, VoiceCpuSchedulingPolicy.threads(requested, 4, false, true, true))
        }
    }

    @Test fun autoCapsCalibratedThreadsWithoutIncreasingASlowerSelectedCount() {
        assertEquals(4, VoiceCpuSchedulingPolicy.threads(2, 4, true, false, false))
        assertEquals(2, VoiceCpuSchedulingPolicy.threads(4, 4, true, true, false))
        assertEquals(1, VoiceCpuSchedulingPolicy.threads(4, 1, true, true, false))
        assertEquals(1, VoiceCpuSchedulingPolicy.threads(4, 4, true, false, true))
        assertEquals(2, VoiceCpuSchedulingPolicy.threads(4, null, true, false, false))
    }

    @Test fun cpuHintTargetsUseTheAvailableReserveWithinBoundedPresentationBudgets() {
        assertEquals(5_000_000_000L, VoiceCpuSchedulingPolicy.targetNanos(true, 0))
        assertEquals(250_000_000L, VoiceCpuSchedulingPolicy.targetNanos(false, -1))
        assertEquals(800_000_000L, VoiceCpuSchedulingPolicy.targetNanos(false, 800))
        assertEquals(5_000_000_000L, VoiceCpuSchedulingPolicy.targetNanos(false, Long.MAX_VALUE))
    }
}
