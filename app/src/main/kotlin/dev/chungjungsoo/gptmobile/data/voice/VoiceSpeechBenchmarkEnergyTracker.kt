package dev.chungjungsoo.gptmobile.data.voice

/** Publication boundaries surround backend work without sampling inside timed native calls. */
internal class VoiceSpeechBenchmarkEnergyTracker(private val begin: (VoiceSpeechBackend) -> VoiceSpeechBenchmarkEnergyWindow) {
    private val windows = linkedMapOf<VoiceSpeechBackend, VoiceSpeechBenchmarkEnergyWindow>()
    private val observations = linkedMapOf<VoiceSpeechBackend, VoiceSpeechBenchmarkEnergyObservation>()
    private val coldWindows = linkedMapOf<VoiceSpeechBackend, VoiceSpeechBenchmarkEnergyObservation>()

    @Synchronized fun attach(report: VoiceSpeechBenchmarkReport): VoiceSpeechBenchmarkReport {
        val states = if (report.configuration.engine == VoiceSpeechBenchmarkEngine.NATIVE_KOKORO) {
            report.results.associate { it.requestedBackend to it.state }
        } else {
            report.engineResults.associate { it.requestedBackend to it.state }
        }
        for (backend in report.configuration.backends) {
            val active = report.isRunning && states[backend] in ACTIVE_STATES
            if (active && backend !in windows && (backend !in observations || report.configuration.comparesColdStartsFirst && backend !in coldWindows)) {
                observations[backend]?.let { coldWindows[backend] = it }
                val window = runCatching { begin(backend) }.getOrElse { unavailableWindow(backend) }
                windows[backend] = window
                observations[backend] = runCatching { window.current() }.getOrElse { VoiceSpeechBenchmarkEnergyObservation(backend, VoiceSpeechEnergyEndpoint()) }
            }
            windows[backend]?.let { window ->
                observations[backend] = if (active) {
                    runCatching { window.current() }.getOrDefault(checkNotNull(observations[backend]))
                } else {
                    windows.remove(backend)
                    runCatching { window.finish() }.getOrElse {
                        checkNotNull(observations[backend]).copy(end = VoiceSpeechEnergyEndpoint(), monitoringConfirmed = false)
                    }
                }
            }
        }
        val retained = report.configuration.backends.flatMap { backend ->
            val first = coldWindows[backend]
            listOfNotNull(first, observations[backend]?.let { if (first == null) it else it.copy(window = 2) })
        }
        return report.copy(energyObservations = retained)
    }

    /** Also retires observers if publication or persistence throws. */
    @Synchronized fun close() {
        windows.values.forEach { runCatching { it.finish() } }
        windows.clear()
    }

    private fun unavailableWindow(backend: VoiceSpeechBackend) = object : VoiceSpeechBenchmarkEnergyWindow {
        private val observation = VoiceSpeechBenchmarkEnergyObservation(backend, VoiceSpeechEnergyEndpoint())
        override fun current() = observation
        override fun finish() = observation.copy(end = VoiceSpeechEnergyEndpoint())
    }

    companion object {
        private val ACTIVE_STATES = setOf(VoiceSpeechBenchmarkBackendState.PREPARING, VoiceSpeechBenchmarkBackendState.RUNNING)
    }
}
