package dev.chungjungsoo.gptmobile.presentation.ui.voice

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCaptionMotionTest {
    @Test fun fastSpeechUsesItsRemainingWordTimeForLineMotion() {
        assertEquals(100, captionWordMotionDurationMs(0, 2_400, 0, 24_000))
        assertEquals(60, captionWordMotionDurationMs(0, 2_400, 2_000, 24_000))
        assertEquals(420, captionWordMotionDurationMs(0, 48_000, 0, 24_000))
        assertEquals(320, captionWordMotionDurationMs(0, 0, 0, 24_000))
        val motion = VoiceCaptionMotion(0f)
        motion.retarget(1f, 0, animate = true, durationMs = 100)
        assertEquals(VoiceCaptionMotionSample(1f, 0f), motion.sample(100_000_000))
        assertFalse(motion.moving(100_000_000))
    }

    @Test fun fastWordColourReachesTheAccentDuringTheWordAndDoesNotTrailSeveralLaterWords() {
        val highlights = VoiceCaptionHighlights()
        highlights.update(0..2, 0, 0, animate = true, wordDurationNanos = 100_000_000)
        assertEquals(1f, highlights.visible(45_000_000).single().colour, 0f)
        highlights.update(4..6, 100_000_000, 100_000_000, animate = true, wordDurationNanos = 100_000_000)
        assertEquals(listOf(4..6), highlights.visible(150_000_000).map { it.range })
        assertEquals(1f, highlights.visible(150_000_000).single().colour, 0f)
    }

    @Test fun pausingFreezesTheColourEnvelopeAlongWithTheWordAndScrollPosition() {
        val highlights = VoiceCaptionHighlights()
        highlights.update(0..2, 0, 0, animate = true, wordDurationNanos = 100_000_000)
        highlights.playing(false, 20_000_000)
        val held = highlights.visible(20_000_000)
        assertEquals(held, highlights.visible(5_000_000_000))
        assertFalse(highlights.animating(5_000_000_000))
        highlights.playing(true, 5_000_000_000)
        assertEquals(held, highlights.visible(5_000_000_000))
        assertEquals(1f, highlights.visible(5_025_000_000).single().colour, 0f)
    }

    @Test fun theFiveSettledRowsRetainTheirTypographyAndOpacity() {
        assertEquals(listOf(1f, 1.5f, 2f, 1.5f, 1f), (-2..2).map { captionLineScale(it.toFloat()) })
        assertEquals(listOf(.5f, .75f, 1f, .75f, .5f), (-2..2).map { captionLineAlpha(it.toFloat()) })
        assertEquals(listOf(-3.125f, -1.8125f, 0f, 1.8125f, 3.125f), (-2..2).map { captionLineDisplacement(it.toFloat()) })
    }

    @Test fun everyIntermediateFocusHasAtMostFiveVisibleLinesAndContinuousGeometry() {
        for (step in 0..2000) {
            val focus = step / 1000f
            assertTrue((-4..6).count { captionLineAlpha(it - focus) > 0f } <= 5)
            for (line in -4..6) {
                val before = line - focus
                val after = before + .00001f
                assertTrue(abs(captionLineScale(before) - captionLineScale(after)) < .0001f)
                assertTrue(abs(captionLineAlpha(before) - captionLineAlpha(after)) < .0001f)
                assertTrue(abs(captionLineDisplacement(before) - captionLineDisplacement(after)) < .0001f)
            }
        }
    }

    @Test fun fractionalMotionKeepsNeighbouringLineBoxesFromOverlappingAtTheOuterEdge() {
        for (step in 0..2000) {
            val focus = step / 1000f
            val visible = (-4..6).filter { captionLineAlpha(it - focus) > 0f }
            visible.zipWithNext().forEach { (upper, lower) ->
                val upperBottom = captionLineDisplacement(upper - focus) + captionLineScale(upper - focus) / 2f
                val lowerTop = captionLineDisplacement(lower - focus) - captionLineScale(lower - focus) / 2f
                assertTrue("Caption rows $upper and $lower overlap at $focus", upperBottom <= lowerTop + .0001f)
            }
        }
    }

    @Test fun outerRowsCrossTheScaleBoundaryWithoutChangingTheirVerticalVelocity() {
        val crossing = 160_000_000L
        val step = 100_000L
        val seconds = step / 1_000_000_000f
        for (direction in listOf(-1f, 1f)) {
            val motion = VoiceCaptionMotion(0f)
            motion.retarget(4f * direction, 0, animate = true)
            assertTrue(abs(motion.sample(crossing).velocity) > 1f)
            for (line in listOf(0f, 4f * direction)) {
                assertEquals(2f, abs(line - motion.sample(crossing).position), .0001f)
                fun displacement(time: Long) = captionLineDisplacement(line - motion.sample(time).position)
                val beforeVelocity = (displacement(crossing) - displacement(crossing - step)) / seconds
                val afterVelocity = (displacement(crossing + step) - displacement(crossing)) / seconds
                assertEquals("Row $line changes velocity across the scale boundary", beforeVelocity, afterVelocity, .05f)
            }
        }
    }

    @Test fun aLineMovesThroughIntermediatePositionsInsteadOfReplacingRows() {
        val motion = VoiceCaptionMotion(2f)
        motion.retarget(3f, 0, animate = true)
        assertEquals(2f, motion.sample(0).position, 0f)
        assertEquals(2.5f, motion.sample(160_000_000).position, .0001f)
        assertEquals(3f, motion.sample(320_000_000).position, 0f)
        assertEquals(0f, motion.sample(320_000_000).velocity, 0f)
    }

    @Test fun retargetingKeepsTheCurrentPositionAndVelocityAtEveryRefreshRate() {
        for (hz in listOf(60, 90, 120, 165)) {
            val motion = VoiceCaptionMotion(0f)
            motion.retarget(1f, 0, animate = true)
            val changedAt = 137_000_000L
            val before = motion.sample(changedAt)
            motion.retarget(4f, changedAt, animate = true)
            assertEquals(before, motion.sample(changedAt))
            var previous = before.position
            for (frame in 1..hz) {
                val current = motion.sample(changedAt + frame * 1_000_000_000L / hz).position
                assertTrue("$hz Hz frame $frame moved backwards", current >= previous)
                assertTrue(current <= 4f)
                previous = current
            }
            assertEquals(4f, previous, 0f)
        }
    }

    @Test fun aFastForwardRetargetStopsAtTheNextLineWithoutReversing() {
        for (direction in listOf(-1f, 1f)) {
            for (skippedLines in listOf(8f, 16f)) {
                val motion = VoiceCaptionMotion(0f)
                motion.retarget(skippedLines * direction, 0, animate = true)
                val changedAt = 250_000_000L
                val before = motion.sample(changedAt)
                motion.retarget((skippedLines + 1f) * direction, changedAt, animate = true)
                assertEquals(before, motion.sample(changedAt))
                assertMonotonicArrival(motion, changedAt, before.position, direction)
            }
        }
    }

    @Test fun forwardRetargetsStayBoundedAcrossCloseTargetsAndLateCallbacks() {
        for (direction in listOf(-1f, 1f)) {
            for (skippedLines in listOf(1f, 16f, 200f)) {
                for (changedAt in listOf(1L, 1_000_000L, 160_000_000L, 319_000_000L, 320_000_000L)) {
                    for (distance in listOf(.0001f, .25f, 1f)) {
                        val motion = VoiceCaptionMotion(0f)
                        motion.retarget(skippedLines * direction, 0, animate = true)
                        val before = motion.sample(changedAt)
                        motion.retarget(before.position + distance * direction, changedAt, animate = true)
                        assertEquals(before, motion.sample(changedAt))
                        assertMonotonicArrival(motion, changedAt, before.position, direction)
                    }
                }
            }
        }
    }

    @Test fun aSubNanosecondArrivalKeepsMomentumAtRetargetAndSettlesOnTheNextTick() {
        for (direction in listOf(-1f, 1f)) {
            val motion = VoiceCaptionMotion(0f)
            motion.retarget(direction, 0, animate = true)
            val before = motion.sample(1L)
            val target = if (direction > 0) Math.nextUp(before.position) else Math.nextDown(before.position)
            assertTrue(3.0 * abs(target.toDouble() - before.position) / abs(before.velocity.toDouble()) < 1e-9)
            motion.retarget(target, 1L, animate = true)
            assertEquals(before, motion.sample(1L))
            assertTrue(motion.moving(1L))
            assertEquals(VoiceCaptionMotionSample(target, 0f), motion.sample(2L))
            assertFalse(motion.moving(2L))
        }
    }

    @Test fun aShortenedRetargetCanPauseRetargetWhileHeldAndResumeWithoutAJump() {
        for (direction in listOf(-1f, 1f)) {
            val motion = VoiceCaptionMotion(0f)
            motion.retarget(16f * direction, 0, animate = true)
            motion.retarget(17f * direction, 250_000_000L, animate = true)
            motion.playing(false, 270_000_000L)
            val held = motion.sample(270_000_000L)
            assertEquals(held, motion.sample(3_000_000_000L))
            motion.retarget(held.position + .25f * direction, 3_000_000_000L, animate = true)
            assertEquals(held, motion.sample(4_000_000_000L))
            assertFalse(motion.moving(4_000_000_000L))
            motion.playing(true, 4_000_000_000L)
            assertEquals(held, motion.sample(4_000_000_000L))
            assertMonotonicArrival(motion, 4_000_000_000L, held.position, direction)
        }
    }

    @Test fun aReverseTargetPreservesItsIncomingVelocityBeforeBraking() {
        for (direction in listOf(-1f, 1f)) {
            val motion = VoiceCaptionMotion(0f)
            motion.retarget(8f * direction, 0, animate = true)
            val changedAt = 160_000_000L
            val before = motion.sample(changedAt)
            motion.retarget(-direction, changedAt, animate = true)
            assertEquals(before, motion.sample(changedAt))
            assertTrue(motion.sample(changedAt + 1_000L).velocity * direction > 0f)
            assertEquals(VoiceCaptionMotionSample(-direction, 0f), motion.sample(changedAt + 320_000_000L))
        }
    }

    private fun assertMonotonicArrival(motion: VoiceCaptionMotion, changedAt: Long, position: Float, direction: Float) {
        val samples = (0L..64L) + listOf(100L, 250L, 500L, 1_000L, 10_000L, 100_000L) + (1..420).map { it * 1_000_000L }
        var previous = position * direction
        for (elapsed in samples) {
            val current = motion.sample(changedAt + elapsed)
            val directedPosition = current.position * direction
            assertTrue("Retarget to ${motion.target} reversed at $elapsed ns", directedPosition >= previous)
            assertTrue("Retarget to ${motion.target} overshot at $elapsed ns", directedPosition <= motion.target * direction)
            assertTrue("Retarget to ${motion.target} has reverse velocity at $elapsed ns", current.velocity * direction >= 0f)
            previous = directedPosition
        }
        assertEquals(VoiceCaptionMotionSample(motion.target, 0f), motion.sample(changedAt + 420_000_000L))
        assertFalse(motion.moving(changedAt + 420_000_000L))
    }

    @Test fun severalSkippedLinesStillTravelInsteadOfSnapping() {
        val motion = VoiceCaptionMotion(1f)
        motion.retarget(8f, 1_000_000, animate = true)
        assertEquals(1f, motion.sample(1_000_000).position, 0f)
        assertTrue(motion.sample(100_000_000).position in 1.01f..7.99f)
        assertEquals(8f, motion.sample(500_000_000).position, 0f)
    }

    @Test fun aPlaybackRefillFreezesTheTrajectoryAndResumesWithoutAJump() {
        val motion = VoiceCaptionMotion(0f)
        motion.retarget(1f, 0, animate = true)
        motion.playing(false, 100_000_000)
        val held = motion.sample(100_000_000)
        assertEquals(held, motion.sample(3_000_000_000))
        assertFalse(motion.moving(3_000_000_000))
        motion.playing(true, 3_000_000_000)
        assertEquals(held, motion.sample(3_000_000_000))
        assertEquals(1f, motion.sample(3_220_000_000).position, 0f)
    }

    @Test fun manualSeekingAndReducedMotionAreImmediateAndDoNotLeaveMomentum() {
        val motion = VoiceCaptionMotion(5f)
        motion.retarget(6f, 0, animate = true)
        motion.seek(2.25f, 100_000_000)
        assertEquals(VoiceCaptionMotionSample(2.25f, 0f), motion.sample(100_000_000))
        motion.retarget(9f, 200_000_000, animate = false)
        assertEquals(VoiceCaptionMotionSample(9f, 0f), motion.sample(200_000_000))
        assertFalse(motion.moving(200_000_000))
    }

    @Test fun widthReflowKeepsTheSameSourceAnchorAndFractionalReadingPosition() {
        val before = listOf(VoiceCaptionLine(0, 10), VoiceCaptionLine(10, 20), VoiceCaptionLine(20, 30))
        val after = listOf(VoiceCaptionLine(0, 5), VoiceCaptionLine(5, 10), VoiceCaptionLine(10, 15), VoiceCaptionLine(15, 20), VoiceCaptionLine(20, 30))
        assertEquals(2.25f, captionReflowPosition(before, after, 1.25f), 0f)
    }

    @Test fun rtlAndEmojiSourceAnchorsSurviveAFontSizeReflow() {
        val text = "مرحبا بالعالم 👋 مرحبا من تورونتو"
        val first = text.indexOf(' ')
        val emoji = text.indexOf("👋")
        val afterEmoji = emoji + "👋 ".length
        val tail = text.indexOf("من")
        val before = listOf(VoiceCaptionLine(0, emoji), VoiceCaptionLine(emoji, tail), VoiceCaptionLine(tail, text.length))
        val after = listOf(VoiceCaptionLine(0, first), VoiceCaptionLine(first + 1, emoji), VoiceCaptionLine(emoji, afterEmoji), VoiceCaptionLine(afterEmoji, tail), VoiceCaptionLine(tail, text.length))
        val mapped = captionReflowPosition(before, after, 1.4f)
        assertEquals(2.4f, mapped, .0001f)
        assertEquals("👋", text.substring(after[2].start, after[2].start + 2))
        assertFalse(text[after[2].start].isLowSurrogate())
    }

    @Test fun layoutReanchoringPreservesTheVisibleLinesRelativePositionAndVelocity() {
        val motion = VoiceCaptionMotion(3f)
        motion.retarget(4f, 0, animate = true)
        val before = motion.sample(100_000_000)
        motion.reanchor(2f)
        val after = motion.sample(100_000_000)
        assertEquals(4f - before.position, 6f - after.position, .0001f)
        assertEquals(before.velocity, after.velocity, .0001f)
    }

    @Test fun aWordTakesExactly250MillisecondsToReachTheThemeColour() {
        val highlights = VoiceCaptionHighlights()
        highlights.update(4..8, 0, 0, animate = true)
        assertEquals(0f, highlights.visible(0).single().colour, 0f)
        assertEquals(.5f, highlights.visible(125_000_000).single().colour, .0001f)
        assertEquals(.996f, highlights.visible(249_000_000).single().colour, .0001f)
        assertEquals(1f, highlights.visible(250_000_000).single().colour, 0f)
        assertTrue(highlights.visible(0).single().active)
        assertFalse(highlights.animating(250_000_000))
    }

    @Test fun aShortWordFinishesItsColourEnvelopeWithoutDelayingTheNextBoldWord() {
        val highlights = VoiceCaptionHighlights()
        highlights.update(0..1, 0, 0, animate = true)
        highlights.update(3..7, 100_000_000, 100_000_000, animate = true)
        val boundary = highlights.visible(100_000_000)
        assertEquals(listOf(3..7), boundary.filter { it.active }.map { it.range })
        assertEquals(.4f, boundary.first { it.range == 0..1 }.colour, .0001f)
        assertEquals(1f, highlights.visible(250_000_000).first { it.range == 0..1 }.colour, 0f)
        assertEquals(.5f, highlights.visible(310_000_000).first { it.range == 0..1 }.colour, .0001f)
        assertTrue(highlights.visible(370_000_000).none { it.range == 0..1 })
        assertEquals(listOf(3..7), highlights.visible(370_000_000).filter { it.active }.map { it.range })
    }

    @Test fun repeatedTimingUpdatesDoNotRestartAWordFade() {
        val highlights = VoiceCaptionHighlights()
        highlights.update(2..5, 0, 0, animate = true)
        highlights.update(2..5, 100_000_000, 100_000_000, animate = true)
        assertEquals(1f, highlights.visible(250_000_000).single().colour, 0f)
    }

    @Test fun silenceReleasesBoldImmediatelyAndBoundsTheCosmeticTrail() {
        val highlights = VoiceCaptionHighlights()
        highlights.update(0..3, 0, 0, animate = true)
        highlights.update(null, 50_000_000, 50_000_000, animate = true)
        assertTrue(highlights.visible(50_000_000).none { it.active })
        assertTrue(highlights.visible(370_000_000).isEmpty())
        assertFalse(highlights.animating(370_000_000))
    }

    @Test fun aDenseCallbackBurstCannotAccumulateAnUnboundedTranscript() {
        val highlights = VoiceCaptionHighlights()
        repeat(1000) { highlights.update(it * 2..it * 2, 0, 0, animate = true) }
        assertTrue(highlights.visible(10_000_000).size <= 12)
        assertEquals(1, highlights.visible(10_000_000).count { it.active })
    }

    @Test fun reducedMotionUsesOneImmediateHighlightWithoutACosmeticTrail() {
        val highlights = VoiceCaptionHighlights()
        highlights.update(0..3, 0, 0, animate = false)
        assertEquals(VoiceCaptionHighlight(0..3, true, 1f), highlights.visible(0).single())
        highlights.update(5..8, 0, 0, animate = false)
        assertEquals(VoiceCaptionHighlight(5..8, true, 1f), highlights.visible(0).single())
        assertFalse(highlights.animating(0))
    }

    @Test fun wordAgeUsesMeasuredAudioAndCannotAdvancePastBufferedFrames() {
        assertEquals(800_000_000L, captionWordOnsetNanos(1000, 5000, 1100, 4000, 1000, 900_000_000, 1_000_000_000, true))
        assertEquals(850_000_000L, captionWordOnsetNanos(1000, 5000, 1100, 1150, 1000, 900_000_000, 1_000_000_000, true))
        assertEquals(900_000_000L, captionWordOnsetNanos(1000, 5000, 1100, 4000, 1000, 900_000_000, 1_000_000_000, false))
    }

    @Test fun invalidOrFutureTimingNeverFabricatesAnEarlierWord() {
        assertEquals(1_000_000_000L, captionWordOnsetNanos(1000, 5000, 900, 4000, 1000, 2_000_000_000, 1_000_000_000, true))
        assertEquals(1_000_000_000L, captionWordOnsetNanos(1000, 1000, 1500, 4000, 1000, 900_000_000, 1_000_000_000, true))
        assertEquals(1_000_000_000L, captionWordOnsetNanos(1000, 5000, 1500, 4000, 0, 900_000_000, 1_000_000_000, true))
    }
}
