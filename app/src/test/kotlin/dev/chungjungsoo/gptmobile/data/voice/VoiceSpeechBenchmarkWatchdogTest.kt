package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSpeechBenchmarkWatchdogTest {
    @Test fun realSchedulerRequestsStopWhileTheOwnerIsStillBlocked() {
        val observed = CountDownLatch(1)
        val requests = AtomicInteger()
        val watchdog = VoiceSpeechBenchmarkWatchdog(
            VoiceSpeechBenchmarkDeadlines(preparationMs = 30, backendMs = 1000),
            onTimeout = {
                requests.incrementAndGet()
                observed.countDown()
            }
        )
        try {
            watchdog.beginBackend()
            watchdog.beginPhase(VoiceSpeechBenchmarkDeadlineScope.PREPARATION)
            assertTrue("The timer must act without an inference-owner checkpoint", observed.await(2, TimeUnit.SECONDS))
            assertEquals(VoiceSpeechBenchmarkDeadlineScope.PREPARATION, watchdog.timeout?.scope)
            assertTrue(watchdog.timeout!!.elapsedMs >= 30)
            repeat(3) { watchdog.checkpoint() }
            assertEquals(1, requests.get())
        } finally {
            watchdog.close()
        }
    }

    @Test fun previousPhaseTimerCannotCancelTheNextChunk() {
        var now = 0L
        val callbacks = mutableListOf<() -> Unit>()
        var requests = 0
        val watchdog = VoiceSpeechBenchmarkWatchdog(
            VoiceSpeechBenchmarkDeadlines(preparationMs = 10, chunkMs = 50),
            nanoTime = { now },
            onTimeout = { requests++ },
            scheduler = VoiceSpeechBenchmarkScheduler { _, action ->
                callbacks += action
                AutoCloseable {}
            }
        )
        watchdog.beginBackend()
        watchdog.beginPhase(VoiceSpeechBenchmarkDeadlineScope.PREPARATION)
        now = 5_000_000
        watchdog.endPhase()
        watchdog.beginPhase(VoiceSpeechBenchmarkDeadlineScope.CHUNK)
        now = 20_000_000
        callbacks[1]()
        assertEquals(0, requests)
        assertNull(watchdog.timeout)
        now = 55_000_000
        callbacks[2]()
        assertEquals(1, requests)
        assertEquals(VoiceSpeechBenchmarkDeadlineScope.CHUNK, watchdog.timeout?.scope)
        assertEquals(50L, watchdog.timeout?.elapsedMs)
        watchdog.close()
    }

    @Test fun wholeBackendDeadlineSurvivesIndividualPhaseCompletion() {
        var now = 0L
        val callbacks = mutableListOf<() -> Unit>()
        val watchdog = VoiceSpeechBenchmarkWatchdog(
            VoiceSpeechBenchmarkDeadlines(preparationMs = 100, chunkMs = 100, backendMs = 50),
            nanoTime = { now },
            onTimeout = {},
            scheduler = VoiceSpeechBenchmarkScheduler { _, action ->
                callbacks += action
                AutoCloseable {}
            }
        )
        watchdog.beginBackend()
        watchdog.beginPhase(VoiceSpeechBenchmarkDeadlineScope.PREPARATION)
        now = 20_000_000
        watchdog.endPhase()
        watchdog.beginPhase(VoiceSpeechBenchmarkDeadlineScope.CHUNK)
        now = 50_000_000
        callbacks[0]()
        assertEquals(VoiceSpeechBenchmarkDeadlineScope.BACKEND, watchdog.timeout?.scope)
        assertEquals(50L, watchdog.timeout?.elapsedMs)
        watchdog.close()
    }

    @Test fun ownerCheckpointCatchesExpiredWorkEvenBeforeDelayedSchedulerCallback() {
        var now = 0L
        var requests = 0
        val watchdog = VoiceSpeechBenchmarkWatchdog(
            VoiceSpeechBenchmarkDeadlines(chunkMs = 25),
            nanoTime = { now },
            stage = { VoiceSpeechStage.PREPARING },
            onTimeout = { requests++ },
            scheduler = VoiceSpeechBenchmarkScheduler { _, _ -> AutoCloseable {} }
        )
        watchdog.beginBackend()
        watchdog.beginPhase(VoiceSpeechBenchmarkDeadlineScope.CHUNK)
        now = 30_000_000
        val timeout = watchdog.endPhase()
        assertEquals(1, requests)
        assertEquals(VoiceSpeechBenchmarkDeadlineScope.CHUNK, timeout?.scope)
        assertEquals(VoiceSpeechStage.PREPARING, timeout?.stage)
        assertEquals(30_000_000L, watchdog.requestedAtNanos)
        watchdog.close()
    }

    @Test fun retiredBackendIgnoresItsLateTimerAndCannotStopAReplacement() {
        var now = 0L
        var requested = false
        val callbacks = mutableListOf<() -> Unit>()
        val watchdog = VoiceSpeechBenchmarkWatchdog(
            VoiceSpeechBenchmarkDeadlines(chunkMs = 10),
            nanoTime = { now },
            onTimeout = { requested = true },
            scheduler = VoiceSpeechBenchmarkScheduler { _, action ->
                callbacks += action
                AutoCloseable {}
            }
        )
        watchdog.beginBackend()
        watchdog.beginPhase(VoiceSpeechBenchmarkDeadlineScope.CHUNK)
        watchdog.close()
        now = 100_000_000
        callbacks.forEach { it() }
        assertFalse(requested)
        assertNull(watchdog.timeout)
    }

    @Test fun cancellationBeforeArmingOrBetweenPhasesReturnsWithoutSchedulingNewWork() {
        var schedules = 0
        val watchdog = VoiceSpeechBenchmarkWatchdog(
            VoiceSpeechBenchmarkDeadlines(),
            onTimeout = {},
            scheduler = VoiceSpeechBenchmarkScheduler { _, _ ->
                schedules++
                AutoCloseable {}
            }
        )
        watchdog.close()
        assertFalse(watchdog.beginBackend())
        assertFalse(watchdog.beginPhase(VoiceSpeechBenchmarkDeadlineScope.PREPARATION))
        assertEquals(0, schedules)
        val active = VoiceSpeechBenchmarkWatchdog(
            VoiceSpeechBenchmarkDeadlines(),
            onTimeout = {},
            scheduler = VoiceSpeechBenchmarkScheduler { _, _ ->
                schedules++
                AutoCloseable {}
            }
        )
        assertTrue(active.beginBackend())
        active.close()
        assertFalse(active.beginPhase(VoiceSpeechBenchmarkDeadlineScope.CHUNK))
        assertEquals(1, schedules)
    }
}
