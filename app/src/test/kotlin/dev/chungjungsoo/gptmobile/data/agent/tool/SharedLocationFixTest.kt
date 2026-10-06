package dev.chungjungsoo.gptmobile.data.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedLocationFixTest {
    private val location = DeviceLocation(43.65, -79.38, 10f, null, 1L, "test")

    @Test fun fixExpiresAtFiveMinutesWithoutExtendingOnReads() {
        val cache = SharedLocationFix()
        cache.save(location, 1_000, true)
        assertEquals(location, cache.get(1_000, true))
        assertEquals(location, cache.get(300_999, true))
        assertNull(cache.get(301_000, true))
    }

    @Test fun changedPrecisionAndClockResetClearTheFix() {
        val cache = SharedLocationFix()
        cache.save(location, 1_000, true)
        assertNull(cache.get(1_001, false))
        cache.save(location, 2_000, false)
        assertNull(cache.get(1_999, false))
        cache.save(location, 3_000, false)
        cache.clear()
        assertNull(cache.get(3_001, false))
    }
}
