package dev.chungjungsoo.gptmobile.data.research.engines

import dev.chungjungsoo.gptmobile.data.research.academic.AcademicSearchRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PubMedEngineAdapterTest {
    @Test fun ncbiSearchIsIndependentAndExplicitlyCapsCoverage() {
        val url = PubMedEngineAdapter.searchUrl(AcademicSearchRequest("sleep & memory", 50, fromYear = 2020, toYear = 2026))
        assertTrue(url.startsWith("https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi?db=pubmed"))
        assertFalse(url.contains("europepmc"))
        assertTrue(url.contains("%26"))
        assertTrue(runCatching { PubMedEngineAdapter.searchUrl(AcademicSearchRequest("sleep", 50, page = 501)) }.isFailure)
        val page = PubMedEngineAdapter.parsePage("""{"esearchresult":{"count":"14000","idlist":["123","456"]}}""")
        assertEquals(14000L, page.total)
        assertEquals(listOf("123", "456"), page.ids)
    }

    @Test fun hydrationPreservesStructuredAbstractAndCollectiveAuthorsWithoutInventingPeerReview() {
        val xml = """<!DOCTYPE PubmedArticleSet SYSTEM "https://example.invalid/pubmed.dtd">
            <PubmedArticleSet><PubmedArticle><MedlineCitation><PMID>123</PMID><Article>
            <ArticleTitle>A sleep study</ArticleTitle><Journal><Title>Example Journal</Title><JournalIssue><PubDate><MedlineDate>2024 Winter</MedlineDate></PubDate></JournalIssue></Journal>
            <AuthorList><Author><CollectiveName>Study Group</CollectiveName></Author><Author><LastName>Melo</LastName><ForeName>E</ForeName></Author></AuthorList>
            <Abstract><AbstractText Label="RESULTS">Observed effect 3.2.</AbstractText><AbstractText Label="LIMITATIONS">Small sample.</AbstractText></Abstract>
            <PublicationTypeList><PublicationType>Journal Article</PublicationType></PublicationTypeList></Article>
            <MeshHeadingList><MeshHeading><DescriptorName>Sleep</DescriptorName></MeshHeading></MeshHeadingList></MedlineCitation>
            <PubmedData><ArticleIdList><ArticleId IdType="doi">10.1234/SLEEP</ArticleId><ArticleId IdType="pmc">PMC567</ArticleId></ArticleIdList></PubmedData>
            </PubmedArticle></PubmedArticleSet>"""
        val paper = PubMedEngineAdapter.parseRecords(xml).single()
        assertEquals("doi:10.1234/sleep", paper.paperId)
        assertEquals("123", paper.identifiers["pmid"])
        assertEquals("PMC567", paper.identifiers["pmcid"])
        assertEquals(listOf("Study Group", "E Melo"), paper.authors)
        assertEquals(2024, paper.year)
        assertEquals("2024 Winter", paper.publicationDate)
        assertTrue(paper.abstract.contains("LIMITATIONS: Small sample."))
        assertEquals("unknown", paper.reviewStatus)
        assertEquals("abstract_only", paper.sourceAccess)
    }

    @Test fun externalEntitiesAndMalformedIdentifiersAreRejectedBeforeHydration() {
        assertTrue(runCatching { PubMedEngineAdapter.parseRecords("<!ENTITY stolen SYSTEM 'file:///etc/passwd'><PubmedArticleSet/>") }.isFailure)
        assertTrue(runCatching { PubMedEngineAdapter.fetchUrl(listOf("1&api_key=bad")) }.isFailure)
        assertTrue(runCatching { PubMedEngineAdapter.parsePage("""{"error":"rate limited"}""") }.isFailure)
    }
}
