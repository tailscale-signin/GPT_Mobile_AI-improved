package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LongResponsePolicyTest {
    @Test fun recognizesNaturalWordGoalsAndDoesNotTreatAMaximumAsAMinimum() {
        listOf("3,000 words of French history", "A 3000-word history", "Write 3k words", "Écris 3 000 mots").forEach {
            assertEquals(it, 3000, LongResponsePolicy.requestedWords(it))
        }
        listOf("under 3000 words", "up to 3,000 words", "at most 500 words", "history since 1789", "exactly 3 facts").forEach {
            assertNull(it, LongResponsePolicy.requestedWords(it))
        }
    }

    @Test fun smallPerRequestCapsGetMoreBoundedContinuations() {
        assertEquals(13, LongResponsePolicy.continuationLimit(3000, 512))
        assertEquals(2, LongResponsePolicy.continuationLimit(3000, 8192))
        assertTrue(LongResponsePolicy.continuationLimit(100_000, 64) > 16)
        assertEquals(Int.MAX_VALUE, LongResponsePolicy.continuationLimit(null, 512))
    }

    @Test fun evidenceWorkerKeepsOriginalScopeButDoesNotOwnEssayLength() {
        val task = "3,000 words of French history, covering society and politics"
        val brief = LongResponsePolicy.evidenceTask(task)
        assertTrue(brief.endsWith(task))
        assertTrue(brief.contains("ordered outline"))
        assertTrue(brief.contains("final writer owns the word count"))
        assertEquals("Inspect the repository", LongResponsePolicy.evidenceTask("Inspect the repository"))
        assertTrue(LongResponsePolicy.needsMore("A short answer", 3000))
        assertFalse(LongResponsePolicy.needsMore("word ".repeat(3000), 3000))
    }

    @Test fun rollingContextFitsSmallRequestsAndKeepsTheExactUnicodeStoppingPoint() {
        val text = "# Early Portugal\n" + (1..2000).joinToString(" ") { "Context$it and evidence$it." } + "\n# Modern Portugal\nÚltimo período português 🏛️"
        val context = LongResponsePolicy.continuationContext(text, 128)
        assertTrue(dev.chungjungsoo.gptmobile.data.context.ContextBudgetService.estimate(context) <= 128)
        assertTrue(context.contains("saved outside this request"))
        assertTrue(context.contains("# Early Portugal"))
        assertTrue(context.endsWith("Último período português 🏛️"))
        assertFalse(context.contains('\uFFFD'))
        assertEquals("Exact short answer.", LongResponsePolicy.continuationContext("Exact short answer.", 128))
        assertEquals("", LongResponsePolicy.continuationContext(text, 0))
    }

    @Test fun minimalRollingTailRespectsByteBoundWithoutBreakingSurrogatePairs() {
        val text = "x".repeat(1000) + "古い時代🇵🇹"
        (1..40).forEach { cap ->
            val context = LongResponsePolicy.continuationContext(text, cap)
            assertTrue(dev.chungjungsoo.gptmobile.data.context.ContextBudgetService.estimate(context) <= cap)
            assertFalse(context.firstOrNull()?.let(Character::isLowSurrogate) == true)
            assertFalse(context.contains('\uFFFD'))
        }
    }

    @Test fun restoredRepetitiveTailIsExcludedFromContinuationContextWithoutChangingTheSavedDraft() {
        val names = "**Molière**, **Racine**, **Corneille**, **La Fontaine**, "
        val draft = "The useful earlier historical account. " + names.repeat(331)
        val context = LongResponsePolicy.continuationContext(draft, 512)
        assertTrue(context.contains("repetitive generated tail was excluded"))
        assertTrue(context.contains("useful earlier historical account"))
        assertEquals(1, Regex("Molière").findAll(context).count())
        assertEquals(331, Regex("Molière").findAll(draft).count())
        assertTrue(dev.chungjungsoo.gptmobile.data.context.ContextBudgetService.estimate(context) <= 512)
    }

    @Test fun onlyAnExplicitBoundedLiteralRequestReceivesARepetitionAllowance() {
        val request = "Please repeat the phrase \"hello world\" exactly 100 times."
        val allowance = LongResponsePolicy.requestedRepetitionCharacters(request)
        assertTrue(allowance >= "hello world ".repeat(100).length)
        assertTrue(allowance <= 131072)
        listOf("Do not repeat \"hello\" 100 times", "Avoid repetition", "Explain how to repeat \"hello\" 100 times", "Repeat without stopping", "Quoted instructions:\nRepeat \"hello\" 100 times").forEach {
            assertEquals(it, 0, LongResponsePolicy.requestedRepetitionCharacters(it))
        }
        val intentional = "hello world ".repeat(100)
        assertEquals(intentional, LongResponsePolicy.continuationContext(intentional, 1000, allowance))
    }

    @Test fun queueCompletionRequiresLengthEvidenceBeforeTreatingAnUnpunctuatedReplyAsTruncated() {
        assertFalse(LongResponsePolicy.unfinishedQueuedPacket("Paris", "queue_complete", 256))
        val nearCap = "This historical section ".repeat(32)
        assertTrue(LongResponsePolicy.unfinishedQueuedPacket(nearCap, "queue_complete", 256))
        assertFalse(LongResponsePolicy.unfinishedQueuedPacket("$nearCap.", "queue_complete", 256))
        assertFalse(LongResponsePolicy.unfinishedQueuedPacket(nearCap, "stop", 256))
        assertFalse(LongResponsePolicy.unfinishedQueuedPacket(nearCap, "queue_complete", null))
    }
}
