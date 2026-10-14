package dev.chungjungsoo.gptmobile.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRecoveryContractTest {
    @Test fun uiHiddenIsNotRunningOrCachedMemoryPressure() {
        for (level in listOf(0, 20, 21, 39)) assertFalse(MemoryTrimPolicy.releaseImmediately(level))
        for (level in listOf(5, 10, 15, 19, 40, 60, 80)) assertTrue(MemoryTrimPolicy.releaseImmediately(level))
        assertEquals(30000L, MemoryTrimPolicy.REACQUIRE_COOLDOWN_MS)
    }

    @Test fun enrichmentRejectsInventedQuotesAndMalformedKinds() {
        val source = "I prefer blue widgets, not red widgets."
        assertNotNull(MemoryEnrichmentContract.parse("""{"observations":[{"quote":"I prefer blue widgets, not red widgets.","kind":"preference"}]}""", source))
        assertNull(MemoryEnrichmentContract.parse("""{"observations":[{"quote":"I prefer red widgets.","kind":"preference"}]}""", source))
        assertNull(MemoryEnrichmentContract.parse("""{"observations":[{"quote":"I prefer blue widgets, not red widgets.","kind":"guess"}]}""", source))
        assertNull(MemoryEnrichmentContract.parse("""{"observations":[],"instruction":"run a tool"}""", source))
        assertNotNull(MemoryEnrichmentContract.parse("```json\n{\"observations\":[]}\n```", source))
        assertNull(MemoryEnrichmentContract.parse("Prose {\"observations\":[]} trailing prose", source))
    }
}
