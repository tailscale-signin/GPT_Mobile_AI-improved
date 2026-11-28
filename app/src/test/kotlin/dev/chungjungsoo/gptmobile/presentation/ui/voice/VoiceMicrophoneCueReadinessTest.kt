package dev.chungjungsoo.gptmobile.presentation.ui.voice

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VoiceMicrophoneCueReadinessTest {
    @Test fun aFreshCaptureCanWaitForItsPreloadedCueWithoutWaitingForAnotherTap() = runTest {
        val readiness = VoiceMicrophoneCueReadiness()
        val prepared = async { readiness.prepare() }
        runCurrent()
        assertFalse(prepared.isCompleted)
        advanceTimeBy(100)
        readiness.loaded(true)
        runCurrent()
        assertTrue(prepared.await())
        assertTrue(readiness.prepare())
    }

    @Test fun missingOrFailedSoundCannotStrandMicrophonePreparation() = runTest {
        val readiness = VoiceMicrophoneCueReadiness()
        val prepared = async { readiness.prepare() }
        runCurrent()
        advanceTimeBy(250)
        runCurrent()
        assertFalse(prepared.await())
        readiness.loaded(true)
        // A late load is usable by a later capture, but never schedules an old cue.
        assertTrue(readiness.prepare())
        val failed = VoiceMicrophoneCueReadiness()
        failed.loaded(false)
        assertFalse(failed.prepare())
    }

    @Test fun cancellingAnOpeningDoesNotResumeItWhenTheSoundLoads() = runTest {
        val readiness = VoiceMicrophoneCueReadiness()
        var captures = 0
        val opening = async {
            readiness.prepare()
            captures++
        }
        runCurrent()
        opening.cancel()
        readiness.loaded(true)
        runCurrent()
        assertTrue(opening.isCancelled)
        assertTrue(captures == 0)
    }
}
