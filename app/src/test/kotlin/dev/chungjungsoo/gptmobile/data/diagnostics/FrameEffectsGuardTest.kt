package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameEffectsGuardTest {
    @Test fun sustainedStallsReduceEffectsAndHealthyWindowsRestoreThemAfterCooldown() {
        val guard = FrameEffectsGuard()
        guard.observe(120, 30, 3, 5000)
        assertFalse(guard.reduced.value)
        guard.observe(120, 30, 3, 10000)
        assertTrue(guard.reduced.value)
        guard.observe(120, 0, 0, 15000)
        guard.observe(120, 0, 0, 20000)
        assertTrue(guard.reduced.value)
        guard.observe(120, 0, 0, 40000)
        assertFalse(guard.reduced.value)
    }

    @Test fun isolatedSpikesOrIncompleteWindowsDoNotDisableEffects() {
        val guard = FrameEffectsGuard()
        guard.observe(119, 119, 3, 0)
        guard.observe(120, 30, 3, 5000)
        guard.observe(120, 0, 0, 10000)
        guard.observe(120, 30, 3, 15000)
        assertFalse(guard.reduced.value)
        guard.observe(120, 30, 3, 50000)
        assertFalse(guard.reduced.value)
    }

    @Test fun severeStallReducesEffectsBeforeACompleteWindowAndRequiresHealthyRecovery() {
        val guard = FrameEffectsGuard()
        guard.observeCriticalStall(583_960_000, 1000)
        assertTrue(guard.reduced.value)
        guard.observe(120, 0, 0, 6000)
        guard.observe(120, 0, 0, 11000)
        assertTrue(guard.reduced.value)
        guard.observe(120, 0, 0, 31000)
        assertFalse(guard.reduced.value)
    }

    @Test fun ordinaryMissedDeadlineDoesNotEnableTheEmergencyFallback() {
        val guard = FrameEffectsGuard()
        guard.observeCriticalStall(25_000_000, 1000)
        assertFalse(guard.reduced.value)
        guard.observeCriticalStall(250_000_000, 2000)
        guard.observeCriticalStall(300_000_000, 20000)
        guard.observe(120, 0, 0, 25000)
        guard.observe(120, 0, 0, 35000)
        assertTrue(guard.reduced.value)
        guard.observe(120, 0, 0, 50000)
        assertFalse(guard.reduced.value)
    }
}
