package dev.chungjungsoo.gptmobile.data.voice

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceListeningPolicyTest {
    @Test fun transcriptionCadenceAdaptsToCostWithoutRunningAtEveryMicrophoneBatch() {
        val cadence = VoiceRecognitionCadence(200)
        repeat(4) { assertFalse(cadence.added(640)) }
        assertTrue(cadence.added(640))
        cadence.completed(400)
        repeat(9) { assertFalse(cadence.added(640)) }
        assertTrue(cadence.added(640))
        cadence.completed(0)
        repeat(4) { assertFalse(cadence.added(640)) }
        assertTrue(cadence.added(640))
    }

    @Test fun capturePoolCannotReuseAnArrayBeforeTheConsumerReleasesIt() {
        val pool = VoiceCaptureBufferPool(640, 2)
        val inFlight = pool.acquire()
        val queued = pool.acquire()
        assertNotSame(inFlight, queued)
        pool.release(inFlight)
        assertSame(inFlight, pool.acquire())
        pool.release(FloatArray(100))
        assertNotSame(queued, pool.acquire())
        pool.release(queued)
        assertSame(queued, pool.acquire())
    }

    @Test fun interruptionNeedsSustainedSpeechAndRejectsSettlingNoiseAndBriefClicks() {
        val detector = VoiceInterruptionDetector()
        repeat(8) { assertFalse(detector.observe(FloatArray(640), null)) }
        assertFalse(detector.observe(speech(.1f), null))
        repeat(3) { assertFalse(detector.observe(FloatArray(640), null)) }
        repeat(4) { assertFalse(detector.observe(speech(.1f), null)) }
        assertTrue(detector.observe(speech(.1f), null))
    }

    @Test fun playbackResidualBelowTheEchoThresholdCannotTriggerInterruption() {
        val detector = VoiceInterruptionDetector()
        repeat(40) { assertFalse(detector.observe(speech(.03f), .8f)) }
        repeat(4) { assertFalse(detector.observe(speech(.25f), .8f)) }
        assertTrue(detector.observe(speech(.25f), .8f))
    }

    private fun speech(amplitude: Float) = FloatArray(640) { index -> (sin(index * 2 * PI * 180 / 16_000) * amplitude).toFloat() }
}
