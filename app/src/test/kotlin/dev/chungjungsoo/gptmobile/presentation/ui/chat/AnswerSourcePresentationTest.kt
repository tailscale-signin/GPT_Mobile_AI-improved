package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerSourcePresentationTest {
    @Test fun citationsMoveOutOfProseAndSourcesKeepOriginalEvidence() {
        val original = "A useful [finding](https://nasa.gov/science) [S1].\n\n## Sources\n1. [NASA](https://nasa.gov/science)"
        assertEquals("A useful finding.", answerWithoutSourceListing(original))
        assertEquals("https://nasa.gov/science", collectChatSources(original, emptyList()).sources.single().url)
    }

    @Test fun codeAndSubsequentAnswerSectionsRemainIntact() {
        val original = "Use `array[1]` or array[1].\n```text\nSources\n[1]: https://example.org\n```\n## References\n- [Source](https://example.org)\n## Next steps\nRun the example."
        val visible = answerWithoutSourceListing(original)
        assertTrue(visible.contains("Use `array[1]` or array[1]."))
        assertTrue(visible.contains("Sources\n[1]: https://example.org"))
        assertTrue(visible.endsWith("## Next steps\nRun the example."))
        assertFalse(visible.contains("## References"))
    }

    @Test fun sourceSectionsStayHiddenAcrossToolSeparatedSegments() {
        val projection = AnswerSourceProjection()
        assertEquals("Answer.", projection.project("Answer.\n\n**Sources:**"))
        assertEquals("", projection.project("1. [Reference](https://example.org)"))
        assertEquals("## Recommendation\nProceed.", projection.project("## Recommendation\nProceed."))
    }

    @Test fun bareSourceListsAndInlineHeadingsAreHiddenButCodeRemainsVisible() {
        assertEquals("Answer.", answerWithoutSourceListing("Answer.\n- https://example.org/evidence"))
        assertEquals("Answer.", answerWithoutSourceListing("Answer.\n**Sources:** [Reference](https://example.org/evidence)"))
        assertEquals("`https://example.org`", answerWithoutSourceListing("`https://example.org`"))
    }

    @Test fun knownSitesSortBeforeOtherAndEachFilterKeepsEveryPage() {
        val sources = listOf(
            ChatSource("https://unknown.test/a", "Unknown", "unknown.test"),
            ChatSource("https://nasa.gov/b", "B", "nasa.gov"),
            ChatSource("https://nasa.gov/a", "A", "nasa.gov")
        )
        assertEquals(listOf("nasa", "other"), sourceFilters(sources))
        assertEquals(listOf("A", "B", "Unknown"), sortedChatSources(sources).map { it.title })
        assertEquals(2, sortedChatSources(sources, "nasa").size)
        assertEquals("Unknown", sortedChatSources(sources, "other").single().title)
    }
}
