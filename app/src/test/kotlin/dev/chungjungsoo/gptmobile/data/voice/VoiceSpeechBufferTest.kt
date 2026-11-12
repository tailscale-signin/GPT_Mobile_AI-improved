package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSpeechBufferTest {
    @Test fun terminalWhitespaceTrimmingDoesNotInvalidateQueuedSpeech() {
        val buffer = VoiceSpeechBuffer()
        assertEquals(listOf("A completed sentence. "), buffer.append("A completed sentence. ", false, earlyStart = true))
        assertEquals(emptyList<String>(), buffer.append("A completed sentence.", true, earlyStart = true))
        assertNull(buffer.append("A changed sentence.", true, earlyStart = true))
    }

    @Test fun repeatedSnapshotsSpeakEachSentenceOnceAndRetainTail() {
        val buffer = VoiceSpeechBuffer()
        assertTrue(buffer.append("Hello", false)!!.isEmpty())
        assertEquals(listOf("Hello. "), buffer.append("Hello. Next", false))
        assertTrue(buffer.append("Hello. Next", false)!!.isEmpty())
        assertEquals(listOf("Next sentence"), buffer.append("Hello. Next sentence", true))
        assertTrue(buffer.append("Hello. Next sentence", true)!!.isEmpty())
    }

    @Test fun revisionStopsFurtherSpokenClaims() {
        val buffer = VoiceSpeechBuffer()
        buffer.append("Original. ", false)
        assertNull(buffer.append("Revised answer.", true))
    }

    @Test fun streamingContinuesThroughAnUnpunctuatedSentenceWithoutRepeatsOrLostWords() {
        val buffer = VoiceSpeechBuffer()
        val spoken = mutableListOf<String>()
        var snapshot = ""
        for (index in 1..44) {
            snapshot += "word$index "
            spoken += requireNotNull(buffer.append(snapshot, false, earlyStart = true))
            assertTrue(buffer.append(snapshot, false, earlyStart = true)!!.isEmpty())
            if (index == 8) assertEquals(1, spoken.size)
            if (index == 20) assertEquals(2, spoken.size)
            if (index == 32) assertEquals(3, spoken.size)
        }
        spoken += requireNotNull(buffer.append(snapshot + "finished", true, earlyStart = true))
        assertEquals(snapshot + "finished", spoken.joinToString(""))
    }

    @Test fun continuationUsesStableClausesAndBoundedCompleteWordsIncludingEmoji() {
        val buffer = VoiceSpeechBuffer()
        val opening = "A short first clause, "
        assertEquals(listOf(opening), buffer.append(opening, false, earlyStart = true))
        val clause = "another useful clause, "
        assertEquals(listOf(clause), buffer.append(opening + clause, false, earlyStart = true))
        val tail = ("🗣" + "longword".repeat(10) + " ").repeat(10)
        val chunks = requireNotNull(buffer.append(opening + clause + tail, false, earlyStart = true))
        assertTrue(chunks.size >= 2)
        assertTrue(chunks.all { it.length <= 240 && it.last().isWhitespace() })
        val final = requireNotNull(buffer.append(opening + clause + tail, true, earlyStart = true))
        assertEquals(tail, (chunks + final).joinToString(""))
    }
}
