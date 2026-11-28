package dev.chungjungsoo.gptmobile.data.voice

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTempoCaptionRegressionTest {
    @Test fun aCollapsedUnheardCueDoesNotBlockSubsequentWordsOrCreateFakeOutputTime() {
        val processor = VoiceTempoProcessor(2f)
        val frames = VoiceStreamFrames("a b c")
        val cues = mutableListOf<VoiceWordCue>()
        val consume: (VoicePcmChunk) -> Unit = { chunk ->
            frames.accept(chunk)
            cues += chunk.wordCues
        }
        processor.accept(
            VoicePcmChunk(
                tone(4_000),
                wordCues = listOf(VoiceWordCue(0, 1, 0, 1), VoiceWordCue(2, 3, 1, 2), VoiceWordCue(4, 5, 2, 4_000))
            ),
            consume
        )
        processor.finish(consume)
        frames.finish()
        assertTrue(cues.all { it.endFrame > it.startFrame })
        assertFalse(cues.any { it.startByte == 2 })
        assertEquals(4, cues.last().startByte)
        assertTrue(cues.last().finalEnd)
    }

    @Test fun revisionsRemainSequentialWhenAnEarlierCorrectionWaitsInTempoLookahead() {
        for (speed in listOf(.75f, 1.5f, 2f)) {
            val processor = VoiceTempoProcessor(speed)
            val frames = VoiceStreamFrames("one two")
            val revisions = mutableListOf<Int>()
            val consume: (VoicePcmChunk) -> Unit = { chunk ->
                frames.accept(chunk)
                revisions += chunk.wordCues.filter { it.startByte == 0 && it.revision > 0 }.map { it.revision }
            }
            processor.accept(
                VoicePcmChunk(tone(6_500), wordCues = listOf(VoiceWordCue(0, 3, 1_000, 2_000), VoiceWordCue(4, 7, 6_100, 6_400))),
                consume
            )
            processor.accept(VoicePcmChunk(FloatArray(0), wordCues = listOf(VoiceWordCue(0, 3, 1_000, 6_000, revision = 1))), consume)
            processor.accept(VoicePcmChunk(FloatArray(0), wordCues = listOf(VoiceWordCue(0, 3, 1_000, 5_500, revision = 2))), consume)
            processor.finish(consume)
            frames.finish()
            assertEquals(listOf(1, 2), revisions)
        }
    }

    @Test fun finalizedAndProvisionalCueEndsStayValidForShortTailsAtEveryTempo() {
        for (speed in listOf(.5f, .75f, 1.15f, 1.5f, 2f)) {
            for (size in listOf(1, 31, 300, 737, 738, 739, 1_480)) {
                val processor = VoiceTempoProcessor(speed)
                var outputFrames = 0L
                val cues = mutableListOf<VoiceWordCue>()
                val consume: (VoicePcmChunk) -> Unit = { chunk ->
                    outputFrames += chunk.samples.size
                    cues += chunk.wordCues
                    assertTrue(chunk.wordCues.all { it.endFrame <= outputFrames && it.endFrame > it.startFrame })
                }
                processor.accept(VoicePcmChunk(tone(size), wordCues = listOf(VoiceWordCue(0, 4, 0, size.toLong()))), consume)
                processor.finish(consume)
                if (cues.isNotEmpty()) {
                    assertTrue(cues.last().finalEnd)
                    assertEquals(outputFrames, cues.last().endFrame)
                }
            }
        }
    }

    @Test fun finalCaptionWaitsForTheSonicTailWithoutMovingAnEarlierFinalizedWord() {
        val processor = VoiceTempoProcessor(1.5f)
        val frames = VoiceStreamFrames("one two")
        val input = FloatArray(VOICE_PCM_SAMPLE_RATE) { (.5 * sin(2 * PI * 200.0 * it / VOICE_PCM_SAMPLE_RATE)).toFloat() }
        val cues = mutableListOf<VoiceWordCue>()
        var outputFrames = 0L
        val consume: (VoicePcmChunk) -> Unit = { chunk ->
            frames.accept(chunk)
            outputFrames += chunk.samples.size
            cues += chunk.wordCues
        }
        processor.accept(
            VoicePcmChunk(input.copyOfRange(0, 6_000), wordCues = listOf(VoiceWordCue(0, 3, 0, 6_000, finalEnd = false))),
            consume
        )
        processor.accept(
            VoicePcmChunk(
                input.copyOfRange(6_000, input.size),
                wordCues = listOf(VoiceWordCue(0, 3, 0, 12_000), VoiceWordCue(4, 7, 12_000, 24_000))
            ),
            consume
        )
        val firstFinal = cues.single { it.startByte == 0 && it.finalEnd }
        assertFalse(cues.any { it.startByte == 4 && it.finalEnd })
        val beforeTail = outputFrames
        processor.finish(consume)
        frames.finish()
        assertTrue(outputFrames > beforeTail)
        assertEquals(16_000L, outputFrames)
        assertEquals(outputFrames, cues.last().endFrame)
        assertTrue(cues.last().finalEnd)
        assertEquals(firstFinal, cues.single { it.startByte == 0 && it.finalEnd })
    }

    @Test fun aZeroInputFinalTailChangesOnlyTheTerminalTimelineBoundary() {
        val timeline = VoiceTimelineTransform()
        timeline.append(100, 60)
        val earlier = (0L until 100).map(timeline::map)
        timeline.finish(100, 65)
        assertEquals(earlier, (0L until 100).map(timeline::map))
        assertEquals(65L, timeline.map(100))
        assertEquals(65L, timeline.outputEnd)
        assertTrue((0L..100).map { requireNotNull(timeline.map(it)) }.zipWithNext().all { (before, after) -> before <= after })
    }

    @Test fun aCorrectionEndingAtTheCurrentFrontierAlsoWaitsForTheFinalAudioTail() {
        val processor = VoiceTempoProcessor(1.05f)
        val frames = VoiceStreamFrames("word")
        val input = FloatArray(738) { (.5 * sin(2 * PI * 65.0 * it / VOICE_PCM_SAMPLE_RATE)).toFloat() }
        var outputFrames = 0L
        val cues = mutableListOf<VoiceWordCue>()
        val consume: (VoicePcmChunk) -> Unit = { chunk ->
            frames.accept(chunk)
            outputFrames += chunk.samples.size
            cues += chunk.wordCues
        }
        processor.accept(VoicePcmChunk(input, wordCues = listOf(VoiceWordCue(0, 4, 0, 100))), consume)
        processor.accept(VoicePcmChunk(FloatArray(0), wordCues = listOf(VoiceWordCue(0, 4, 0, 738, revision = 1))), consume)
        assertFalse(cues.any { it.revision > 0 })
        processor.finish(consume)
        frames.finish()
        assertEquals(1, cues.last().revision)
        assertEquals(outputFrames, cues.last().endFrame)
    }

    private fun tone(size: Int) = FloatArray(size) { (.5 * sin(2 * PI * 180.0 * it / VOICE_PCM_SAMPLE_RATE)).toFloat() }
}
