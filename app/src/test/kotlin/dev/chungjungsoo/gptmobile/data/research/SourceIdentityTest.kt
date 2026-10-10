package dev.chungjungsoo.gptmobile.data.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceIdentityTest {
    @Test fun canonicalUrlDropsTrackingAndDefaultPortButKeepsMeaningfulQuery() {
        assertEquals(
            "https://example.org/article?id=4",
            SourceIdentity.canonicalResearchUrl("HTTPS://WWW.Example.org:443/article?utm_source=search&id=4#section")
        )
        assertNotEquals(
            SourceIdentity.canonicalResearchUrl("https://example.org/article?id=4"),
            SourceIdentity.canonicalResearchUrl("https://example.org/article?id=5")
        )
    }

    @Test fun doiAndPubmedIdentitiesAreNormalizedAndTakePrecedence() {
        val doi = SourceIdentity.create("https://publisher.example/item", doi = "https://doi.org/10.1234/ABC.XY")
        assertEquals("doi:10.1234/abc.xy", doi.equivalenceKey)
        assertEquals("pmid:12345", SourceIdentity.create("https://pubmed.ncbi.nlm.nih.gov/12345", pmid = "12345").equivalenceKey)
    }

    @Test fun unsafeOrInvalidIdentifiersAreIgnored() {
        val identity = SourceIdentity.create("file:///etc/passwd", doi = "not-a-doi", pmid = "1 OR 1=1")
        assertEquals("", identity.canonicalUrl)
        assertEquals("", identity.doi)
        assertEquals("", identity.pmid)
        assertTrue(identity.equivalenceKey.isEmpty())
    }
}
