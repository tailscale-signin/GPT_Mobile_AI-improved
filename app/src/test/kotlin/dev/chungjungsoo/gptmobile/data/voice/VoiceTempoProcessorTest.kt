package dev.chungjungsoo.gptmobile.data.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTempoProcessorTest {
    @Test fun normalSpeedPreservesFloatSamplesAndCueObjectsWithoutBuffering() {
        val input = VoicePcmChunk(floatArrayOf(.25f, -.75f, 0f), wordCues = listOf(VoiceWordCue(0, 4, 0, 3)))
        val output = mutableListOf<VoicePcmChunk>()
        val processor = VoiceTempoProcessor(1f)
        processor.accept(input, output::add)
        assertTrue(input === output.single())
        processor.finish(output::add)
        assertEquals(1, output.size)
    }

    @Test fun tempoChangesDurationWithoutRaisingOrLoweringTheFundamentalPitch() {
        val input = tone(160.0, VOICE_PCM_SAMPLE_RATE * 3)
        for (rate in listOf(.5f, .75f, 1.15f, 1.5f, 2f)) {
            val output = render(input, rate, 337).flatMap { it.samples.asIterable() }.toFloatArray()
            val expected = input.size / rate.toDouble()
            assertTrue("$rate duration ${output.size} / $expected", abs(output.size - expected) <= 2)
            val pitch = frequency(output)
            assertEquals("$rate pitch", 160.0, pitch, 2.0)
            assertTrue(output.all { it.isFinite() && abs(it) <= 1f })
        }
    }

    @Test fun arbitrarySdkPacketBoundariesDoNotResetTheProcessorOrAccumulateTimingDrift() {
        val input = FloatArray(VOICE_PCM_SAMPLE_RATE * 12) { index ->
            (.25 * sin(2 * PI * 173 * index / VOICE_PCM_SAMPLE_RATE) + .1 * sin(2 * PI * 346 * index / VOICE_PCM_SAMPLE_RATE)).toFloat()
        }
        val small = render(input, 1.37f, 137).flatMap { it.samples.asIterable() }.toFloatArray()
        val large = render(input, 1.37f, 24_000).flatMap { it.samples.asIterable() }.toFloatArray()
        assertArrayEquals(large, small, 0f)
        assertTrue(abs(small.size - input.size / 1.37) < 3)
    }

    @Test fun captionsFollowTransformedPcmAndRetainProvisionalEndAndTimingProvenance() {
        val input = tone(200.0, VOICE_PCM_SAMPLE_RATE)
        val processor = VoiceTempoProcessor(1.5f)
        val frames = VoiceStreamFrames("word")
        var received = 0L
        var firstObserved = false
        val cues = mutableListOf<VoiceWordCue>()
        val sink: (VoicePcmChunk) -> Unit = { chunk ->
            frames.accept(chunk)
            received += chunk.samples.size
            for (cue in chunk.wordCues) {
                assertTrue(cue.endFrame <= received)
                assertEquals(VoiceWordCueProvenance.ESTIMATED, cue.provenance)
                if (!firstObserved) {
                    assertFalse(cue.finalEnd)
                    firstObserved = true
                }
                cues += cue
            }
        }
        processor.accept(VoicePcmChunk(input.copyOfRange(0, 6_000), wordCues = listOf(VoiceWordCue(0, 4, 0, 6_000, finalEnd = false))), sink)
        assertTrue(firstObserved)
        processor.accept(VoicePcmChunk(input.copyOfRange(6_000, input.size), wordCues = listOf(VoiceWordCue(0, 4, 0, 24_000))), sink)
        processor.finish(sink)
        frames.finish()
        assertTrue(cues.last().finalEnd)
        assertEquals(received, cues.last().endFrame)
        assertEquals(16_000.0, received.toDouble(), 160.0)
    }

    @Test fun correctedCueUsesTheSameAbsoluteTimelineAsItsOriginal() {
        val processor = VoiceTempoProcessor(2f)
        val output = mutableListOf<VoicePcmChunk>()
        processor.accept(VoicePcmChunk(tone(180.0, 12_000), wordCues = listOf(VoiceWordCue(0, 3, 2_400, 4_800))), output::add)
        processor.accept(VoicePcmChunk(FloatArray(0), wordCues = listOf(VoiceWordCue(0, 3, 2_640, 5_040, revision = 1))), output::add)
        processor.finish(output::add)
        val corrected = output.flatMap { it.wordCues }.single { it.revision == 1 }
        assertEquals(1_320.0, corrected.startFrame.toDouble(), 150.0)
        assertEquals(2_520.0, corrected.endFrame.toDouble(), 150.0)
        assertEquals(0, corrected.startByte)
        assertEquals(3, corrected.endByte)
    }

    @Test fun cancellationNeverDrainsOrReusesTheAbandonedProcessorsTail() {
        val first = VoiceTempoProcessor(1.5f)
        val abandoned = mutableListOf<VoicePcmChunk>()
        assertThrows(InterruptedException::class.java) {
            first.accept(VoicePcmChunk(tone(100.0, 24_000))) { throw InterruptedException("cancel") }
        }
        // A replacement reply owns a new processor, not an end-of-stream flush of the old reply.
        val next = render(tone(220.0, 24_000), 1.5f, 1_024).flatMap { it.samples.asIterable() }.toFloatArray()
        assertTrue(abandoned.isEmpty())
        assertEquals(220.0, frequency(next), 3.0)
    }

    @Test fun invalidRatesAndNonFiniteSamplesCannotEnterTheProcessor() {
        for (rate in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, .49f, 2.01f)) {
            assertThrows(IllegalArgumentException::class.java) { VoiceTempoProcessor(rate) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            VoiceTempoProcessor(1.5f).accept(VoicePcmChunk(floatArrayOf(Float.NaN))) { }
        }
    }

    @Test fun presentationTimeTransformRemainsMonotonicAcrossPruningAndNonUniformOperations() {
        val timeline = VoiceTimelineTransform()
        timeline.append(100, 100)
        timeline.append(300, 200)
        timeline.append(500, 300)
        assertEquals(150L, timeline.map(200))
        timeline.discardBefore(250)
        assertEquals(null, timeline.map(50))
        assertEquals(150L, timeline.map(200))
        assertEquals(null, timeline.map(600))
        assertThrows(IllegalArgumentException::class.java) { timeline.append(400, 400) }
    }

    private fun render(samples: FloatArray, rate: Float, packet: Int): List<VoicePcmChunk> {
        val processor = VoiceTempoProcessor(rate)
        val result = mutableListOf<VoicePcmChunk>()
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(samples.size, offset + packet)
            processor.accept(VoicePcmChunk(samples.copyOfRange(offset, end)), result::add)
            offset = end
        }
        processor.finish(result::add)
        return result
    }

    private fun tone(hz: Double, size: Int) = FloatArray(size) { (.5 * sin(2 * PI * hz * it / VOICE_PCM_SAMPLE_RATE)).toFloat() }

    private fun frequency(samples: FloatArray): Double {
        val trim = minOf(VOICE_PCM_SAMPLE_RATE / 10, samples.size / 5)
        val crossings = (trim + 1 until samples.size - trim).count { samples[it - 1] <= 0 && samples[it] > 0 }
        return crossings * VOICE_PCM_SAMPLE_RATE.toDouble() / (samples.size - 2 * trim)
    }
}
