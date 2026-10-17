package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.agent.ClaimSupportClass
import dev.chungjungsoo.gptmobile.data.agent.ValidatedCombinedProvenance
import dev.chungjungsoo.gptmobile.data.agent.ValidatedSentenceAttribution
import dev.chungjungsoo.gptmobile.data.conversation.ConversationSubject
import dev.chungjungsoo.gptmobile.data.research.researchHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class MarkdownSourceMapTest {
    private fun mapped(source: String): Pair<String, MarkdownSourceMap> {
        val visible = ConversationSubject.withoutMetadata(source)
        return buildMappedMarkdown(source, visible, parseChatMarkdown(visible, cacheResult = false), "fixture")
    }

    @Test
    fun wordingColoursFollowRealMarkdownTransformsAndRejectStaleAnswers() {
        val sentence = "Silver mines funded public works and naval power in ancient Athens."
        val source = "<!-- chat_subject: Culture -->\n😀 **$sentence** ${'$'}x^2${'$'}\n\n${'$'}${'$'}y+z${'$'}${'$'}"
        val rows = listOf(dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse("one", "One", content = sentence))
        val (markdown, map) = mapped(source)
        val spans = map.wordingSpans(source, markdown, rows)
        assertTrue(spans.isNotEmpty())
        assertEquals("Silver mines funded public works and naval power in ancient Athens", spans.joinToString("") { markdown.substring(it.start, it.end) })
        assertTrue(map.wordingSpans(source + "changed", markdown, rows).isEmpty())
        val shared = map.wordingSpans(source, markdown, rows + rows.single().copy(platformUid = "two"))
        assertTrue(shared.isNotEmpty())
        assertTrue(shared.all { it.profileUid == "one" })
        assertTrue(map.copy(runs = listOf(MarkdownSourceRun(0, -1, 5, 0))).wordingSpans(source, markdown, rows).isEmpty())
    }

    @Test
    fun `actual math transformation preserves Unicode prose offsets and excludes generated placeholders`() {
        val source = "<!-- chat_subject: Test -->\n😀 é שלום **before** ${'$'}x^2${'$'} [after](https://example.org).\n\n${'$'}${'$'}y+z${'$'}${'$'}\n\nآخر tail."
        val (markdown, map) = mapped(source)
        val provenance = ValidatedCombinedProvenance(
            researchHash(source),
            "corpus",
            ATTRIBUTION_MARKDOWN_PARSER_VERSION,
            "policy",
            listOf(ValidatedSentenceAttribution(0, source.length, ClaimSupportClass.EXCLUSIVE, setOf("profile"), emptySet(), 1, 1, null))
        )
        val spans = map.exclusiveSpans(source, markdown, provenance, "corpus", "policy")
        assertTrue(spans.isNotEmpty())
        val colored = spans.joinToString("") { markdown.substring(it.start, it.end) }
        assertTrue(colored.contains("😀 é שלום **before**"))
        assertTrue(colored.contains("[after](https://example.org)"))
        assertTrue(colored.contains("آخر tail."))
        assertFalse(colored.contains("CHAT_MATH"))
        assertFalse(colored.contains("chat_subject"))
        map.runs.forEach { run -> assertEquals(source.substring(run.sourceStart, run.sourceEnd), markdown.substring(run.transformedStart, run.transformedStart + run.length)) }
    }

    @Test
    fun `stale digests and malformed runs fall back without changing markdown bytes`() {
        val source = "Before ${'$'}x${'$'} after."
        val (markdown, map) = mapped(source)
        val provenance = ValidatedCombinedProvenance(researchHash(source), "corpus", ATTRIBUTION_MARKDOWN_PARSER_VERSION, "policy", emptyList())
        assertTrue(map.exclusiveSpans(source + "changed", markdown, provenance, "corpus", "policy").isEmpty())
        assertTrue(map.copy(runs = listOf(MarkdownSourceRun(0, 0, source.length + 1, 0))).exclusiveSpans(source, markdown, provenance, "corpus", "policy").isEmpty())
        assertEquals(markdown, buildCombinedMarkdown(parseChatMarkdown(source).blocks, "fixture"))
    }

    @Test
    fun `repeated text code tables and blank blocks retain exact origin positions`() {
        val source = "same. ${'$'}x${'$'} same.\n\n${'$'}${'$'}y${'$'}${'$'}  ${'$'}${'$'}z${'$'}${'$'}\n\n| one | two |\n| --- | --- |\n| 😀 | שלום |\n\n```text\n${'$'}x${'$'} literal\n```\n`code` same."
        val (markdown, map) = mapped(source)
        map.runs.forEach { run -> assertEquals(source.substring(run.sourceStart, run.sourceEnd), markdown.substring(run.transformedStart, run.transformedStart + run.length)) }
        val last = map.runs.last()
        assertTrue(source.substring(last.sourceStart, last.sourceEnd).endsWith("`code` same."))
        assertTrue(markdown.contains("${'$'}x${'$'} literal"))
    }
}
