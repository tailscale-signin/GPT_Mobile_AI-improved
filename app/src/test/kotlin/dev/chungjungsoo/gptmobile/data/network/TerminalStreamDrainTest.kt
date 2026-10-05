package dev.chungjungsoo.gptmobile.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalStreamDrainTest {
    @Test
    fun `heartbeats and repeated terminal markers never extend the first deadline`() {
        var now = 0L
        val drain = TerminalStreamDrain(1000L) { now }
        assertNull(drain.remainingMillis())
        drain.markTerminal()
        assertEquals(1000L, drain.remainingMillis())
        now = 600_000_000L
        drain.markTerminal()
        assertEquals(400L, drain.remainingMillis())
        now = 1_500_000_000L
        assertEquals(0L, drain.remainingMillis())
    }
}
