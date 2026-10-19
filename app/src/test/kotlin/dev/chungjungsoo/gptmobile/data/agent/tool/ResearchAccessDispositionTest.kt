package dev.chungjungsoo.gptmobile.data.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Test

class ResearchAccessDispositionTest {
    @Test fun successfulTransportDoesNotEstablishReadableFullText() {
        assertEquals(ResearchAccessDisposition.CHALLENGE, classifyResearchAccess("Checking your browser before accessing pubmed.ncbi.nlm.nih.gov. Please enable cookies to continue."))
        assertEquals(ResearchAccessDisposition.AUTH_REQUIRED, classifyResearchAccess("Please sign in to continue to the requested article. Authentication required."))
        assertEquals(ResearchAccessDisposition.PREVIEW, classifyResearchAccess("Abstract: preliminary observations. This is a preview of subscription content; access through your institution."))
        assertEquals(ResearchAccessDisposition.READABLE, classifyResearchAccess("Methods: measured a synthetic sample. Results: uncertain association. Limitations: incomplete follow-up."))
    }
}
