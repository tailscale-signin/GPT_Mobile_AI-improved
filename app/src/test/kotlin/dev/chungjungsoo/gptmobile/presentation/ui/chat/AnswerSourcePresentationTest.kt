package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerSourcePresentationTest {
    @Test fun numericVectorsAreNotMistakenForReferences() {
        val answer = "Vectors [1] and [1, 2, 3] are data."
        assertEquals(answer, answerWithoutSourceLists(answer))
    }

    @Test fun boldSourceHeadingsAndLinkedCitationIdsStayInPickerWhileCommandsStayReadable() {
        val answer = "Run `curl https://example.org/api`. [S1](https://example.org/docs)\n\n**Sources:**\n- [Docs](https://example.org/docs)"
        assertEquals("Run `curl https://example.org/api`.", answerWithoutSourceLists(answer))
    }

    @Test fun inlineReferencesKeepReadableLabelsAndIndentedCodeRemainsUnchanged() {
        assertEquals("Read the documentation.", answerWithoutSourceLists("Read the [documentation](https://example.org/docs). [1]"))
        val code = "    Sources:\n    https://example.org/code\n    value[1]"
        assertEquals(code, answerWithoutSourceLists(code))
    }

    @Test fun referencesMoveOutOfAnswerWithoutRemovingFollowingContent() {
        val answer = "A grounded result. [S1]\n\n## Sources\n- [Documentation](https://example.org/doc)\n\n## Next steps\nKeep working."
        val display = answerWithoutSourceLists(answer)
        assertTrue(display.contains("A grounded result."))
        assertTrue(display.contains("Keep working."))
        assertFalse(display.contains("https://") || display.contains("[S1]"))
        assertEquals(1, collectChatSources(answer, emptyList()).sources.size)
    }

    @Test fun codeAndSubstantiveSourceHeadingsRemainIntact() {
        val code = "```text\nSources:\nhttps://example.org\nvalue[1]\n```"
        assertEquals(code, answerWithoutSourceLists(code))
        val answer = "## Energy sources\nSolar and wind.\n\n## References\nC++ references bind to objects."
        assertEquals(answer, answerWithoutSourceLists(answer))
    }

    @Test fun nextStepsAfterReferenceListRemainVisibleWithoutAnotherHeading() {
        val answer = "A grounded result.\n\n## Sources\n- [Documentation](https://example.org/doc)\n\n**Next steps:**\nKeep working on the implementation."

        val display = answerWithoutSourceLists(answer)

        assertTrue(display.contains("**Next steps:**"))
        assertTrue(display.contains("Keep working on the implementation."))
        assertFalse(display.contains("https://"))
    }

    @Test fun unknownSitesShareOtherAndDistinctPagesRemainVisible() {
        val sources = listOf(ChatSource("https://example.org/a", "A", "example.org"), ChatSource("https://example.org/b", "B", "example.org"), ChatSource("https://github.com/repo", "Repo", "github.com"))
        assertEquals(2, filterChatSources(sources, "other").size)
        assertEquals(1, filterChatSources(sources, "github").size)
        assertEquals("github.com", filterChatSources(sources, null).first().host)
    }
}
