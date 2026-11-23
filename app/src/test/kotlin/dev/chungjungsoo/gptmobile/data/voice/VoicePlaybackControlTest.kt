package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePlaybackControlTest {
    @Test fun fixedCalibrationSurvivesRoutesWithoutReadingOrWritingSavedSettings() {
        val policy = VoiceCaptionCalibration()
        val saved = mutableMapOf("speaker" to 500, "bluetooth" to -500)
        policy.fixed(0)
        for (route in saved.keys) {
            assertEquals(0, policy.resolve(0) { error("A fixed benchmark must not read $route calibration") })
        }
        assertEquals(mapOf("speaker" to 500, "bluetooth" to -500), saved)
        policy.useSavedRoutes()
        assertEquals(500, policy.resolve(0) { saved.getValue("speaker") })
        assertEquals(-500, policy.resolve(0) { saved.getValue("bluetooth") })
        assertEquals(25, policy.resolve(25) { error("Unavailable route storage") })
    }

    @Test fun slowGraphPreparationAndInferenceHaveSeparateBoundedDeadlines() {
        assertNull(deadline(progress(VoiceSpeechStage.PREPARING, 45_000)))
        val preparation = requireNotNull(deadline(progress(VoiceSpeechStage.PREPARING, 90_000)))
        assertEquals(VoiceSpeechStage.PREPARING, preparation.stage)
        assertEquals(90_000L, preparation.limitMs)
        assertEquals("native_stage", preparation.scope)
        val synthesis = requireNotNull(deadline(progress(VoiceSpeechStage.SYNTHESIS, 30_000)))
        assertEquals(VoiceSpeechStage.SYNTHESIS, synthesis.stage)
        assertEquals(30_000L, synthesis.limitMs)
    }

    @Test fun stageTransitionsAndCancellationCannotResetTheAbsoluteAttemptLimit() {
        for (stage in listOf(VoiceSpeechStage.PREPARING, VoiceSpeechStage.SYNTHESIS, VoiceSpeechStage.VALIDATING)) {
            val timeout = requireNotNull(deadline(progress(stage, 1, 120_000).copy(stageSequence = 99, cancellationRequested = true)))
            assertEquals("native_attempt", timeout.scope)
            assertEquals(120_000L, timeout.elapsedMs)
        }
    }

    @Test fun inactiveOldProgressCannotExpireAFreshReplyOrExtendUnknownWork() {
        val old = progress(VoiceSpeechStage.SYNTHESIS, 300_000).copy(active = false)
        assertNull(VoicePlaybackDeadlines.exceeded(old, 100, false, 30_000))
        assertNotNull(VoicePlaybackDeadlines.exceeded(old, 30_000, false, 30_000))
        assertNull(VoicePlaybackDeadlines.exceeded(null, 89_999, true, 90_000))
        assertNotNull(VoicePlaybackDeadlines.exceeded(null, 90_000, true, 90_000))
        assertNotNull(VoicePlaybackDeadlines.exceeded(null, 360_000, true, Long.MAX_VALUE))
    }

    @Test fun cumulativeUnderrunsDoNotDoubleCountOrTreatFinalDrainAsActiveStarvation() {
        val counter = VoicePlaybackUnderruns()
        assertEquals(0, counter.observe(null))
        assertEquals(0, counter.observe(0))
        assertEquals(1, counter.observe(1))
        assertEquals(0, counter.observe(1))
        assertEquals(0, counter.observe(0))
        assertEquals(1, counter.observe(2))
        assertEquals("startup", VoicePlaybackUnderruns.phase(false, false, 100, 0, false, false))
        assertEquals("active", VoicePlaybackUnderruns.phase(true, false, 100, 100, false, false))
        assertEquals("draining", VoicePlaybackUnderruns.phase(true, true, 100, 100, false, false))
        assertEquals("refill", VoicePlaybackUnderruns.phase(true, false, 100, 100, true, false))
        assertEquals("paused", VoicePlaybackUnderruns.phase(true, false, 100, 100, true, true))
    }

    @Test fun coldGraphAndProfilerCostDoNotBecomeAnExtraStartupReserveButCompleteCostRemains() {
        val evidence = evidence().copy(synthesisMs = 1_100, currentPrepareMs = 15_000, frontendMs = 30, validationMs = 20, profileFinalizeMs = 100)
        val recurring = requireNotNull(recurringSpeechCostMs(evidence, 16_200))
        assertEquals(1_050L, recurring)
        val playback = VoiceAdaptivePlayback(VoicePerformanceMode.BALANCED)
        playback.observe(16_200, 2_000, recurring)
        assertEquals(1_260, playback.bufferMs(startup = true))
        assertEquals(1_300L, playback.primeWaitMs(startup = true))
        assertEquals(VoiceAdaptivePlayback.MAX_RESERVE_MS, playback.bufferMs())
        assertEquals(16_200L, playback.predictedSynthesisMs())
        assertEquals(8.1, requireNotNull(playback.ratio()), .00001)
    }

    @Test fun absentOrInconsistentNativeCostKeepsTheConservativeCompleteCost() {
        val complete = evidence().copy(synthesisMs = 300, frontendMs = 20, validationMs = 10, profileFinalizeMs = 0)
        for (receipt in listOf(null, complete.copy(frontendMs = null), complete.copy(profileFinalizeMs = null), complete.copy(verifiedAudio = false), complete.copy(validationMs = Long.MAX_VALUE), complete.copy(profileFinalizeMs = 301))) {
            assertNull(recurringSpeechCostMs(receipt, 400))
        }
        assertNull(recurringSpeechCostMs(complete, 320))
        val playback = VoiceAdaptivePlayback(VoicePerformanceMode.BALANCED)
        playback.observe(4_000, 2_000, recurringSpeechCostMs(null, 4_000))
        assertEquals(playback.bufferMs(), playback.bufferMs(startup = true))
        assertTrue(playback.bufferMs() < KokoroPcmBatches.QUEUE_LIMIT_MS)
    }

    private fun deadline(progress: VoiceSpeechProgress) = VoicePlaybackDeadlines.exceeded(progress, 100, false, 30_000)

    private fun progress(stage: VoiceSpeechStage, stageMs: Long, attemptMs: Long = stageMs) =
        VoiceSpeechProgress(stage, 1, 1, stageMs, attemptMs, false)

    private fun evidence() = VoiceSpeechExecutionEvidence(
        VoiceSpeechBackend.CPU,
        "cpu",
        "a".repeat(64),
        "runtime",
        "b".repeat(64),
        VoiceSpeechStage.READY,
        verifiedAudio = true,
        observedAtMillis = 0
    )
}
