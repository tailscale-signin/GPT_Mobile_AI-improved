package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSpeechBenchmarkEnergyTrackerTest {
    private fun report() = VoiceSpeechBenchmarkReport(
        1,
        VoiceSpeechBenchmarkConfiguration(
            VoiceSpeechBenchmarkMode.QUICK, listOf(VoiceSpeechBackend.CPU, VoiceSpeechBackend.QNN_GPU), KokoroVoice.HEART,
            2, 1f, "a".repeat(64), "runtime", 149, 37, "b".repeat(64)
        )
    )

    private class Window(backend: VoiceSpeechBackend) : VoiceSpeechBenchmarkEnergyWindow {
        private val start = VoiceSpeechBenchmarkEnergyObservation(backend, VoiceSpeechEnergyEndpoint(1000, VoiceSpeechEnergyChargeState.DISCHARGING, false), monitorRegistered = true)
        var finishes = 0
        override fun current() = start
        override fun finish(): VoiceSpeechBenchmarkEnergyObservation {
            finishes++
            return start.copy(end = start.start.copy(remainingNanowattHours = 900), elapsedMs = 1000, monitoringConfirmed = true)
        }
    }

    @Test fun nativePublicationWindowsSurroundEachAttemptExactlyOnceAndSurviveRunnerCopies() {
        val windows = linkedMapOf<VoiceSpeechBackend, Window>()
        val tracker = VoiceSpeechBenchmarkEnergyTracker { backend -> Window(backend).also { windows[backend] = it } }
        val initial = report()
        assertTrue(tracker.attach(initial).energyObservations.isEmpty())
        fun running(index: Int, state: VoiceSpeechBenchmarkBackendState) = initial.copy(results = initial.results.mapIndexed { i, row -> if (i == index) row.copy(state = state) else row })
        val preparing = tracker.attach(running(0, VoiceSpeechBenchmarkBackendState.PREPARING))
        assertEquals(VoiceSpeechEnergyStatus.IN_PROGRESS, preparing.energyObservations.single().status)
        tracker.attach(running(0, VoiceSpeechBenchmarkBackendState.RUNNING))
        val cpuDone = tracker.attach(running(0, VoiceSpeechBenchmarkBackendState.COMPLETE))
        assertEquals(100L, cpuDone.energyObservations.single().observedCounterDecreaseNanowattHours)
        val gpu = initial.copy(results = listOf(cpuDone.results[0], initial.results[1].copy(state = VoiceSpeechBenchmarkBackendState.PREPARING)))
        tracker.attach(gpu)
        val finished = tracker.attach(gpu.copy(state = VoiceSpeechBenchmarkRunState.CANCELLED))
        assertEquals(listOf(VoiceSpeechBackend.CPU, VoiceSpeechBackend.QNN_GPU), finished.energyObservations.map { it.requestedBackend })
        assertTrue(finished.energyObservations.all { it.end != null })
        tracker.attach(finished)
        tracker.close()
        assertEquals(listOf(1, 1), windows.values.map { it.finishes })
    }

    @Test fun unavailableBatteryServiceCannotAbortSpeechAndPendingBackendsStayUnobserved() {
        val tracker = VoiceSpeechBenchmarkEnergyTracker { error("Service unavailable") }
        val initial = report()
        val running = initial.copy(results = listOf(initial.results[0].copy(state = VoiceSpeechBenchmarkBackendState.PREPARING), initial.results[1]))
        assertEquals(1, tracker.attach(running).energyObservations.size)
        val stopped = tracker.attach(running.copy(state = VoiceSpeechBenchmarkRunState.FAILED))
        assertEquals(VoiceSpeechEnergyStatus.COUNTER_UNAVAILABLE, stopped.energyObservations.single().status)
        assertEquals(stopped, speechBenchmarkReportFromJson(speechBenchmarkJson(stopped, local = true)))
        tracker.close()
    }

    @Test fun coldRoundClosesBeforeAnotherProcessorAndSecondPassRetainsSeparateEndpoints() {
        val started = mutableListOf<Window>()
        val tracker = VoiceSpeechBenchmarkEnergyTracker { Window(it).also(started::add) }
        val seed = report()
        var current = seed.copy(configuration = seed.configuration.copy(playback = true, mode = VoiceSpeechBenchmarkMode.FULL))
        fun publish(index: Int, state: VoiceSpeechBenchmarkBackendState) {
            current = tracker.attach(current.copy(results = current.results.mapIndexed { i, row -> if (i == index) row.copy(state = state) else row }))
        }
        for (endState in listOf(VoiceSpeechBenchmarkBackendState.PARTIAL, VoiceSpeechBenchmarkBackendState.COMPLETE)) {
            for (index in 0..1) {
                publish(index, VoiceSpeechBenchmarkBackendState.RUNNING)
                assertTrue(started.dropLast(1).all { it.finishes == 1 })
                publish(index, endState)
            }
        }
        assertEquals(listOf(1, 2, 1, 2), current.energyObservations.map { it.window })
        assertTrue(started.all { it.finishes == 1 })
        assertEquals(current, speechBenchmarkReportFromJson(speechBenchmarkJson(current, local = true)))
        val duplicate = current.copy(energyObservations = current.energyObservations.map { it.copy(window = 1) })
        assertEquals(null, speechBenchmarkReportFromJson(speechBenchmarkJson(duplicate, local = true)))
    }

    @Test fun failedMonitorFinalizationRetainsUnknownEvidenceAndCloseDoesNotDoubleFinish() {
        var finishes = 0
        val tracker = VoiceSpeechBenchmarkEnergyTracker { backend ->
            object : VoiceSpeechBenchmarkEnergyWindow {
                override fun current() = VoiceSpeechBenchmarkEnergyObservation(backend, VoiceSpeechEnergyEndpoint(1000, VoiceSpeechEnergyChargeState.DISCHARGING, false))
                override fun finish(): VoiceSpeechBenchmarkEnergyObservation {
                    finishes++
                    error("Monitor failure")
                }
            }
        }
        val initial = report()
        val running = initial.copy(results = listOf(initial.results[0].copy(state = VoiceSpeechBenchmarkBackendState.RUNNING), initial.results[1]))
        tracker.attach(running)
        val stopped = tracker.attach(running.copy(state = VoiceSpeechBenchmarkRunState.INTERRUPTED))
        assertEquals(VoiceSpeechEnergyStatus.COUNTER_UNAVAILABLE, stopped.energyObservations.single().status)
        assertFalse(stopped.energyObservations.single().monitoringConfirmed)
        tracker.close()
        assertEquals(1, finishes)
    }

    @Test fun sdkBenchmarksUseTheirOwnBackendStateAndProcessCleanupClosesActiveObserver() {
        var window: Window? = null
        val tracker = VoiceSpeechBenchmarkEnergyTracker { backend -> Window(backend).also { window = it } }
        val initial = report().let { value ->
            value.copy(configuration = value.configuration.copy(engine = VoiceSpeechBenchmarkEngine.PHONON, backends = listOf(VoiceSpeechBackend.PHONON_CPU)), results = emptyList(), engineResults = listOf(VoiceSpeechEngineBenchmarkResult(VoiceSpeechBackend.PHONON_CPU, VoiceSpeechBenchmarkBackendState.RUNNING)))
        }
        val observed = tracker.attach(initial)
        assertEquals(VoiceSpeechBackend.PHONON_CPU, observed.energyObservations.single().requestedBackend)
        tracker.close()
        tracker.close()
        assertEquals(1, checkNotNull(window).finishes)
    }
}
