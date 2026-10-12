package dev.chungjungsoo.gptmobile.data.localruntime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRequestLifecycleTest {
    @Test fun startupFailureIsNotProofOfNativeShutdown() {
        val lifecycle = NativeRequestLifecycle()
        lifecycle.enteringNative()
        val cause = IllegalStateException("template mismatch")
        assertTrue(lifecycle.terminal(NativeRequestLifecycle.Outcome.FAILED, cause))
        assertFalse(lifecycle.quiescent)
        assertFalse(lifecycle.terminal(NativeRequestLifecycle.Outcome.COMPLETED, acknowledged = true))
        assertTrue(lifecycle.quiescent)
        assertEquals(cause, lifecycle.failure)
        assertEquals(NativeRequestLifecycle.Outcome.FAILED, lifecycle.outcome)
    }

    @Test fun duplicateAndLateCallbacksCannotChangeTerminalWinner() {
        for (first in NativeRequestLifecycle.Outcome.entries) {
            val lifecycle = NativeRequestLifecycle()
            lifecycle.enteringNative()
            assertTrue(lifecycle.terminal(first))
            NativeRequestLifecycle.Outcome.entries.forEach { later ->
                assertFalse(lifecycle.terminal(later, acknowledged = true))
                assertEquals(first, lifecycle.outcome)
            }
        }
    }
}
