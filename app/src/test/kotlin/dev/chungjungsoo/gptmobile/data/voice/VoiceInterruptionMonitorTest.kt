package dev.chungjungsoo.gptmobile.data.voice

import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class VoiceInterruptionMonitorTest {
    @Test fun stoppingDuringRecorderCreationNeverStartsTheRetiredMicrophone() {
        val creating = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val started = AtomicBoolean()
        val capture = object : LocalVoiceCapture {
            override fun start() = started.set(true)
            override fun read(): FloatArray = error("Retired microphone was read")
            override fun stop() = Unit
            override fun close() = closed.countDown()
        }
        val monitor = VoiceInterruptionMonitor(RuntimeEnvironment.getApplication()) {
            creating.countDown()
            check(resume.await(3, TimeUnit.SECONDS))
            capture
        }
        try {
            monitor.start({ null }) { error("Retired microphone detected speech") }
            assertTrue(creating.await(3, TimeUnit.SECONDS))
            monitor.stop()
            resume.countDown()
            assertTrue(closed.await(3, TimeUnit.SECONDS))
            assertFalse(started.get())
            assertEquals(VoiceInterruptionStatus.OFF, monitor.state.value)
        } finally {
            resume.countDown()
            monitor.close()
        }
    }

    @Test fun monitoringReusesRecorderBuffersAndHandsOffOnlyTheLastSecondOfSpeech() {
        val closed = CountDownLatch(1)
        val available = ArrayDeque<FloatArray>()
        var allocated = 0
        var reads = 0
        val capture = object : LocalVoiceCapture {
            override fun start() = Unit
            override fun read(): FloatArray {
                val samples = available.removeFirstOrNull() ?: FloatArray(640).also { allocated++ }
                reads++
                if (reads > 100) {
                    for (i in samples.indices) samples[i] = if ((i / 16) % 2 == 0) .2f else -.2f
                }
                return samples
            }
            override fun recycle(samples: FloatArray) {
                samples.fill(0f)
                available.addLast(samples)
            }
            override fun stop() = Unit
            override fun close() = closed.countDown()
        }
        val monitor = VoiceInterruptionMonitor(RuntimeEnvironment.getApplication()) { capture }
        var speech: List<VoicePreRoll>? = null
        try {
            monitor.start({ null }) { speech = it }
            assertTrue(closed.await(3, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (speech == null && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.yield()
            }
            assertEquals(105, reads)
            assertEquals(26, allocated)
            assertEquals(allocated, available.size)
            val preRoll = requireNotNull(speech)
            assertEquals(16_000, preRoll.sumOf { it.samples.size })
            assertTrue(preRoll.take(20).all { chunk -> chunk.samples.all { it == 0f } })
            assertTrue(preRoll.takeLast(5).all { it.samples.first() == .2f && it.samples[16] == -.2f })
        } finally {
            monitor.close()
        }
    }
}
