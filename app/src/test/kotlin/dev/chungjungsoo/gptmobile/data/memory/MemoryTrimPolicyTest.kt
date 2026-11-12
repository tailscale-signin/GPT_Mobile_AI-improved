package dev.chungjungsoo.gptmobile.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTrimPolicyTest {
    @Test fun backgroundCleanupIsDistinguishedFromRunningPressureWithoutChangingReleasePolicy() {
        assertTrue(MemoryTrimPolicy.releaseImmediately(40))
        assertEquals("background_trim", MemoryTrimPolicy.releaseReason(true, 40, 0))
        assertFalse(MemoryTrimPolicy.releaseImmediately(20))
        for (level in listOf(5, 10, 15, 60, 80)) {
            assertTrue(MemoryTrimPolicy.releaseImmediately(level))
            assertEquals("memory_pressure", MemoryTrimPolicy.releaseReason(true, level, 0))
        }
    }

    @Test fun explicitAndIdleReleaseNeverClaimMemoryPressure() {
        assertEquals("explicit_release", MemoryTrimPolicy.releaseReason(false, null, 0))
        assertEquals("idle_timeout", MemoryTrimPolicy.releaseReason(false, null, 180000))
        assertEquals("memory_pressure", MemoryTrimPolicy.releaseReason(true, null, 0))
    }
}
