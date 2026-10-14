package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AnswerSegmentChainTest {
    @Test
    fun cmT26LongUnicodeAnswerRetainsFullPrefixAndTail() {
        val first = "chapter 🙂é\n".repeat(20_000)
        val initial = AnswerSegmentChain.append(emptyList(), "run", 8, first)
        val next = first + "last chapter λ"
        val all = initial + AnswerSegmentChain.append(initial, "run", 8, next)
        assertEquals(next, AnswerSegmentChain.text(all, "run", 8))
        assertEquals(emptyList<Any>(), AnswerSegmentChain.append(all, "run", 8, next))
    }

    @Test
    fun ix08StaleFenceAndChangedPrefixAreRejected() {
        val first = AnswerSegmentChain.append(emptyList(), "run", 2, "first chapter")
        assertThrows(IllegalArgumentException::class.java) { AnswerSegmentChain.append(first, "run", 3, "first chapter and more") }
        assertThrows(IllegalArgumentException::class.java) { AnswerSegmentChain.append(first, "run", 2, "replacement tail") }
        assertThrows(IllegalArgumentException::class.java) { AnswerSegmentChain.text(first.map { it.copy(text = "corrupted") }, "run", 2) }
    }
}
