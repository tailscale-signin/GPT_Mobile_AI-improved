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
        assertEquals(16, LongResponsePolicy.continuationLimit(100_000, 64))
        assertEquals(1, LongResponsePolicy.continuationLimit(null, 512))
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
}
