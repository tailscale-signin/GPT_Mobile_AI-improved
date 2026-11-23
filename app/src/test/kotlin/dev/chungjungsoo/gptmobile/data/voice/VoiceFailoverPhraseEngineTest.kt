package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VoiceFailoverPhraseEngineTest {
    private class Engine(private val fail: Boolean = false, private val cancel: Boolean = false) : VoicePhraseEngine {
        val phrases = mutableListOf<String>()
        var closes = 0
        var cancellations = 0
        var progress: VoiceSpeechProgress? = null
        override val executionProgress: VoiceSpeechProgress? get() = progress
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

    @Test fun liveProgressBelongsToTheCurrentDelegateAndDisappearsAfterClose() {
        val gpu = Engine(fail = true).apply {
            progress = VoiceSpeechProgress(VoiceSpeechStage.PREPARING, 1, 2, 400, 450, false)
        }
        val cpu = Engine().apply {
            progress = VoiceSpeechProgress(VoiceSpeechStage.SYNTHESIS, 1, 3, 20, 50, false)
        }
        val engine = VoiceFailoverPhraseEngine(gpu, true) { cpu }
        assertSame(gpu.progress, engine.executionProgress)
        engine.generate("unplayed", 0, 1f)
        assertSame(cpu.progress, engine.executionProgress)
        engine.cancel()
        cpu.progress = checkNotNull(cpu.progress).copy(cancellationRequested = true)
        assertSame(cpu.progress, engine.executionProgress)
        assertEquals(20L, engine.executionProgress?.stageElapsedMs)
        engine.close()
        assertNull(engine.executionProgress)
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

    @Test fun rejectedInputDoesNotRetireHardwareSpendFallbackOrReplayOnCpu() {
        val invalid = IllegalStateException("FRONTEND_INPUT_TOO_LONG")
        var closes = 0
        var calls = 0
        var cpuLoads = 0
        val gpu = object : VoicePhraseEngine {
            override fun generate(text: String, speaker: Int, speed: Float): VoicePhraseAudio {
                calls++
                if (calls == 1) throw invalid
                if (calls == 3) error("GRAPH_PREPARATION")
                return VoicePhraseAudio(floatArrayOf(.5f), longArrayOf(1), intArrayOf(0, 1))
            }
            override fun resetCancellation() = Unit
            override fun cancel() = Unit
            override fun close() {
                closes++
            }
        }
        val cpu = Engine()
        val engine = VoiceFailoverPhraseEngine(gpu, true, canRecover = { it !== invalid }) {
            cpuLoads++
            cpu
        }
        try {
            engine.generate("oversized", 3, 1.25f)
            fail("Expected exact input failure")
        } catch (actual: IllegalStateException) {
            assertSame(invalid, actual)
        }
        assertTrue(engine.canReuse)
        assertEquals(0, closes)
        assertEquals(0, cpuLoads)
        engine.generate("supported next input", 3, 1.25f)
        assertEquals(0, cpuLoads)
        engine.generate("unplayed graph failure", 3, 1.25f)
        assertEquals(1, closes)
        assertEquals(1, cpuLoads)
        assertEquals(listOf("unplayed graph failure"), cpu.phrases)
        engine.close()
    }
}
