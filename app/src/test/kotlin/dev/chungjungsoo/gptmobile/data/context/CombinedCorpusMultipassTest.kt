package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedCorpusMultipassTest {
    private fun turn(content: String, current: Boolean) =
        ConversationTurn(MessageV2(content = content, platformType = null), null, current)

    @Test fun comprehensivePolicySurvivesTheMultipassHandoff() {
        val input = "{\"original_request\":\"Write 100 words\",\"combination_policy\":\"COMPREHENSIVE\",\"contributions\":\"All four topics\"}"
        val plan = CombinedCorpusMultipass.plan(listOf(turn(input, true)), "", TokenBudgetSettings(contextTokens = 4096, outputTokens = 256))
        assertTrue(plan.comprehensive)
        val final = plan.finalTurns(plan.passes.map { "All distinct topics and details retained." }).single().userMessage.content
        assertTrue(final.contains("Combination policy: COMPREHENSIVE"))
        assertTrue(final.contains("word limit and requested number of examples take priority"))
        assertFalse(final.contains("short original word goal must not remove topics"))
    }

    @Test fun passageProtocolAndCanonicalSourcesSurviveTheLargeContextFallback() {
        val input = """{"original_request":"One fact under 100 words","combination_policy":"COMPREHENSIVE","delivery_protocol":"PASSAGES_V2","instruction_priority":"USER_SCOPE_AND_CONSTRAINTS_FIRST","authoritative_turn_context":{"date":"2026-10-16","timezone":"America/Toronto"},"contributions":[{"id":"C1","profile_uid":"alpha","response":"Complete frozen contributor text."}],"evidence_ledger":{"records":[{"id":"E1","url":"https://example.org/article","publishedAt":"2026-10-16","passage":"Complete retained source passage."}]}}"""
        val plan = CombinedCorpusMultipass.plan(listOf(turn(input, true)), "", TokenBudgetSettings(contextTokens = 4096, outputTokens = 256))
        val final = plan.finalTurns(plan.passes.map { "Inspected C1 and E1; preserve scope and provenance." }).single().userMessage.content
        assertTrue(final.contains("PASSAGES_V2"))
        assertTrue(final.contains("https://example.org/article"))
        assertTrue(final.contains("America/Toronto"))
        assertFalse(final.contains("short original word goal must not remove topics"))
        assertEquals(input, plan.passes.joinToString("") { it.source.text })
    }

    @Test fun overflowSelectsBoundedInspectionRatherThanEvictingHistory() {
        val historical = "historical constraints with unique details ".repeat(200)
        val current = "{\"original_request\":\"Explain all details\",\"contributions\":\"${"unique ".repeat(1600)}\"}"
        val original = listOf(turn(historical, false), turn(current, true))
        val plan = ContextBudgetService.planFullInputOrMultipass(
            original,
            "Be accurate.",
            TokenBudgetSettings(contextTokens = 4096, outputTokens = 256)
        )
        val passes = requireNotNull(plan.multipass)
        assertTrue(plan.notice.contains("FULL_CORPUS_MULTIPASS"))
        assertEquals(original, plan.turns)
        assertEquals(
            historical,
            passes.passes.filter { it.source.turnIndex == 0 }.joinToString("") { it.source.text }
        )
        assertEquals(
            current,
            passes.passes.filter { it.source.turnIndex == 1 }.joinToString("") { it.source.text }
        )
        assertTrue(passes.passes.all { it.estimatedPromptTokens + passes.inspectionOutputTokens + 256 <= 4096 })
        assertTrue(passes.passes.all { it.source.text.isNotEmpty() })
    }

    @Test(timeout = 5000)
    fun unicodeSurrogatePairsAreNeverCutBetweenPasses() {
        val original = "🌍✨".repeat(3000)
        val corpus = CombinedCorpusMultipass.plan(
            listOf(turn("Previous", false), turn("{\"original_request\":\"Describe\",\"contributions\":\"$original\"}", true)),
            "",
            TokenBudgetSettings(contextTokens = 4096, outputTokens = 128)
        )
        val spans = corpus.passes.filter { it.source.turnIndex == 1 }
        assertTrue(spans.size > 1)
        assertFalse(spans.any { Character.isLowSurrogate(it.source.text.first()) })
        assertFalse(spans.any { Character.isHighSurrogate(it.source.text.last()) })
        assertEquals("{\"original_request\":\"Describe\",\"contributions\":\"$original\"}", spans.joinToString("") { it.source.text })
    }

    @Test fun rejectsUnsupportedOutputHeadroomAndNeverChangesExplicitLimits() {
        val original = listOf(turn("History ".repeat(1000), false), turn("Goal", true))
        val failure = assertThrows(IllegalArgumentException::class.java) {
            CombinedCorpusMultipass.plan(original, "", TokenBudgetSettings(contextTokens = 1024, outputTokens = 1024))
        }
        assertTrue(failure.message.orEmpty().contains("COMBINED_MULTIPASS_OUTPUT"))
    }

    @Test fun refusesExcessivePhysicalPassesWithoutDiscardingOriginalCorpus() {
        val source = "Untrusted source. ".repeat(12000)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            CombinedCorpusMultipass.plan(
                listOf(turn(source, false), turn("Describe", true)),
                "",
                TokenBudgetSettings(contextTokens = 1024, outputTokens = 128)
            )
        }
        assertTrue(failure.message.orEmpty().contains("COMBINED_MULTIPASS_BUDGET"))
    }

    @Test fun requiresEveryInspectedReportBeforePreparingFinalPrompt() {
        val corpus = CombinedCorpusMultipass.plan(
            listOf(turn("Old ".repeat(1000), false), turn("{\"original_request\":\"Write in detail\"}", true)),
            "",
            TokenBudgetSettings(contextTokens = 2048, outputTokens = 128)
        )
        assertThrows(IllegalArgumentException::class.java) { corpus.finalTurns(emptyList()) }
        val final = corpus.finalTurns(corpus.passes.map { "Detailed finding from ${it.source.sourceId}" })
        assertEquals(1, final.size)
        assertTrue(final.single().userMessage.content.contains("Write in detail"))
        assertTrue(final.single().userMessage.content.contains("Same-primary inspection records"))
        assertTrue(final.single().isCurrentTurn)
    }
}
