package dev.chungjungsoo.gptmobile.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTrimPolicyTest {
    @Test fun smallReadinessCataloguesSurviveVisibilityAndModerateSignals() {
        for (level in listOf(0, 5, 20)) assertEquals(null, MemoryTrimPolicy.readinessEvictionReason(level))
        for (level in listOf(10, 15)) assertEquals("RUNNING_MEMORY_PRESSURE", MemoryTrimPolicy.readinessEvictionReason(level))
        for (level in listOf(40, 60, 80)) assertEquals("BACKGROUND_MEMORY_TRIM", MemoryTrimPolicy.readinessEvictionReason(level))
        // Large engine weights still release at moderate pressure under their existing policy.
        assertTrue(MemoryTrimPolicy.releaseImmediately(5))
    }

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
