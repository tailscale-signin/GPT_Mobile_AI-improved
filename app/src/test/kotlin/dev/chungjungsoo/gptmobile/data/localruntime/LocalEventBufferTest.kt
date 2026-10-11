package dev.chungjungsoo.gptmobile.data.localruntime

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEventBufferTest {
    @org.junit.Test fun unicodeChunksNeverSplitSurrogatePairs() {
        val text = "a".repeat(4095) + "🙂é".repeat(3000)
        val chunks = text.unicodeChunks(4096).toList()
        org.junit.Assert.assertEquals(text, chunks.joinToString(""))
        org.junit.Assert.assertTrue(chunks.all { it == it.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) })
    }

    @Test fun `slow consumer receives every accepted character and channel boundary`() = runTest {
        val queue = LocalEventBuffer()
        repeat(1000) { assertTrue(queue.offer(LocalRuntimeEvent.TextDelta("é$it "))) }
        queue.offer(LocalRuntimeEvent.ThinkingDelta("think"))
        queue.offer(LocalRuntimeEvent.TextDelta("answer"))
        queue.offer(LocalRuntimeEvent.Done)
        val events = mutableListOf<LocalRuntimeEvent>()
        while (true) events += queue.take() ?: break
        assertEquals((0..999).joinToString("") { "é$it " } + "answer", events.filterIsInstance<LocalRuntimeEvent.TextDelta>().joinToString("") { it.text })
        assertEquals(listOf(LocalRuntimeEvent.ThinkingDelta("think")), events.filterIsInstance<LocalRuntimeEvent.ThinkingDelta>())
        assertEquals(LocalRuntimeEvent.Done, events.last())
    }

    @Test fun `overflow preserves accepted prefix and reserves a single terminal error`() = runTest {
        val queue = LocalEventBuffer(byteLimit = 4)
        assertTrue(queue.offer(LocalRuntimeEvent.TextDelta("éé")))
        assertFalse(queue.offer(LocalRuntimeEvent.TextDelta("x")))
        assertFalse(queue.offer(LocalRuntimeEvent.Done))
        assertEquals(LocalRuntimeEvent.TextDelta("éé"), queue.take())
        assertTrue(queue.take() is LocalRuntimeEvent.Error)
        assertNull(queue.take())
    }
}
