package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.ArrayBlockingQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSynthesisPerformanceTest {
    @Test fun aColdIncrementalStreamPrimesForRecurringDeliveryInsteadOfChargingStartupTwice() {
        val policy = VoiceAdaptivePlayback(VoicePerformanceMode.AUTO)
        policy.beginStream(incrementalInput = true)
        policy.observeStreamDelivery(1_500, 0)
        policy.observeStreamDelivery(300, 1_200)
        assertEquals(100, policy.bufferMs(startup = true))
        assertEquals(60L, policy.primeWaitMs(startup = true))
        policy.observeStreamDelivery(700, 2_400)
        assertEquals(840, policy.bufferMs())
        repeat(40) { policy.observeStreamDelivery(0, 2_400) }
        assertEquals(840, policy.bufferMs())
        policy.endStream(0)
    }

    @Test fun incrementalFollowupsUsePcmHeadroomBeforeEndOfInput() {
        val policy = VoiceAdaptivePlayback(VoicePerformanceMode.AUTO)
        assertEquals(6, policy.phraseWords(0))
        policy.beginStream()
        policy.observeStreamDelivery(350, 24000)
        assertEquals(6, policy.phraseWords(450))
        assertEquals(12, policy.phraseWords(900))
        assertEquals(16, policy.phraseWords(1400))
        policy.observeStreamDelivery(1200, 24000)
        assertEquals(6, policy.phraseWords(1400))
        policy.endStream(0)
    }

    @Test fun aSlowNativePhraseDoesNotHoldFiveSecondsOfReadyPcmUntilTheDeadline() {
        val policy = VoiceAdaptivePlayback(VoicePerformanceMode.AUTO)
        assertNull(policy.nativeReserveExit(5_425, 5_700, 0, 0))
        policy.observe(9_000, 5_425)
        assertEquals(VoicePcmReserveExit.NEXT_CHUNK_LATE, policy.nativeReserveExit(5_425, 5_700, 100, 0))
        assertNull(policy.nativeReserveExit(500, 5_700, 100, 0))
        assertNull(policy.nativeReserveExit(5_425, 5_700, 4_000, 0))
        val exit = KokoroPcmBatches.awaitReserve(ArrayBlockingQueue(60), 144_000, { 130_200 }, { true }, { true }, { false }, { VoicePcmReserveExit.NEXT_CHUNK_LATE }) { error("unexpected poll") }
        assertEquals(VoicePcmReserveExit.NEXT_CHUNK_LATE, exit)
        policy.beginStream()
        assertNull(policy.nativeReserveExit(5_425, 5_700, 100, 8))
    }

    @Test fun firstAudioBudgetIncludesModelPreparationAndBothPrimingStages() {
        assertEquals(0L, VoiceAdaptivePlayback.startupWaitMs(8_909, 3_745))
        assertEquals(1_998L, VoiceAdaptivePlayback.startupWaitMs(3_002, 3_226))
        assertEquals(0L, VoiceAdaptivePlayback.startupWaitMs(5_000, 60))
        assertEquals(60L, VoiceAdaptivePlayback.startupWaitMs(1_200, 60))
    }

    @Test fun primingCountsAudioThatCanPlayDuringTheNextNativePhrase() {
        val policy = VoiceAdaptivePlayback(VoicePerformanceMode.BALANCED)
        policy.observe(4_000, 3_000, spokenWords = 8)
        // The 10-word phrase is estimated at six seconds. The existing PCM covers five of those seconds.
        assertNull(policy.nativeReserveExit(5_000, 2_000, 0, 10))
        assertEquals(VoicePcmReserveExit.NEXT_CHUNK_COVERED, policy.nativeReserveExit(5_000, 2_000, 1_100, 10))
        assertEquals(VoicePcmReserveExit.NEXT_CHUNK_LATE, policy.nativeReserveExit(1_000, 2_000, 0, 10))
        assertNull(policy.nativeReserveExit(5_000, 2_000, 7_000, 10))
        policy.beginStream()
        assertNull(policy.nativeReserveExit(5_000, 2_000, 1_100, 10))
    }

    @Test fun pcmViewsRetainEveryFrameWithoutCopyingThePhrase() {
        val samples = FloatArray(24_007) { it.toFloat() }
        val slices = mutableListOf<VoicePcmSlice>()
        assertTrue(
            KokoroPcmBatches.slices(samples, 24_000) {
                slices += it
                true
            }
        )
        assertEquals(samples.size, slices.sumOf { it.size })
        var next = 0
        slices.forEach {
            assertSame(samples, it.samples)
            assertEquals(next, it.offset)
            assertTrue(it.size in 1..2_400)
            next += it.size
        }
    }

    @Test fun cancellingPcmDeliveryStopsAtTheRejectedView() {
        var count = 0
        assertFalse(KokoroPcmBatches.slices(FloatArray(48_000), 24_000) { ++count < 3 })
        assertEquals(3, count)
    }

    @Test fun autoBufferRespondsToMeasuredProductionAndRefills() {
        val policy = VoiceAdaptivePlayback(VoicePerformanceMode.AUTO)
        assertEquals(100, policy.bufferMs())
        policy.observe(100, 1_000)
        assertTrue(policy.bufferMs() >= 100)
        assertEquals(6, policy.phraseWords(100))
        assertEquals(16, policy.phraseWords(2_000))
        policy.underrun()
        assertTrue(policy.bufferMs() > 300)
        repeat(20) {
            policy.observe(2_000, 1_000)
            policy.underrun()
        }
        assertTrue(policy.bufferMs() >= 2_000)
        assertTrue(policy.bufferMs() < KokoroPcmBatches.QUEUE_LIMIT_MS)
        assertTrue(requireNotNull(policy.ratio()) > 1.0)
    }

    @Test fun unfinishedStreamReservesItsSlowDeliveryDespiteImmediateSubdivisions() {
        val policy = VoiceAdaptivePlayback(VoicePerformanceMode.AUTO)
        policy.beginStream()
        policy.observeStreamDelivery(1_200, 2_400)
        repeat(40) { policy.observeStreamDelivery(0, 2_400) }
        policy.underrun()
        assertNull("No complete phrase has been observed", policy.predictedSynthesisMs())
        assertNull(policy.ratio())
        assertEquals("The slow delivery still protects limited PCM headroom", 6, policy.phraseWords(1_400))
        assertEquals("Measured PCM headroom permits a bounded followup", 12, policy.phraseWords(2_100))
        assertEquals("Ample PCM headroom permits followups before stream completion", 16, policy.phraseWords(6_000))
        val queue = ArrayBlockingQueue<VoicePcmSlice>(KokoroPcmBatches.QUEUE_BATCHES)
        var acceptedFrames = 2_400L
        var polls = 0
        val exit = KokoroPcmBatches.awaitReserve(
            queue,
            24_000L * policy.bufferMs() / 1_000,
            acceptedFrames = { acceptedFrames },
            canWait = { true },
            producerPending = { true },
            deadlineReached = { polls > 20 },
            poll = {
                polls++
                acceptedFrames += 2_400
            }
        )
        assertEquals(VoicePcmReserveExit.TARGET, exit)
        assertEquals("The original 100 ms reserve would release without one poll", 16, polls)
        assertEquals(1_650L, policy.primeWaitMs())
        policy.endStream(0)
    }

    @Test fun completeStreamCostDoesNotReplaceItsLiveDeliveryReserve() {
        val policy = VoiceAdaptivePlayback(VoicePerformanceMode.AUTO)
        policy.beginStream()
        policy.observeStreamDelivery(400, 24_000)
        repeat(20) { policy.observeStreamDelivery(0, 2_400) }
        policy.endStream(200)
        policy.observe(5_000, 5_000, spokenWords = 16)
        assertEquals(5_000L, policy.predictedSynthesisMs())
        assertEquals(1.0, requireNotNull(policy.ratio()), 0.0)
        assertEquals(480, policy.bufferMs())
        assertEquals(650L, policy.primeWaitMs())
        policy.beginStream()
        policy.observeStreamDelivery(300, 24_000)
        // Retirement after the preceding PCM and the new first delivery form one 500 ms gap.
        assertEquals(600, policy.bufferMs())
        assertEquals(750L, policy.primeWaitMs())
        policy.endStream(0)
    }

    @Test fun trimmedCallbacksAndRetirementAccumulateUntilTheNextRetainedPcm() {
        val policy = VoiceAdaptivePlayback(VoicePerformanceMode.AUTO)
        policy.beginStream()
        policy.observeStreamDelivery(100, 2_400)
        policy.observeStreamDelivery(350, 0)
        policy.observeStreamDelivery(450, 0)
        policy.endStream(200)
        policy.beginStream()
        policy.observeStreamDelivery(250, 2_400)
        assertEquals(1_500, policy.bufferMs())
        assertEquals(1_500L, policy.primeWaitMs())
        assertNull(policy.predictedSynthesisMs())
        policy.endStream(0)
    }

    @Test fun deliveryHistoryAgesOnlyWhenActualStreamsComplete() {
        val policy = VoiceAdaptivePlayback(VoicePerformanceMode.AUTO)
        policy.beginStream()
        policy.observeStreamDelivery(1_200, 2_400)
        policy.endStream(0)
        repeat(7) {
            policy.beginStream()
            repeat(40) { policy.observeStreamDelivery(0, 2_400) }
            assertEquals(1_440, policy.bufferMs())
            policy.endStream(0)
        }
        assertEquals(1_440, policy.bufferMs())
        policy.beginStream()
        policy.observeStreamDelivery(0, 2_400)
        policy.endStream(0)
        assertEquals(100, policy.bufferMs())
    }

    @Test fun streamingRefillsLearnBeforeTimingAndStayWithinExistingBounds() {
        val policy = VoiceAdaptivePlayback(VoicePerformanceMode.FAST)
        policy.beginStream()
        policy.underrun()
        assertEquals(245, policy.bufferMs())
        assertEquals(200L, policy.primeWaitMs())
        repeat(20) { policy.underrun() }
        policy.observeStreamDelivery(Long.MAX_VALUE, 0)
        policy.observeStreamDelivery(Long.MAX_VALUE, 2_400)
        assertEquals(VoiceAdaptivePlayback.MAX_RESERVE_MS, policy.bufferMs())
        assertEquals(VoiceAdaptivePlayback.MAX_RESERVE_MS.toLong(), policy.primeWaitMs())
        policy.endStream(0)
        val legacy = VoiceAdaptivePlayback(VoicePerformanceMode.FAST)
        legacy.underrun()
        assertEquals(45, legacy.bufferMs())
        assertEquals(0L, legacy.primeWaitMs())
        legacy.observe(4_000, 5_000, 1_000)
        assertEquals(1_250, legacy.bufferMs(startup = true))
        assertEquals(1_250L, legacy.primeWaitMs(startup = true))
        assertEquals(4_400, legacy.bufferMs())
        assertEquals(4_250L, legacy.primeWaitMs())
    }

    @Test fun explicitPhraseModesKeepTheirLimitsButAllModesProtectMeasuredContinuity() {
        val fixed = VoiceAdaptivePlayback(VoicePerformanceMode.FAST)
        fixed.observe(6_000, 100)
        fixed.underrun()
        assertEquals(VoiceAdaptivePlayback.MAX_RESERVE_MS, fixed.bufferMs())
        assertEquals(12, fixed.phraseWords(2_000))
        assertEquals(16, VoiceAdaptivePlayback(VoicePerformanceMode.BALANCED).phraseWords(2_000))
        val auto = VoiceAdaptivePlayback(VoicePerformanceMode.AUTO)
        auto.observe(-1, 1_000)
        auto.observe(1_000, 0)
        assertEquals(100, auto.bufferMs())
    }

    @Test fun qualityUsesAShortOpeningAndEarnsLongPhrasesFromMeasuredRefillHeadroom() {
        val quality = VoiceAdaptivePlayback(VoicePerformanceMode.QUALITY)
        assertEquals(6, quality.phraseWords(5_000))
        quality.observe(900, 2_000, recurringMs = 600, spokenWords = 6)
        assertEquals(6, quality.phraseWords(200))
        assertEquals(10, quality.phraseWords(1_200))
        assertEquals(24, quality.phraseWords(5_000))
        val slow = VoiceAdaptivePlayback(VoicePerformanceMode.QUALITY)
        slow.observe(2_400, 1_200, spokenWords = 6)
        assertEquals(8, slow.phraseWords(10_000))
    }

    @Test fun productionTailRetainsSlowPhrasesAndReservesRemainBounded() {
        val policy = VoiceAdaptivePlayback(VoicePerformanceMode.AUTO)
        policy.observe(2_500, 4_000)
        repeat(7) { policy.observe(100, 1_000) }
        assertEquals(2_500L, policy.predictedSynthesisMs())
        assertTrue(policy.bufferMs() >= 2_500)
        assertTrue(policy.primeWaitMs() >= 2_500)
        policy.observe(100, 1_000)
        assertEquals(100L, policy.predictedSynthesisMs())
        policy.observe(30_000, 1_000)
        assertTrue(policy.primeWaitMs() < KokoroPcmBatches.QUEUE_LIMIT_MS)
        assertTrue(policy.bufferMs() < KokoroPcmBatches.QUEUE_LIMIT_MS)
    }

    @Test fun capturedFastSynthesisTailAndSevenUnderrunsUseAvailableQueueHeadroom() {
        val policy = VoiceAdaptivePlayback(VoicePerformanceMode.FAST)
        // The capture measured 4,482 ms synthesis and a separate 5,443 ms
        // refill gap. Six seconds of retained PCM is this synthetic queue fixture.
        policy.observe(4_482, KokoroPcmBatches.QUEUE_LIMIT_MS.toLong())
        repeat(7) { policy.underrun() }
        assertEquals(4_482L, policy.predictedSynthesisMs())
        assertTrue("Do not clip the measured tail and learned refill margin at five seconds", policy.bufferMs() > 5_443)
        assertTrue(policy.bufferMs() <= KokoroPcmBatches.QUEUE_LIMIT_MS - KokoroPcmBatches.DURATION_MS)
        var acceptedFrames = 0
        var primedAtMs: Long? = null
        KokoroPcmBatches.slices(FloatArray(24_000 * KokoroPcmBatches.QUEUE_LIMIT_MS / 1_000), 24_000) { slice ->
            acceptedFrames += slice.size
            val acceptedMs = acceptedFrames.toLong() * 1_000 / 24_000
            if (primedAtMs == null && acceptedMs >= policy.bufferMs()) primedAtMs = acceptedMs
            true
        }
        assertTrue(requireNotNull(primedAtMs) > 5_443)
        assertTrue(requireNotNull(primedAtMs) < KokoroPcmBatches.QUEUE_LIMIT_MS)
    }

    @Test fun qualityActuallyGeneratesTwentyFourShortWordsInOneTimedPhrase() {
        val text = "go ".repeat(24)
        val phrases = requireNotNull(KokoroSpeechText.withPolicy(text, { true }, emptyMap(), 24, true).phrases)
        assertEquals(1, phrases.size)
        assertEquals(24, phrases.single().wordRanges.size)
    }

    @Test fun calibrationRequiresRepeatedMeasurementsAndAMaterialGain() {
        assertEquals(2, VoiceCpuCalibration.select(mapOf(4 to listOf(10))))
        assertEquals(2, VoiceCpuCalibration.select(mapOf(2 to listOf(90, 100, 100), 4 to listOf(90, 96, 96))))
        assertEquals(3, VoiceCpuCalibration.select(mapOf(2 to listOf(100, 110, 120), 3 to listOf(60, 70, 75), 4 to listOf(50, 60, 140))))
    }

    @Test fun timestampPresentationUsesTheMeasuredClockAndNeverRewinds() {
        val clock = VoicePlaybackClock(24_000)
        val first = clock.position(2_400, 1_200, 1_000_000_000, 1_025_000_000, true)
        assertEquals(1_800, first.frames)
        assertEquals("audio_timestamp", first.basis)
        assertEquals(1_800, clock.position(2_400, 1_000, 1_020_000_000, 1_025_000_000, true).frames)
        assertEquals(1_800, clock.position(2_400, null, null, 1_100_000_000, false).frames)
    }

    @Test fun staleTimestampsFallBackAndRouteChangesPreservePresentedWords() {
        val clock = VoicePlaybackClock(24_000)
        assertEquals("playback_head", clock.position(2_400, 0, 1, 20_000_000_000, true).basis)
        clock.routeChanged()
        assertEquals(2_400, clock.position(1_200, 600, 20_000_000_000, 20_000_000_000, true).frames)
        assertEquals(1, clock.routeEpoch)
        assertEquals(600, VoicePlaybackClock(24_000).position(1_200, 600, 20_000_000_000, 20_000_000_000, true).frames)
    }

    @Test fun stableTimestampCanBeInterpolatedBetweenOccasionalPlatformSamples() {
        val clock = VoicePlaybackClock(24_000)
        assertEquals(240_000L, clock.position(250_000, 0, 1_000_000_000, 11_000_000_000, true).frames)
        assertEquals(240_000L, clock.position(230_000, 0, 1_000_000_000, 11_000_000_000, true).frames)
    }

    @Test fun queueEmptyAtLastPhraseKeepsPresentationTailMovingUntilALateSealCanComplete() {
        val clock = VoicePlaybackClock(1_000)
        var playing = true
        var presented = 0L
        for (milliseconds in 0L..100L step 10) {
            presented = clock.position(1_000, 900, 1_000_000_000, 1_000_000_000 + milliseconds * 1_000_000, playing).frames
            if (VoicePlaybackClock.drained(1_000, 1_000, presented)) playing = false
            if (milliseconds < 100) assertTrue("The raw head is drained but its measured presentation tail must keep advancing", playing)
        }
        assertFalse(playing)
        assertEquals(1_000L, presented)
        assertTrue(VoicePlaybackClock.drained(1_000, 1_000, presented))
    }

    @Test fun captionDelayDrainsTheFinalWordsWithoutChangingPcm() {
        assertEquals(21_600, VoicePlaybackClock.captionFrames(24_000, 24_000, 100))
        assertEquals(22_800, VoicePlaybackClock.captionFrames(24_000, 24_000, 100, 50))
        assertEquals(24_000, VoicePlaybackClock.captionFrames(24_000, 24_000, 100, 200))
        assertEquals(26_400, VoicePlaybackClock.captionFrames(24_000, 24_000, -100))
    }

    @Test fun finalDelayedCaptionKeepsScrollingUntilItsCalibratedClockDrains() {
        val delayed = VoicePlaybackClock.captionFrames(24_000, 24_000, 100, 50, 24_000)
        assertTrue(VoicePlaybackClock.captionsAdvancing(false, 24_000, delayed, 24_000, true))
        val complete = VoicePlaybackClock.captionFrames(24_000, 24_000, 100, 100, 24_000)
        assertFalse(VoicePlaybackClock.captionsAdvancing(false, 24_000, complete, 24_000, true))
    }

    @Test fun captionAnimationFreezesDuringPauseRefillAndBeforeFirstPresentation() {
        assertFalse(VoicePlaybackClock.captionsAdvancing(false, 12_000, 14_400, 24_000, false))
        assertFalse(VoicePlaybackClock.captionsAdvancing(false, 24_000, 24_000, 48_000, false))
        assertFalse(VoicePlaybackClock.captionsAdvancing(true, 0, 0, 24_000, false))
        assertTrue(VoicePlaybackClock.captionsAdvancing(true, 12_000, 14_400, 24_000, false))
    }

    @Test fun pronunciationOverridesRemainBoundedAndKeepSourceWordMapping() {
        assertFalse(VoicePronunciationOverrides.valid("word", "text; other"))
        assertTrue(VoicePronunciationOverrides.valid("one two three four five six", "test"))
        assertFalse(VoicePronunciationOverrides.valid("one two three four five six seven", "test"))
        val clean = VoicePronunciationOverrides.clean(mapOf("Sentoria" to "sen toria", "bad" to "<script>"))
        assertEquals(mapOf("sentoria" to "sen toria"), clean)
        val result = requireNotNull(KokoroSpeechText.withPolicy("Sentoria is here.", { true }, clean, 16, true).phrases)
        assertEquals("sen toria is here.", result.single().speech)
        assertEquals(listOf(0..7, 0..7, 9..10, 12..15), result.single().wordRanges)
    }

    @Test fun adaptiveChunksStartEarlyAndUseCurrentPlaybackFeedback() {
        val buffer = VoiceSpeechBuffer { 0L }
        val text = "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen "
        val feedback = VoiceSynthesisFeedback(suggestedFollowUpWords = 8)
        val parts = requireNotNull(buffer.append(text, final = false, earlyStart = true, feedback = feedback, adaptive = true))
        assertEquals(listOf("one two three four five six ", "seven eight nine ten eleven twelve thirteen fourteen "), parts)
        val tail = requireNotNull(buffer.append(text, final = true, earlyStart = true, feedback = feedback, adaptive = true))
        assertEquals(listOf("fifteen sixteen "), tail)
        assertEquals(text, (parts + tail).joinToString(""))
    }

    @Test fun slowTextArrivalHasABoundedStablePhraseWait() {
        var time = 0L
        val buffer = VoiceSpeechBuffer { time }
        assertEquals(emptyList<String>(), buffer.append("Hello there ", false, true, adaptive = true))
        time = 250
        assertEquals(listOf("Hello there "), buffer.append("Hello there ", false, true, adaptive = true))
        assertEquals(emptyList<String>(), buffer.append("Hello there ", false, true, adaptive = true))
    }
}
