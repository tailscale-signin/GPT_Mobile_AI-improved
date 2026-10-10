package dev.chungjungsoo.gptmobile.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryEnrichmentWorkerTest {
    @Test fun `generation retry limits exclude model prerequisites and bound invalid output`() {
        assertEquals(0, enrichmentRetryLimit("NO_LOADED_MODEL"))
        assertEquals(0, enrichmentRetryLimit("SOURCE_CHANGED"))
        assertEquals(1, enrichmentRetryLimit("INVALID_OUTPUT"))
        assertEquals(3, enrichmentRetryLimit("RUNTIME_BUSY"))
        assertEquals(3, enrichmentRetryLimit("INFERENCE_TIMEOUT"))
    }

    @Test fun `empty observations are valid but arbitrary JSON is not enrichment`() {
        assertNotNull(MemoryEnrichmentWorker.parse("""{"observations":[]}"""))
        assertNull(MemoryEnrichmentWorker.parse("{}"))
        assertNull(MemoryEnrichmentWorker.parse("""{"observations":"done"}"""))
    }
}
