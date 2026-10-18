package dev.chungjungsoo.gptmobile.data.agent

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedAnswerPolicyTest {
    @Test
    fun exactMaximumMinimumAndApproximateUseDistinctBounds() {
        for (count in listOf(43, 45, 50, 55, 56)) {
            val answer = List(count) { "word$it" }.joinToString(" ")
            assertEquals(count != 50, CombinedAnswerPolicy.from("Exactly 50 words").violations(answer).isNotEmpty())
            assertEquals(count > 50, CombinedAnswerPolicy.from("At most 50 words").violations(answer).isNotEmpty())
            assertEquals(count < 50, CombinedAnswerPolicy.from("At least 50 words").violations(answer).isNotEmpty())
            assertEquals(count !in 45..55, CombinedAnswerPolicy.from("About 50 words").violations(answer).isNotEmpty())
        }
    }

    @Test fun recognizesSmallTargetsAndStrictUpperBounds() {
        assertEquals(100, CombinedAnswerPolicy.from("Random historical fact, 100 words").targetWords)
        assertEquals(99, CombinedAnswerPolicy.from("Italy history under 100 words").maximumWords)
        assertEquals(100, CombinedAnswerPolicy.from("At most 100 words").maximumWords)
        assertTrue(CombinedAnswerPolicy.from("One historical fact").singleExample)
        assertTrue(CombinedAnswerPolicy.from("Newest Toronto news story").requiresCurrentEvidence)
        assertFalse(CombinedAnswerPolicy.from("Explain ancient history comprehensively").requiresCurrentEvidence)
        assertTrue(CombinedAnswerPolicy.from("Under 10 words").violations("word ".repeat(10)).any { it.startsWith("WORD_LIMIT_EXCEEDED") })
    }

    @Test fun frozenTurnUsesTorontoCalendarRatherThanProviderGuessOrUtcDate() {
        val instant = Instant.parse("2026-10-17T01:00:00Z")
        val context = CombinedTurnContext.capture(instant.epochSecond, ZoneId.of("America/Toronto"))
        assertEquals("2026-10-16", context.date)
        assertEquals("America/Toronto", context.timezone)
        assertTrue(context.instruction().contains("tool results take precedence"))
    }

    @Test fun requestedLengthControlsContinuationAllowanceEvenWithHugeContributorCorpus() {
        val envelope = """{"original_request":"Under 100 words","contributions":[{"response":"${"word ".repeat(10000)}"}]}"""
        assertEquals(1, CombinedResponseBudget.continuationLimit(envelope, 4096, null))
    }
}
