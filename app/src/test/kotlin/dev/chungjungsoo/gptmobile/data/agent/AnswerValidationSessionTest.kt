package dev.chungjungsoo.gptmobile.data.agent

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerValidationSessionTest {
    private fun session(vararg events: ProviderEvent) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(*events)
    }

    @Test fun oversizedAnswerIsReplacedOnceAndNeverAppended() = runBlocking {
        val draft = (1..150).joinToString(" ") { "item$it" } + "."
        val corrected = (1..100).joinToString(" ") { "point$it" } + "."
        var rewrites = 0
        val events = AnswerValidationSession(session(ProviderEvent.TextDelta(draft), ProviderEvent.Completed), "Write 100 words.") { original, failures, _ ->
            assertEquals(draft, original)
            assertTrue(failures.any { it.startsWith("WORD_TARGET_EXCEEDED") })
            rewrites++
            session(ProviderEvent.TextDelta(corrected), ProviderEvent.Completed)
        }.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, rewrites)
        assertEquals(corrected, events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertTrue(events.last() == ProviderEvent.Completed)
    }

    @Test fun wrongSubjectCannotCompleteEvenAfterRewrite() = runBlocking {
        val wrong = "Portugal became a republic in 1910. Portugal joined the European Communities in 1986."
        val events = AnswerValidationSession(session(ProviderEvent.TextDelta(wrong), ProviderEvent.Completed), "History of Germany") { _, _, _ ->
            session(ProviderEvent.TextDelta(wrong), ProviderEvent.Completed)
        }.streamRound(emptyList(), emptyList()).toList()
        assertFalse(events.contains(ProviderEvent.Completed))
        assertTrue(events.last() is ProviderEvent.Failed)
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().isNotEmpty())
    }

    @Test fun queueFailuresNeverTriggerARewriteOrGenericContinuation() = runBlocking {
        var rewrites = 0
        val message = "QUEUE_STREAM_DISCONNECTED: stream ended before completion"
        val events = AnswerValidationSession(session(ProviderEvent.Failed(message)), "Write an essay") { _, _, _ ->
            rewrites++
            session()
        }
            .streamRound(emptyList(), emptyList()).toList()
        assertEquals(0, rewrites)
        assertFalse(isRecoverableStreamFailure(message))
        assertTrue(events.last() is ProviderEvent.Failed)
    }

    @Test fun checksDetectBothLengthDirectionsRepetitionAndUnfinishedProse() {
        assertTrue(AnswerCompletionChecks.failures("Write 500 words", "brief").any { it.startsWith("WORD_TARGET_UNDERSHOT") })
        assertTrue(AnswerCompletionChecks.failures("Write at most 10 words", (1..30).joinToString(" ") { "word$it" }).any { it.startsWith("WORD_LIMIT_EXCEEDED") })
        assertTrue(AnswerCompletionChecks.failures("Write an essay", "This sentence repeats without adding any useful information. ".repeat(10)).contains("SUBSTANTIAL_REPETITION"))
        assertTrue(AnswerCompletionChecks.failures("Write an essay", (1..50).joinToString(" ") { "word$it" }).contains("UNFINISHED_PROSE"))
        assertTrue(AnswerCompletionChecks.failures("Write a poem", "Blue\nThe strange quiet\nGreen").isEmpty())
    }

    @Test fun deviceDateUsesTimezoneAndComputesWeekdayWithoutSearch() {
        val instant = Instant.parse("2026-10-23T01:00:00Z")
        assertEquals("Today is Thursday, October 22, 2026 (America/Toronto), according to your device clock.", DeviceDateAnswer.answer("what's today?", instant, ZoneId.of("America/Toronto")))
        assertTrue(DeviceDateAnswer.answer("today's date", instant, ZoneId.of("UTC"))!!.contains("Friday, October 23"))
        assertNull(DeviceDateAnswer.answer("What's today and what are the headlines?", instant, ZoneId.of("UTC")))
    }
}
