package dev.chungjungsoo.gptmobile.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEnrichmentReadinessTest {
    @Test fun workerSuccessForDeferredIndexingIsNotSemanticReadiness() {
        assertFalse(memoryEnrichmentReady("SUCCEEDED", "WAITING_FOR_MODEL", false))
        assertFalse(memoryEnrichmentReady("SUCCEEDED", "WAITING_FOR_MODEL", true))
        assertFalse(memoryEnrichmentReady("SUCCEEDED", null, false))
        assertFalse(memoryEnrichmentReady("FAILED", "SUCCESS", true))
        assertTrue(memoryEnrichmentReady("SUCCEEDED", "SUCCESS", true))
        assertTrue(memoryEnrichmentReady("SUCCEEDED", "PROCESSED_NO_NEW_FACTS", true))
        assertFalse(memoryEnrichmentReady("SUCCEEDED", "SKIPPED", false))
    }

    @Test fun deferredAndRetriedPrivateWorkKeepsAnExplicitLocalOwnerReceipt() {
        val initial = MemoryEnrichmentProvenance.observed(11, "private:chat:11")
        val retried = MemoryEnrichmentProvenance.observed(11, "private:chat:11")
        assertEquals(initial, retried)
        assertEquals(11, initial.ownerChatId)
        assertEquals("PRIVATE_CONVERSATION", initial.scopeClass)
        assertTrue(initial.diagnosticFields().contains("processingDestination=ON_DEVICE"))
        assertEquals("PRIVATE_UNSHARED", MemoryEnrichmentProvenance.observed(11, "private:unshared").scopeClass)
        val project = MemoryEnrichmentProvenance.observed(12, "project:Private project name")
        assertEquals("PROJECT", project.scopeClass)
        assertFalse(project.diagnosticFields().contains("Private project name"))
    }
}
