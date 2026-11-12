package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuationTextProgressTest {
    private val names = "**Molière**, **Racine**, **Corneille**, **La Fontaine**, "

    @Test fun capturedFourNameLoopKeepsOneOccurrenceForEveryChunkBoundary() {
        val prefix = "French classical literature includes "
        for (size in listOf(1, 2, 7, 57, 128, 4096)) {
            val progress = ContinuationTextProgress()
            val output = (prefix + names.repeat(331)).chunked(size).joinToString("") { progress.append(it) } + progress.finish()
            assertTrue("chunk=$size", progress.repetitive)
            assertEquals("chunk=$size", prefix + names, output)
        }
    }

    @Test fun continuationEmitsNewTextBeforeTheProviderCompletesWithoutDuplicatingOverlap() {
        val overlap = "The Revolution began in 1789. "
        val progress = ContinuationTextProgress("Earlier eras. $overlap")
        val outputs = (overlap + "Napoleon took power in 1799. The next section follows. ").chunked(7).map(progress::append)
        assertTrue(outputs.any(String::isNotEmpty))
        assertEquals("Napoleon took power in 1799. The next section follows. ", outputs.joinToString("") + progress.finish())
        assertFalse(progress.stopped)
    }

    @Test fun divergentDeltaPublishesWithoutWaitingForTheNextCoarseOverlapProbe() {
        val previous = "An earlier historical section retained for context. The saved introduction ends here. "
        val overlap = previous.takeLast(28)
        val progress = ContinuationTextProgress(previous)
        assertEquals("", progress.append(overlap))
        val continuation = "The next era changed institutions. Additional material "
        assertTrue(overlap.length + continuation.length < 84)
        val live = progress.append(continuation)
        assertTrue("Confirmed new text must publish at the provider's delta boundary", live.contains("The next era"))
        assertEquals(continuation, live + progress.finish())
        assertFalse(progress.stopped)
    }

    @Test fun longLiteralRestartStopsAtBoundedPrefixInsteadOfWaitingForAnotherFullAnswer() {
        val previous = (1..10000).joinToString(" ") { "distinct$it" }
        val progress = ContinuationTextProgress(previous)
        assertEquals("", progress.append(previous))
        assertTrue(progress.repeatedPrefix)
        assertEquals("", progress.finish())
    }

    @Test fun shortCompletionAndDifferentDatesRemainLiteral() {
        val tiny = ContinuationTextProgress("The emperor was Napoleo")
        assertEquals("n", tiny.append("n") + tiny.finish())
        val different = ContinuationTextProgress("The Revolution began in 1789. ")
        val answer = "A conflicting source gives the date 1788."
        assertEquals(answer, different.append(answer) + different.finish())
    }

    @Test fun codeTablesQuotesAndDivergingProseArePreservedByteForByte() {
        val repeated = "A deliberately repeated phrase with several words. "
        val cases = listOf(
            "```text\n${repeated.repeat(40)}\n```\nDone.",
            "~~~text\n${repeated.repeat(40)}\n~~~\nDone.",
            "> ${repeated.repeat(40)}\nEnd of quotation.",
            "\"${repeated.repeat(40)}\" is the quoted passage.",
            "``${repeated.repeat(40)}`` is literal code.",
            "| Label | Value |\n| --- | --- |\n" + "| Same | Same |\n".repeat(40),
            (1..50).joinToString("\n") { "The institution changed in year ${1700 + it}." },
            "This is the shared opening. This is the shared opening with a different conclusion."
        )
        for (source in cases) {
            for (size in listOf(1, 17, 512)) {
                val progress = ContinuationTextProgress()
                val output = source.chunked(size).joinToString("") { progress.append(it) } + progress.finish()
                assertFalse(source.take(80), progress.stopped)
                assertEquals(source, output)
            }
        }
    }

    @Test fun oneRepeatedWordDoesNotCountAsSubstantiveProgress() {
        val progress = ContinuationTextProgress()
        val output = progress.append("history ".repeat(500)) + progress.finish()
        assertTrue(progress.repetitive)
        assertEquals("history ", output)
    }

    @Test fun openCodeFenceIsRetainedAcrossContinuationPackets() {
        val progress = ContinuationTextProgress("The example is:\n```text\n")
        val source = "literal value ".repeat(100) + "\n```\nThe explanation continues."
        assertEquals(source, progress.append(source) + progress.finish())
        assertFalse(progress.stopped)
    }

    @Test fun boundedLiteralRepetitionAllowanceStillStopsRunawayOutputBeyondTheRequest() {
        val source = "echo ".repeat(100)
        val allowed = ContinuationTextProgress(requestedRepetitionCharacters = 1500)
        assertEquals(source, allowed.append(source) + allowed.finish())
        assertFalse(allowed.stopped)
        val runaway = ContinuationTextProgress(requestedRepetitionCharacters = 1500)
        val output = runaway.append("echo ".repeat(2000)) + runaway.finish()
        assertTrue(runaway.repetitive)
        assertTrue(output.length <= 1500)
    }

    @Test fun repeatedVisibleBoundariesKeepThePendingRepetitionLimit() {
        val repeated = "This repeating passage is not new evidence. "
        val progress = ContinuationTextProgress()
        val output = StringBuilder()
        var chunks = 0
        while (chunks < 100 && !progress.stopped) {
            chunks++
            output.append(progress.append(repeated))
            output.append(progress.flushBoundary())
        }
        output.append(progress.finish())
        assertTrue(progress.repetitive)
        assertTrue(chunks < 100)
        assertTrue(output.startsWith(repeated))
        assertTrue(output.length < repeated.length * 10)
    }

    @Test fun publishedRepetitionCandidateIsNotDuplicatedWhenItDivergesOrFinishes() {
        val shared = "This is a deliberately shared opening. "
        for (ending in listOf("", "A different conclusion follows.", "\n```text\nliteral example\n```")) {
            val progress = ContinuationTextProgress()
            val output = StringBuilder()
            repeat(2) {
                output.append(progress.append(shared))
                output.append(progress.flushBoundary())
                assertEquals("", progress.flushBoundary())
            }
            output.append(progress.append(ending))
            output.append(progress.finish())
            assertEquals(shared.repeat(2) + ending, output.toString())
            assertEquals("", progress.finish())
            assertFalse(progress.stopped)
        }
    }
}
