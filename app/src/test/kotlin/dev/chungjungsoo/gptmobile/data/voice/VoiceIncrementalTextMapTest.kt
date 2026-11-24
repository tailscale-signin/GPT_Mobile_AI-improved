package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceIncrementalTextMapTest {
    @Test fun utf8BoundariesRemainStableWhenLaterInputArrives() {
        val text = VoiceIncrementalTextMap()
        text.append("Hi 🎤 ")
        assertEquals(3..4, text.range(3, 7))
        assertFalse(text.contains(4, 7))
        text.append("café")
        assertEquals(3..4, text.range(3, 7))
        assertEquals(6..9, text.range(8, 13))
        assertThrows(IllegalStateException::class.java) { text.range(8, 12) }
    }

    @Test fun oneStreamMapsPronunciationAndLaterSegmentCuesToTheirOriginalCaptions() {
        val text = VoiceReplyStreamText()
        val first = VoiceStreamingPhrasePlan("GPU", mapOf("gpu" to "graphics processor"), 1f).next(8)!!
        val second = VoiceStreamingPhrasePlan("is ready.", emptyMap(), 1f).next(8)!!
        assertEquals("graphics processor", text.append(0, "GPU", first))
        assertEquals(" is ready.", text.append(1, "is ready.", second))
        val raw = VoiceStreamFrames.incremental(text.projection)
        val cues = raw.accept(
            VoicePcmChunk(
                FloatArray(30) { .1f },
                wordCues = listOf(
                    VoiceWordCue(0, 8, 0, 10),
                    VoiceWordCue(9, 18, 10, 20),
                    VoiceWordCue(19, 21, 20, 30)
                )
            )
        )
        val mapped = text.captions(cues)
        assertEquals(listOf(0, 0, 1), mapped.map { it.segment })
        assertEquals(listOf(0..2, 0..2, 0..1), mapped.map { it.cue.range })
        assertEquals(listOf("GPU", "GPU", "is ready."), mapped.map { it.text })
        raw.finish()
    }

    @Test fun longUnbrokenInputIsPreservedAcrossPlannerChunksWithoutInventedSpaces() {
        val original = "word".repeat(150)
        val plan = VoiceStreamingPhrasePlan(original, emptyMap(), 1f)
        val reply = VoiceReplyStreamText()
        val reconstructed = buildString {
            while (true) append(reply.append(0, original, plan.next(8) ?: break))
        }
        assertEquals(original, reconstructed)
        assertEquals(original.indices, reply.projection.range(0, original.length))
    }

    @Test fun replyTimelineCanPassPhraseLimitWhileIndividualChunksStayBounded() {
        val text = VoiceIncrementalTextMap().also { it.append("word") }
        val raw = VoiceStreamFrames.incremental(text)
        repeat(31) { raw.accept(VoicePcmChunk(FloatArray(VOICE_PCM_SAMPLE_RATE) { .1f })) }
        raw.finish()
        assertTrue(raw.frames > VoiceStreamFrames.MAX_STREAM_FRAMES)
        assertThrows(IllegalArgumentException::class.java) {
            raw.accept(VoicePcmChunk(FloatArray(VoiceStreamFrames.MAX_CHUNK_FRAMES + 1)))
        }
    }

    @Test fun inputLimitRejectsTheEntireDeltaAndKeepsEarlierOffsetsValid() {
        val text = VoiceIncrementalTextMap()
        text.append("first")
        assertThrows(IllegalArgumentException::class.java) { text.append("x".repeat(VoiceIncrementalStreamLimits.MAX_INPUT_BYTES)) }
        assertEquals(0..4, text.range(0, 5))
        assertFalse(text.contains(5, 6))
    }
}
