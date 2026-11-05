package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameEffectsGuardTest {
    @Test fun sustainedStallsReduceEffectsUntilTheCooldownEnds() {
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

    @Test fun severeStallReducesEffectsBeforeACompleteWindowAndKeepsTheCooldown() {
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

    @Test fun firstDrawsRemainDiagnosticWithoutTriggeringEitherPressurePath() {
        val guard = FrameEffectsGuard()
        val observation = FrameEffectsObservation(guard, 0)
        assertTrue(observation.observe(800_000_000, 16_666_667, firstDraw = true, nowMs = 1000))
        repeat(240) {
            assertTrue(observation.observe(150_000_000, 16_666_667, firstDraw = true, nowMs = 5000L + it * 50))
        }
        assertFalse(guard.reduced.value)
        observation.observe(250_000_000, 16_666_667, firstDraw = false, nowMs = 20000)
        assertTrue(guard.reduced.value)
    }

    @Test fun cooldownRecoversWithoutAnyFurtherFrames() {
        val guard = FrameEffectsGuard()
        guard.observeCriticalStall(250_000_000, 1000)
        assertEquals(30_000L, guard.recoverAfterCooldown(1000))
        assertEquals(1L, guard.recoverAfterCooldown(30999))
        assertTrue(guard.reduced.value)
        assertNull(guard.recoverAfterCooldown(31000))
        assertFalse(guard.reduced.value)
    }

    @Test fun anOlderRecoveryCheckCannotClearANewerStall() {
        val guard = FrameEffectsGuard()
        guard.observeCriticalStall(250_000_000, 1000)
        guard.observeCriticalStall(300_000_000, 20000)
        assertEquals(19_000L, guard.recoverAfterCooldown(31000))
        assertTrue(guard.reduced.value)
        assertNull(guard.recoverAfterCooldown(50000))
        assertFalse(guard.reduced.value)
    }

    @Test fun overloadExtendsTheCooldownAndRecoveryClearsOldWindowHistory() {
        val guard = FrameEffectsGuard()
        guard.observeCriticalStall(250_000_000, 1000)
        guard.observe(120, 30, 3, 20000)
        assertEquals(19_000L, guard.recoverAfterCooldown(31000))
        assertTrue(guard.reduced.value)
        assertNull(guard.recoverAfterCooldown(50000))
        guard.observe(120, 30, 3, 55000)
        assertFalse(guard.reduced.value)
        guard.observe(120, 30, 3, 60000)
        assertTrue(guard.reduced.value)
    }

    @Test fun interactiveFramesNeedTwoCompleteRepresentativeWindows() {
        val guard = FrameEffectsGuard()
        val observation = FrameEffectsObservation(guard, 0)
        repeat(119) { observation.observe(30_000_000, 16_666_667, firstDraw = false, nowMs = 4999) }
        assertFalse(guard.reduced.value)
        observation.observe(30_000_000, 16_666_667, firstDraw = false, nowMs = 5000)
        assertFalse(guard.reduced.value)
        repeat(119) { observation.observe(30_000_000, 16_666_667, firstDraw = false, nowMs = 9999) }
        assertFalse(guard.reduced.value)
        observation.observe(30_000_000, 16_666_667, firstDraw = false, nowMs = 10000)
        assertTrue(guard.reduced.value)
    }

    @Test fun stoppedCallbacksAreIgnoredAndTheNextObservationStartsWithFreshSamples() {
        val guard = FrameEffectsGuard()
        val oldObservation = FrameEffectsObservation(guard, 0)
        repeat(120) { oldObservation.observe(30_000_000, 16_666_667, firstDraw = false, nowMs = 5000) }
        repeat(119) { oldObservation.observe(30_000_000, 16_666_667, firstDraw = false, nowMs = 10000) }
        oldObservation.stop()
        assertFalse(oldObservation.observe(300_000_000, 16_666_667, firstDraw = false, nowMs = 10000))
        assertFalse(guard.reduced.value)

        val resumedObservation = FrameEffectsObservation(guard, 10000)
        repeat(119) { resumedObservation.observe(30_000_000, 16_666_667, firstDraw = false, nowMs = 15000) }
        assertFalse(guard.reduced.value)
        resumedObservation.observe(30_000_000, 16_666_667, firstDraw = false, nowMs = 15000)
        assertTrue(guard.reduced.value)
    }
}
