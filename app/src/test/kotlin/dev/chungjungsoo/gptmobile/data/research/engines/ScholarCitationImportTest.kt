package dev.chungjungsoo.gptmobile.data.research.engines

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScholarCitationImportTest {
    @Test fun biomedicalLinksAndPmcPrefixesResolveAsIdentifiers() {
        assertEquals("pmid:42", ScholarCitationImport.parse("https://pubmed.ncbi.nlm.nih.gov/42/").single().identifier)
        assertEquals("pmcid:PMC42", ScholarCitationImport.parse("PMCID: PMC42").single().identifier)
        assertEquals("pmcid:PMC42", ScholarCitationImport.parse("https://pmc.ncbi.nlm.nih.gov/articles/PMC42/").single().identifier)
    }

    @Test fun nestedBibtexAndRisImportsRetainIdentityAndDoNotClaimVerification() {
        val bib = ScholarCitationImport.parse("""@article{a, title={A {nested} title}, doi={10.1234/test}, author={Melo, E}}""").single()
        assertEquals("A nested title", bib.title)
        assertEquals("doi:10.1234/test", bib.identifier)
        assertFalse(bib.verified)
        val ris = ScholarCitationImport.parse("TY  - JOUR\nTI  - A title\nDO  - 10.1234/test\nER  -\n").single()
        assertEquals(bib.identifier, ris.identifier)
    }

    @Test fun userInputCannotImportScriptOrPrivateCredentialUrl() {
        for (input in listOf("<script>alert(1)</script>", "https://127.0.0.1/private", "https://secret@example.org/article", "https://example.org/article?token=secret", "@article{x,title={broken}")) {
            assertTrue(input, runCatching { ScholarCitationImport.parse(input) }.isFailure)
        }
        assertTrue(runCatching { ScholarCitationImport.parse("x".repeat(262145)) }.isFailure)
        assertTrue(ScholarCitationImport.scholarUrl("a & b").endsWith("q=a+%26+b"))
    }
}
