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
}
