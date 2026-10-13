package dev.chungjungsoo.gptmobile.data.research.academic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetedArticleLinksTest {
    @Test fun onlyExplicitSameOriginArticleLeadsAreFollowed() {
        val html = """<a href="/full/1">Full text</a><a href="https://other.example/full">Full text</a><a href="/reference">References</a><a href="/download.pdf">Full text</a><a href="/full/2">Read article</a><a href="/full/3">Full text</a>"""
        assertEquals(listOf("https://journal.example/full/1", "https://journal.example/full/2"), targetedArticleLinks(html, "https://journal.example/abstract", ""))
    }

    @Test fun refusalIdentityMismatchAndAlreadyReadableTextStopTraversal() {
        val lead = "<a href='/full'>Full text</a>"
        assertTrue(targetedArticleLinks("Access denied $lead", "https://journal.example/abstract", "").isEmpty())
        assertTrue(targetedArticleLinks("<meta name='citation_doi' content='10.1234/other'>$lead", "https://journal.example/abstract", "10.1234/requested").isEmpty())
        assertTrue(targetedArticleLinks("<article>${"methods ".repeat(100)}</article>$lead", "https://journal.example/abstract", "").isEmpty())
    }
}
