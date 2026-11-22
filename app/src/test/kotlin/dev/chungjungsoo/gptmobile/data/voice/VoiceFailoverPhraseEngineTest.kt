package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VoiceFailoverPhraseEngineTest {
    private class Engine(private val fail: Boolean = false, private val cancel: Boolean = false) : VoicePhraseEngine {
        val phrases = mutableListOf<String>()
        var closes = 0
        var cancellations = 0
        override fun generate(text: String, speaker: Int, speed: Float): VoicePhraseAudio {
            check(closes == 0) { "Kokoro is closed" }
            phrases += text
            if (cancel) throw CancellationException("Cancelled")
            if (fail) error("Graph rejected")
            return VoicePhraseAudio(floatArrayOf(0.5f), longArrayOf(1), intArrayOf(0, 1))
        }
        override fun resetCancellation() {
            check(closes == 0) { "Kokoro is closed" }
        }
        override fun cancel() {
            cancellations++
        }
        override fun close() {
            closes++
        }
    }

    @Test fun oneUnplayedPhraseRetriesAndSubsequentPhrasesUseTheResidentCpu() {
        val gpu = Engine(fail = true)
        val cpu = Engine()
        var loads = 0
        val engine = VoiceFailoverPhraseEngine(gpu, true) {
            assertEquals(1, gpu.closes)
            loads++
            cpu
        }
        engine.generate("first", 2, 1f)
        engine.generate("second", 2, 1f)
        assertEquals(listOf("first"), gpu.phrases)
        assertEquals(listOf("first", "second"), cpu.phrases)
        assertEquals(1, loads)
        assertTrue(engine.canReuse)
        engine.close()
        engine.close()
        assertEquals(1, cpu.closes)
    }

    @Test fun failedCpuPreparationRetiresTheCachedAccelerator() {
        val gpu = Engine(fail = true)
        val preparationFailure = IllegalStateException("CPU preparation failed")
        val engine = VoiceFailoverPhraseEngine(gpu, true) { throw preparationFailure }
        try {
            engine.generate("first", 0, 1f)
            fail("Expected CPU preparation failure")
        } catch (actual: IllegalStateException) {
            assertSame(preparationFailure, actual)
        }
        assertEquals(1, gpu.closes)
        assertFalse(engine.canReuse)
    }

    @Test fun ordinaryCancellationKeepsAHealthyResidentEngineReusable() {
        val cpu = Engine()
        val engine = VoiceFailoverPhraseEngine(cpu, false) { error("Unexpected fallback") }
        engine.cancel()
        assertTrue(engine.canReuse)
        engine.resetCancellation()
        engine.generate("next utterance", 0, 1f)
        assertEquals(listOf("next utterance"), cpu.phrases)
        assertEquals(0, cpu.closes)
        engine.close()
        assertFalse(engine.canReuse)
    }

    @Test fun cpuFailureDoesNotStartASecondRetry() {
        val gpu = Engine(fail = true)
        val cpu = Engine(fail = true)
        var loads = 0
        val engine = VoiceFailoverPhraseEngine(gpu, true) {
            loads++
            cpu
        }
        repeat(2) {
            try {
                engine.generate("unplayed", 0, 1f)
                fail("Expected CPU failure")
            } catch (_: IllegalStateException) { }
        }
        assertEquals(1, loads)
        assertEquals(listOf("unplayed"), gpu.phrases)
        assertEquals(2, cpu.phrases.size)
    }

    @Test fun cancellationAndAnAlreadySelectedCpuNeverTriggerFallback() {
        for ((native, allowed) in listOf(Engine(cancel = true) to true, Engine(fail = true) to false)) {
            var loads = 0
            val engine = VoiceFailoverPhraseEngine(native, allowed) {
                loads++
                Engine()
            }
            try {
                engine.generate("first", 0, 1f)
                fail("Expected stop")
            } catch (_: IllegalStateException) { }
            assertEquals(0, loads)
        }
    }

    @Test fun interruptionDuringReplacementCreationClosesItWithoutProducingPcm() {
        val gpu = Engine(fail = true)
        val cpu = Engine()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val engine = VoiceFailoverPhraseEngine(gpu, true) {
            entered.countDown()
            check(resume.await(5, TimeUnit.SECONDS))
            cpu
        }
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Boolean> {
                try {
                    engine.generate("first", 0, 1f)
                    false
                } catch (_: CancellationException) {
                    true
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            engine.cancel()
            resume.countDown()
            assertTrue(result.get(5, TimeUnit.SECONDS))
            assertTrue(cpu.phrases.isEmpty())
            assertEquals(1, cpu.closes)
            assertEquals(1, cpu.cancellations)
            assertFalse(engine.canReuse)
        } finally {
            resume.countDown()
            engine.close()
            worker.shutdownNow()
        }
    }
}
