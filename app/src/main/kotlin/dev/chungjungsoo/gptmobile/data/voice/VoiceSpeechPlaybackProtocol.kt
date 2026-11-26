package dev.chungjungsoo.gptmobile.data.voice

internal enum class VoiceSpeechPlaybackScenario(val label: String) {
    COLD_START("Fresh session"),
    RESIDENT_WARM("Resident warm session"),
    STREAMED_SEGMENTS("Streaming arrival"),
    VARIED_SHAPES("Changing phrase shapes"),
    SUSTAINED("Sustained playback"),
    CANCELLATION("Cancellation at each stage")
}

internal enum class VoiceSpeechCalibrationPolicy(val label: String) {
    CONTROLLED_ZERO("Controlled · zero caption offset"),
    CURRENT_SETTINGS("Current route calibration")
}

internal enum class VoiceSpeechPlaybackQuality {
    UNASSESSED,
    FAILED,
    INCOMPLETE,
    COMPLETE_WITH_STALLS,
    SLOW_START,
    THERMAL_LIMITED,
    PLAYBACK_VALIDATED,
    STREAMING_PLAYBACK_OBSERVED,
    CANCELLATION_VALIDATED,
    CANCELLATION_TARGET_NOT_REACHED
}

internal enum class VoiceSpeechCancellationTarget { PREPARATION, INFERENCE, PLAYBACK, SDK_STREAM_CALL }

internal enum class VoiceSpeechPrewarmMode { MODEL_ONLY, FIRST_INFERENCE }

internal enum class VoiceSpeechStopOrigin { USER, LIFECYCLE, DEADLINE, PROBE, COROUTINE }

/** An immutable workload is selected before any production planner or output callback runs. */
internal data class VoiceSpeechPlaybackPlan(
    val scenario: VoiceSpeechPlaybackScenario,
    val repeat: Int,
    val segments: List<String>,
    val warmup: Boolean = false,
    val expectedResident: Boolean = false,
    val arrivalIntervalMs: Long = 0,
    val cancellationTarget: VoiceSpeechCancellationTarget? = null,
    val prewarmMode: VoiceSpeechPrewarmMode? = null,
    val recoveryAfter: VoiceSpeechCancellationTarget? = null
) {
    val manifest: VoiceSpeechCaptionManifest = VoiceSpeechCaptionManifest.from(segments)
    val identity: String get() = "${scenario.name}:$repeat:${cancellationTarget?.name ?: "NONE"}"
}

internal data class VoiceSpeechCaptionSpan(val segment: Int, val first: Int, val last: Int)

internal data class VoiceSpeechPlaybackPass(val backend: VoiceSpeechBackend, val plans: List<VoiceSpeechPlaybackPlan>, val completesBackend: Boolean)

/** Fixture expectation is independent of the pronunciation planner and its emitted captions. */
internal data class VoiceSpeechCaptionManifest(val hash: String, val spans: Set<VoiceSpeechCaptionSpan>) {
    companion object {
        private val word = Regex("[a-zA-Z]+(?:'[a-zA-Z]+)*")
        fun from(segments: List<String>): VoiceSpeechCaptionManifest {
            require(segments.isNotEmpty() && segments.size <= 32)
            require(segments.all { it.isNotBlank() && it.length <= 4_000 })
            val spans = segments.flatMapIndexed { index, text ->
                word.findAll(text).map { VoiceSpeechCaptionSpan(index, it.range.first, it.range.last) }.toList()
            }.toSet()
            require(spans.isNotEmpty() && spans.size <= MAX_WORDS)
            return VoiceSpeechCaptionManifest(VoiceSpeechWorkload.digest(segments.joinToString("\u0000")), spans)
        }
        const val MAX_WORDS = 1024
    }
}

internal object VoiceSpeechPlaybackProtocol {
    const val VERSION = 7
    const val COMPARISON_CACHE_BYTES = 512L * 1024 * 1024
    const val MAX_SAMPLES_PER_BACKEND = 100
    const val MAX_CHUNKS_PER_SAMPLE = 128
    const val FIRST_AUDIO_TARGET_MS = VoiceAdaptivePlayback.FIRST_AUDIO_TARGET_MS
    const val CANCELLATION_TARGET_MS = 500L
    const val MAX_CANCEL_TARGET_WAIT_MS = 90_000L

    fun cancellationTargets(configuration: VoiceSpeechBenchmarkConfiguration): List<VoiceSpeechCancellationTarget> {
        val synthesis = if (configuration.playbackProtocolVersion >= 7 && configuration.engine != VoiceSpeechBenchmarkEngine.NATIVE_KOKORO) {
            VoiceSpeechCancellationTarget.SDK_STREAM_CALL
        } else {
            VoiceSpeechCancellationTarget.INFERENCE
        }
        return listOf(VoiceSpeechCancellationTarget.PREPARATION, synthesis, VoiceSpeechCancellationTarget.PLAYBACK)
    }

    fun passes(configuration: VoiceSpeechBenchmarkConfiguration): List<VoiceSpeechPlaybackPass> {
        val plans = plans(configuration)
        val order = configuration.plannedBackendOrder
        if (!configuration.comparesColdStartsFirst) return order.map { VoiceSpeechPlaybackPass(it, plans, true) }
        // Only a cold trial owns an independent session. Keep each warm cohort and sustained series together.
        return order.map { VoiceSpeechPlaybackPass(it, plans.take(1), false) } +
            order.map { VoiceSpeechPlaybackPass(it, plans.drop(1), true) }
    }

    fun maximumSamples(configuration: VoiceSpeechBenchmarkConfiguration): Int = plans(configuration).size +
        if (VoiceSpeechPlaybackScenario.SUSTAINED in configuration.playbackScenarios) VoiceSpeechSustainedSeries.MAX_REPLIES - 1 else 0

    fun expandedPlans(configuration: VoiceSpeechBenchmarkConfiguration, sustainedReplies: Int): List<VoiceSpeechPlaybackPlan> {
        val hasSustained = VoiceSpeechPlaybackScenario.SUSTAINED in configuration.playbackScenarios
        require(if (hasSustained) sustainedReplies in 1..VoiceSpeechSustainedSeries.MAX_REPLIES else sustainedReplies == 0)
        return plans(configuration).flatMap { plan ->
            if (plan.scenario == VoiceSpeechPlaybackScenario.SUSTAINED) {
                List(sustainedReplies) { repeat -> plan.copy(repeat = repeat, expectedResident = repeat > 0) }
            } else {
                listOf(plan)
            }
        }.also { require(it.size <= MAX_SAMPLES_PER_BACKEND) }
    }

    fun plans(configuration: VoiceSpeechBenchmarkConfiguration): List<VoiceSpeechPlaybackPlan> {
        val full = configuration.mode != VoiceSpeechBenchmarkMode.QUICK
        val stability = configuration.mode == VoiceSpeechBenchmarkMode.STABILITY
        val repeats = if (full) 5 else 2
        val sdk = configuration.playbackProtocolVersion >= 7 && configuration.engine != VoiceSpeechBenchmarkEngine.NATIVE_KOKORO
        val prewarmModes = if (sdk && configuration.engine.mediaPipeEngine()?.isKokoro == true) {
            VoiceSpeechPrewarmMode.entries.let { if (configuration.backendOrderSeed % 2L == 0L) it else it.reversed() }
        } else {
            emptyList()
        }
        val long = VoiceSpeechBenchmarkFixture.LONG.text
        return configuration.playbackScenarios.flatMap { scenario ->
            when (scenario) {
                VoiceSpeechPlaybackScenario.COLD_START -> List(if (full) 3 else 1) { VoiceSpeechPlaybackPlan(scenario, it, listOf(long)) }
                VoiceSpeechPlaybackScenario.RESIDENT_WARM -> List(repeats + 1) {
                    VoiceSpeechPlaybackPlan(scenario, it, listOf(long), warmup = it == 0, expectedResident = it > 0)
                } + prewarmModes.mapIndexed { index, mode ->
                    VoiceSpeechPlaybackPlan(scenario, repeats + 1 + index, listOf(long), expectedResident = true, prewarmMode = mode)
                }
                VoiceSpeechPlaybackScenario.STREAMED_SEGMENTS -> List(repeats) {
                    VoiceSpeechPlaybackPlan(scenario, it, long.split(Regex("(?<=[.!?])\\s+")).filter(String::isNotBlank), expectedResident = it > 0, arrivalIntervalMs = 500)
                }
                VoiceSpeechPlaybackScenario.VARIED_SHAPES -> List(if (stability) 1 + configuration.mode.warmRepeats * VoiceSpeechBenchmarkFixture.entries.size else repeats + 1) {
                    val index = if (stability && it > 0) it - 1 else it
                    val fixture = VoiceSpeechBenchmarkFixture.entries[index % VoiceSpeechBenchmarkFixture.entries.size]
                    VoiceSpeechPlaybackPlan(scenario, it, listOf(fixture.text), warmup = stability && it == 0, expectedResident = it > 0)
                }
                VoiceSpeechPlaybackScenario.SUSTAINED -> listOf(
                    VoiceSpeechPlaybackPlan(scenario, 0, listOf(List(6) { long }.joinToString(" ")))
                )
                VoiceSpeechPlaybackScenario.CANCELLATION -> cancellationTargets(configuration).flatMapIndexed { index, target ->
                    val probe = VoiceSpeechPlaybackPlan(scenario, index, listOf(long), cancellationTarget = target)
                    if (sdk) listOf(probe, VoiceSpeechPlaybackPlan(scenario, 3 + index, listOf(VoiceSpeechBenchmarkFixture.SHORT.text), recoveryAfter = target)) else listOf(probe)
                }
            }
        }.also { require(it.size <= MAX_SAMPLES_PER_BACKEND) }
    }
}
