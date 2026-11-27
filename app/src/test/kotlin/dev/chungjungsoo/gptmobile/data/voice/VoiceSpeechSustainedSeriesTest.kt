package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSpeechSustainedSeriesTest {
    private fun reply(repeat: Int, audioMs: Long = 20_000) = VoiceSpeechPlaybackResult(
        VoiceSpeechBackend.CPU, VoiceSpeechPlaybackState.COMPLETE,
        firstPresentedAudioMs = 100, elapsedMs = audioMs + 1_000, totalAudioMs = audioMs,
        chunks = 1, validatedChunks = 1, effectiveProvider = "cpu",
        protocolVersion = VoiceSpeechPlaybackProtocol.VERSION, scenario = VoiceSpeechPlaybackScenario.SUSTAINED,
        repeat = repeat, expectedResident = repeat > 0, engineReused = repeat > 0,
        manifestWordsExpected = 2, manifestWordsPrepared = 2, manifestWordsObserved = 2, renderedManifestWords = 2,
        unexpectedCaptionWords = 0, cleanupState = "RETIRED", activePlaybackUnderruns = 0,
        thermalBefore = 0, thermalAfter = 0
    )

    @Test fun completedPresentedRepliesReachTheTargetOnTheSameOwner() {
        var clock = 0L
        val series = VoiceSpeechSustainedSeries(60_000) { clock }
        val rows = (0..2).map { repeat ->
            clock += 21_000_000_000
            series.observe(reply(repeat))
        }
        assertEquals(listOf("IN_PROGRESS", "IN_PROGRESS", "TARGET_REACHED"), rows.map { it.sustainedQualification })
        assertEquals(listOf(20_000L, 40_000L, 60_000L), rows.map { it.sustainedObservedAudioMs })
        assertEquals(3, rows.last().sustainedCompletedRepeats)
        assertTrue(VoiceSpeechSustainedSeries.qualified(rows, 60_000))
        assertFalse(series.canStart())
    }

    @Test fun waitingOrQueuedButUnpresentedPcmCannotCountAsSustainedAudio() {
        var clock = 0L
        val series = VoiceSpeechSustainedSeries(60_000) { clock }
        clock = 90_000_000_000
        val row = series.observe(reply(0).copy(firstPresentedAudioMs = null))
        assertEquals(0L, row.sustainedObservedAudioMs)
        assertEquals(0, row.sustainedCompletedRepeats)
        assertEquals("INTERRUPTED", row.sustainedQualification)
        assertFalse(VoiceSpeechSustainedSeries.qualified(listOf(row), 60_000))
    }

    @Test fun partialCancellationKeepsCompletedDurationAndDoesNotCreditQueuedTail() {
        var clock = 0L
        val series = VoiceSpeechSustainedSeries(60_000) { clock }
        clock = 21_000_000_000
        val completed = series.observe(reply(0))
        clock += 5_000_000_000
        val cancelled = series.observe(reply(1).copy(state = VoiceSpeechPlaybackState.CANCELLED))
        assertEquals(20_000L, cancelled.sustainedObservedAudioMs)
        assertEquals(1, cancelled.sustainedCompletedRepeats)
        assertEquals("INTERRUPTED", cancelled.sustainedQualification)
        assertEquals(20_000L, completed.sustainedObservedAudioMs)
        assertFalse(series.canStart())
    }

    @Test fun wallDeadlineAndReplyCapProduceExplicitInsufficientDuration() {
        var clock = 0L
        val deadline = VoiceSpeechSustainedSeries(60_000) { clock }
        clock = 240_000_000_000
        assertFalse(deadline.canStart())
        val timeout = deadline.observe(reply(0).copy(state = VoiceSpeechPlaybackState.FAILED, timeoutScope = "SUSTAINED_SERIES"))
        assertEquals("INSUFFICIENT_DURATION", timeout.sustainedQualification)
        assertEquals(0L, timeout.sustainedObservedAudioMs)

        clock = 0
        val bounded = VoiceSpeechSustainedSeries(60_000) { clock }
        var last: VoiceSpeechPlaybackResult? = null
        repeat(VoiceSpeechSustainedSeries.MAX_REPLIES) { repeat ->
            clock += 2_000_000_000
            last = bounded.observe(reply(repeat, 1_000))
        }
        assertEquals("INSUFFICIENT_DURATION", last?.sustainedQualification)
        assertEquals(16_000L, last?.sustainedObservedAudioMs)
        assertFalse(bounded.canStart())
    }

    @Test fun forgedTotalsLegacyRowsAndChangedOwnersCannotRegainQualification() {
        var clock = 0L
        val series = VoiceSpeechSustainedSeries(60_000) { clock }
        val rows = (0..2).map { repeat ->
            clock += 21_000_000_000
            series.observe(reply(repeat))
        }
        fun rejected(change: (VoiceSpeechPlaybackResult) -> VoiceSpeechPlaybackResult) =
            assertFalse(VoiceSpeechSustainedSeries.qualified(rows.dropLast(1) + change(rows.last()), 60_000))
        rejected { it.copy(sustainedObservedAudioMs = 600_000) }
        rejected { it.copy(sustainedCompletedRepeats = 12) }
        rejected { it.copy(sustainedWallElapsedMs = 1) }
        rejected { it.copy(sustainedTargetAudioMs = null, sustainedQualification = null) }
        rejected { it.copy(protocolVersion = VoiceSpeechPlaybackProtocol.VERSION - 1) }
        rejected { it.copy(engineReused = false) }
        rejected { it.copy(repeat = 4) }
        rejected { it.copy(totalAudioMs = 200_000) }
        rejected { it.copy(renderedManifestWords = null) }
        rejected { it.copy(renderedManifestWords = 0) }
        rejected { it.copy(firstPresentedAudioMs = it.elapsedMs + 1) }
        rejected { it.copy(elapsedMs = -1) }
    }

    @Test fun aStopBetweenRepliesFinalizesTheSeriesWithoutInventingAnotherReply() {
        var clock = 0L
        val series = VoiceSpeechSustainedSeries(60_000) { clock }
        clock = 21_000_000_000
        val row = series.observe(reply(0))
        val stopped = series.finishIncomplete(row, interrupted = true)
        assertEquals("INTERRUPTED", stopped.sustainedQualification)
        assertEquals(row.sustainedCompletedRepeats, stopped.sustainedCompletedRepeats)
        assertEquals(row.sustainedObservedAudioMs, stopped.sustainedObservedAudioMs)
        assertFalse(series.canStart())
    }

    @Test fun thermalPressureStopsAnotherReplyWithoutDiscardingPresentedAudioOrChangingThreads() {
        var clock = 0L
        val series = VoiceSpeechSustainedSeries(60_000) { clock }
        assertTrue(series.canStart(null))
        assertTrue(series.canStart(1))
        clock = 21_000_000_000
        val row = series.observe(reply(0).copy(requestedThreads = 4, effectiveThreads = 4))
        assertFalse(series.canStart(2))
        assertFalse(series.canStart(6))
        val stopped = series.finishIncomplete(row, interrupted = true)
        assertEquals("INTERRUPTED", stopped.sustainedQualification)
        assertEquals(20_000L, stopped.sustainedObservedAudioMs)
        assertEquals(1, stopped.sustainedCompletedRepeats)
        assertEquals(row.firstPresentedAudioMs, stopped.firstPresentedAudioMs)
        assertEquals(row.totalAudioMs, stopped.totalAudioMs)
        assertEquals(4, stopped.requestedThreads)
        assertEquals(4, stopped.effectiveThreads)
        assertFalse(series.canStart(0))
        assertFalse(VoiceSpeechSustainedSeries.qualified(listOf(stopped), 60_000))
    }
}
