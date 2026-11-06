package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.ResponseSource
import dev.chungjungsoo.gptmobile.data.database.entity.ResponseTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AnswerSegmentChainTest {
    @Test
    fun mixedSourcesRemainAttachedToTheirCommittedCharactersAfterContinuation() {
        val gateway = ResponseSource(ResponseTransport.GATEWAY, profileUid = "one")
        val direct = ResponseSource(ResponseTransport.DIRECT, profileUid = "two")
        val timeline = listOf(
            AssistantTimelineItem(AssistantTimelineItemType.TEXT, content = "gateway", responseSource = gateway),
            AssistantTimelineItem(AssistantTimelineItemType.TEXT, content = "direct", responseSource = direct)
        )
        val first = AnswerSegmentChain.append(emptyList(), "run", 1, "gateway", timeline.take(1))
        val continued = first + AnswerSegmentChain.append(first, "run", 1, "gatewaydirect", timeline)
        assertEquals("gatewaydirect", AnswerSegmentChain.text(continued, "run", 1))
        assertEquals(listOf(gateway, direct), continued.map { it.responseSource })
        assertEquals(first, continued.take(first.size))
    }

    @Test
    fun sourceBoundaryCannotSplitUnicodeOrStallAppend() {
        val gateway = ResponseSource(ResponseTransport.GATEWAY)
        val text = "🙂"
        val timeline = listOf(
            AssistantTimelineItem(AssistantTimelineItemType.TEXT, content = text.take(1), responseSource = gateway),
            AssistantTimelineItem(AssistantTimelineItemType.TEXT, content = text.drop(1))
        )
        val segments = AnswerSegmentChain.append(emptyList(), "run", 1, text, timeline)
        assertEquals(text, AnswerSegmentChain.text(segments, "run", 1))
        assertEquals(null, segments.single().responseSource)
    }

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
