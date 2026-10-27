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

    @Test fun preservesFencedCodeHeadingsBlankLinesAndRepeatedStatements() {
        val code = "```kotlin\n# example heading\n\nprintln(\"retain this repeated statement\")\n\n# example heading\n\nprintln(\"retain this repeated statement\")\n```"
        val input = "Introductory prose about this example.\n\n$code\n\nIntroductory prose about this example."
        assertEquals("Introductory prose about this example.\n\n$code", CombinedTextDeduplicator.removeRepeatedParagraphs(input))
    }

    @Test fun preservesTildeAndUnclosedFencesAndIndentedCode() {
        val tilde = "~~~text\n# repeated heading\n\n# repeated heading\n~~~"
        val unclosed = "```text\n# repeated heading\n\n# repeated heading"
        val indented = "    println(\"do not remove repeated code\")\n\n    println(\"do not remove repeated code\")"
        listOf(tilde, unclosed, indented).forEach { assertEquals(it, CombinedTextDeduplicator.removeRepeatedParagraphs(it)) }
    }

    @Test fun preservesCaseSensitiveClaimsAndRepeatedStructuredBlocks() {
        val input = "The identifier FooBar is distinct from another identifier.\n\nThe identifier foobar is distinct from another identifier.\n\n> Repeated quotation must remain intact.\n\n> Repeated quotation must remain intact.\n\n1. Repeat this step for the next participant.\n\n1. Repeat this step for the next participant."
        assertEquals(input, CombinedTextDeduplicator.removeRepeatedParagraphs(input))
    }
}
