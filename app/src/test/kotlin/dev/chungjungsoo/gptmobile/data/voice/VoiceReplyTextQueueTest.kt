package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceReplyTextQueueTest {
    @Test fun finishingAFullQueueRetainsOrderAndDeliversEndImmediatelyAfterItsLastText() {
        val input = VoiceReplyTextQueue(2)
        assertTrue(input.offer("hello"))
        assertTrue(input.offer("again"))
        assertFalse(input.offer("overflow"))
        input.finish()
        assertFalse(input.offer("late"))
        assertEquals("hello", input.next())
        assertEquals("again", input.next())
        assertNull(input.next())
        assertTrue(input.isEmpty())
    }

    @Test fun finishAndCancelReleaseAReaderWaitingForMoreText() {
        for (finish in listOf(true, false)) {
            val input = VoiceReplyTextQueue(1)
            val reader = Executors.newSingleThreadExecutor()
            val started = CountDownLatch(1)
            try {
                val next = reader.submit<String?> {
                    started.countDown()
                    input.next()
                }
                assertTrue(started.await(2, TimeUnit.SECONDS))
                if (finish) input.finish() else input.cancel()
                assertNull(next.get(2, TimeUnit.SECONDS))
            } finally {
                reader.shutdownNow()
            }
        }
    }

    @Test fun cancelDiscardsQueuedTextEvenAfterInputFinished() {
        val input = VoiceReplyTextQueue(1)
        assertTrue(input.offer("stale"))
        input.finish()
        input.cancel()
        assertNull(input.next())
        assertFalse(input.offer("late"))
    }
}
