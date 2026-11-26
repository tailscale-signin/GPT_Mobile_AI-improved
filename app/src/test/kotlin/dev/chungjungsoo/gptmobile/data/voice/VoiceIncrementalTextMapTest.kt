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

    @Test fun firstPronunciationMapAfterPlainTextKeepsEarlierAndLaterUtf16Offsets() {
        val text = VoiceIncrementalTextMap()
        text.append("🚀")
        text.append("GPU", listOf(7..9, IntRange.EMPTY, 7..9))
        text.append("é")
        assertEquals(0..1, text.range(0, 4))
        assertEquals(7..9, text.range(4, 7))
        assertEquals(IntRange.EMPTY, text.range(5, 6))
        assertEquals(5..5, text.range(7, 9))
        assertFalse(text.contains(1, 4))
        assertFalse(text.contains(9, 10))
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

    @Test fun exactCeilingSeparatorKeepsTheFollowingWordAndCaptionByteOffsets() {
        val original = "a".repeat(128) + " b"
        val plan = VoiceStreamingPhrasePlan(original, emptyMap(), 1f)
        val reply = VoiceReplyStreamText()
        val deltas = generateSequence { plan.next(8) }.map { reply.append(0, original, it) }.toList()

        assertEquals(listOf("a".repeat(128), " b"), deltas)
        assertEquals(original, deltas.joinToString(""))
        assertEquals(128..128, reply.projection.range(128, 129))
        assertEquals(129..129, reply.projection.range(129, 130))
        val frames = VoiceStreamFrames.incremental(reply.projection)
        val mapped = reply.captions(
            frames.accept(
                VoicePcmChunk(
                    FloatArray(20) { .1f },
                    wordCues = listOf(VoiceWordCue(0, 128, 0, 10), VoiceWordCue(129, 130, 10, 20))
                )
            )
        )
        assertEquals(listOf(0..127, 129..129), mapped.map { it.cue.range })
        assertTrue(mapped.all { it.segment == 0 && it.text == original })
        frames.finish()
    }

    @Test fun originalAndSyntheticSegmentSeparatorsKeepDistinctCaptionOwnership() {
        val first = "a".repeat(128) + " b"
        val second = "café"
        val reply = VoiceReplyStreamText()
        val firstPlan = VoiceStreamingPhrasePlan(first, emptyMap(), 1f)
        val spoken = buildString {
            while (true) append(reply.append(0, first, firstPlan.next(8) ?: break))
            append(reply.append(1, second, VoiceStreamingPhrasePlan(second, emptyMap(), 1f).next(8)!!))
        }

        assertEquals(first + " " + second, spoken)
        assertEquals(128..128, reply.projection.range(128, 129))
        assertEquals(IntRange.EMPTY, reply.projection.range(130, 131))
        val frames = VoiceStreamFrames.incremental(reply.projection)
        val mapped = reply.captions(
            frames.accept(
                VoicePcmChunk(
                    FloatArray(20) { .1f },
                    wordCues = listOf(VoiceWordCue(129, 130, 0, 10), VoiceWordCue(131, 136, 10, 20))
                )
            )
        )
        assertEquals(listOf(0, 1), mapped.map { it.segment })
        assertEquals(listOf(129..129, 0..3), mapped.map { it.cue.range })
        assertEquals(listOf(first, second), mapped.map { it.text })
        frames.finish()
    }

    @Test fun unicodeAndPronunciationMappingsRemainExactAfterAFullChunk() {
        val original = "🙂".repeat(64) + " GPU e\u0301"
        val plan = VoiceStreamingPhrasePlan(original, mapOf("gpu" to "graphics processor"), 1f)
        val reply = VoiceReplyStreamText()
        val deltas = generateSequence { plan.next(8) }.map { reply.append(0, original, it) }.toList()
        val spoken = deltas.joinToString("")

        assertEquals("🙂".repeat(64) + " graphics processor e\u0301", spoken)
        assertTrue(deltas.all { it.length <= 128 && !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
        val firstByte = spoken.substringBefore("graphics").toByteArray(Charsets.UTF_8).size
        val accentByte = spoken.substringBefore("e\u0301").toByteArray(Charsets.UTF_8).size
        assertEquals(129..131, reply.projection.range(firstByte, firstByte + 8))
        assertEquals(129..131, reply.projection.range(firstByte + 9, firstByte + 18))
        assertEquals(133..134, reply.projection.range(accentByte, accentByte + 3))
        assertFalse(reply.projection.contains(accentByte, accentByte + 2))
        assertFalse(reply.projection.contains(1, 4))
    }

    @Test fun anUnrepresentableSeparatorDoesNotChangePreviouslyPublishedByteMappings() {
        val original = "a".repeat(128) + " ".repeat(128) + "b"
        val plan = VoiceStreamingPhrasePlan(original, emptyMap(), 1f)
        val reply = VoiceReplyStreamText()
        assertEquals("a".repeat(128), reply.append(0, original, plan.next(8)!!))

        assertThrows(IllegalArgumentException::class.java) { plan.next(8) }

        assertEquals(0..127, reply.projection.range(0, 128))
        assertFalse(reply.projection.contains(128, 129))
    }
}
