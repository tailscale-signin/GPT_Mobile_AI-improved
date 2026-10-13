package dev.chungjungsoo.gptmobile.data.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResearchResultCacheTest {
    @Test fun hitsDoNotExtendOriginalExpiry() {
        var time = 0L
        val cache = ResearchResultCache<String, String>(1000, 2) { time }
        cache["query"] = "old"
        time = 999
        assertEquals("old", cache["query"])
        time = 1000
        assertNull(cache["query"])
        cache["query"] = "fresh"
        assertEquals("fresh", cache["query"])
    }

    @Test fun capacityAndClockRollbackCannotReturnUnboundedOrFutureEntries() {
        var time = 10L
        val cache = ResearchResultCache<String, Int>(1000, 2) { time }
        cache["a"] = 1
        cache["b"] = 2
        cache["c"] = 3
        assertNull(cache["a"])
        assertEquals(2, cache["b"])
        time = 9
        assertNull(cache["c"])
    }
}
