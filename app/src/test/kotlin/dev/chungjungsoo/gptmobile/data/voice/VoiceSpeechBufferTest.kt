package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSpeechBufferTest {
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
}
