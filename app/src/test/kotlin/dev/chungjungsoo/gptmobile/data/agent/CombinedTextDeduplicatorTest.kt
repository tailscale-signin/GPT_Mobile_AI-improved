package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class CombinedTextDeduplicatorTest {
    @Test
    fun removesRepeatedSectionHeadingsAndExactParagraphs() {
        val input = """
            # History of Portugal

            Portugal became a kingdom in the twelfth century and expanded overseas later.

            ## Maritime expansion

            Portuguese navigators established routes across the Atlantic and Indian Ocean.

            # History of Portugal

            Portugal became a kingdom in the twelfth century and expanded overseas later.

            ## Modern era

            The modern republic developed through major political changes.
        """.trimIndent()

        val output = CombinedTextDeduplicator.removeRepeatedParagraphs(input)

        assertEquals(1, Regex("(?m)^# History of Portugal$").findAll(output).count())
        assertEquals(
            1,
            "Portugal became a kingdom in the twelfth century and expanded overseas later.".let { paragraph ->
                Regex(Regex.escape(paragraph)).findAll(output).count()
            }
        )
        assertEquals(1, Regex("(?m)^## Maritime expansion$").findAll(output).count())
        assertEquals(1, Regex("(?m)^## Modern era$").findAll(output).count())
    }

    @Test
    fun preservesDistinctParagraphsAndReturnsBlankInputUnchanged() {
        val input = "First substantial paragraph with unique content.\n\nSecond substantial paragraph with different content."
        assertEquals(input, CombinedTextDeduplicator.removeRepeatedParagraphs(input))
        assertEquals("   ", CombinedTextDeduplicator.removeRepeatedParagraphs("   "))
    }
}
