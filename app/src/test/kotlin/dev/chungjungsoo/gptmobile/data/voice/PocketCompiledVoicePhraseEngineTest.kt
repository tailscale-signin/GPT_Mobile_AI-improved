package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketCompiledVoicePhraseEngineTest {
    private class Session(private val generate: ((FloatArray) -> Unit) -> Unit) : PocketCompiledSession {
        var closed = false
        override val tokenCount = 3
        override fun receipt() = "lm_requested=GPU;kernel_attribution=NOT_MEASURED"
        override fun resetMeasurements() = Unit
        override fun synthesize(text: String, voice: String, emit: (FloatArray) -> Unit) = generate(emit)
        override fun close() {
            closed = true
        }
    }

    private fun engine(session: PocketCompiledSession) = PocketCompiledVoicePhraseEngine(
        session,
        AtomicBoolean(),
        VoiceSpeechBackend.POCKET_GPU,
        1f,
        "alba",
        17,
        "HOST_TEST"
    )

    @Test
    fun successfulStreamingSplitsBoundedPacketsAndKeepsHardwareUnverified() {
        val session = Session { emit -> emit(FloatArray(48_001) { .25f }) }
        val engine = engine(session)
        val sizes = ArrayList<Int>()
        engine.stream("Hello world.", 0, 1f) {
            sizes.add(it.samples.size)
            true
        }
        assertEquals(listOf(24_000, 24_000, 1), sizes)
        val evidence = requireNotNull(engine.executionEvidence)
        assertEquals("litert-compiled-pocket-gpu-requested", evidence.effectiveProvider)
        assertEquals(false, evidence.profileAvailable)
        assertEquals(true, evidence.verifiedAudio)
        assertEquals(48_001L, evidence.outputFrames)
        assertEquals(.25, evidence.audioRms!!, 1e-9)
        assertFalse(engine.supportsIncrementalInput)
        engine.close()
        assertTrue(session.closed)
    }

    @Test
    fun invalidFirstPacketRetainsRawMeasurementsAndNeverReachesPlayback() {
        val engine = engine(Session { it(floatArrayOf(.2f, 1f, -.5f)) })
        var emitted = false
        assertThrows(IllegalArgumentException::class.java) {
            engine.stream("Hello.", 0, 1f) {
                emitted = true
                true
            }
        }
        assertFalse(emitted)
        val evidence = requireNotNull(engine.executionEvidence)
        assertEquals(VoiceSpeechFailureCode.OUTPUT_INVALID, evidence.failure)
        assertEquals(true, evidence.audioMeasurementAvailable)
        assertEquals(3L, evidence.outputFrames)
        assertEquals(1L, evidence.clippedSamples)
        assertEquals(1.0, evidence.audioPeak!!, 0.0)
        assertFalse(evidence.verifiedAudio)
    }

    @Test
    fun nonfinitePacketIsRecordedAndRejectedBeforePlayback() {
        val engine = engine(Session { it(floatArrayOf(.2f, Float.NaN)) })
        assertThrows(IllegalArgumentException::class.java) { engine.stream("Hello.", 0, 1f) { error("Must not emit") } }
        val evidence = requireNotNull(engine.executionEvidence)
        assertEquals(false, evidence.audioFinite)
        assertEquals(null, evidence.audioRms)
        assertEquals(2L, evidence.outputFrames)
    }

    @Test
    fun cancellationWaitsForNativeReturnBeforeClosingAndSuppressesStalePcm() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val retired = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val session = Session { emit ->
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            emit(floatArrayOf(.2f))
        }
        val engine = engine(session)
        val worker = Thread {
            try {
                engine.stream("Hello.", 0, 1f) { throw AssertionError("Stale audio escaped cancellation") }
            } catch (failure: Throwable) {
                error.set(failure)
            }
        }.also(Thread::start)
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        engine.cancel()
        val closer = Thread {
            engine.close()
            retired.countDown()
        }.also(Thread::start)
        assertFalse(retired.await(30, TimeUnit.MILLISECONDS))
        assertFalse(session.closed)
        release.countDown()
        worker.join(3_000)
        closer.join(3_000)
        assertTrue(error.get() is CancellationException)
        assertTrue(session.closed)
        assertEquals(0L, retired.count)
    }
}
