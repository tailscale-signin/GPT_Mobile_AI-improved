package dev.chungjungsoo.gptmobile.data.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ResearchEvidenceTransferTest {
    @Test fun unrelatedCatalogDifferencesDoNotExcludeReceiptedPublicEvidence() {
        val public = ResearchSource("S1", "https://example.org/study", "Study", "Read", "A captured public passage.", publicCapture = true, retrievalReceipt = "native-read-1")
        val private = public.copy(id = "S2", url = "https://example.org/private", publicCapture = false, passage = "Private passage.")
        val snapshots = authorizedResearchTransfer(listOf(ResearchSession("worker-run", 1, ResearchSnapshot("task", sources = listOf(public, private), claims = listOf(ResearchClaim("private claim", "S2", private.passage))), "turn", "worker-catalog")), "writer-catalog")
        assertEquals(listOf("S1"), snapshots.single().sources.map { it.id })
        assertFalse(snapshots.single().claims.any { it.sourceId == "S2" })
    }

    @Test fun publicLookingUrlWithoutRetrievalProvenanceRemainsRestricted() {
        val source = ResearchSource("S1", "https://example.org/private", "Private", "Read", "Restricted captured material.")
        val result = authorizedResearchTransfer(listOf(ResearchSession("run", 1, ResearchSnapshot("task", sources = listOf(source)), authority = "account-a")), "account-b")
        assertEquals(0, result.single().sources.size)
    }
}
