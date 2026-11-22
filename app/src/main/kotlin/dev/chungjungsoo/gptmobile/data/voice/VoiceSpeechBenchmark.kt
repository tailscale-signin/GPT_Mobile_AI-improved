package dev.chungjungsoo.gptmobile.data.voice

import kotlin.math.ceil

internal enum class VoiceSpeechBenchmarkMode(val label: String, val warmRepeats: Int) {
    QUICK("Quick", 2),
    FULL("Full", 5)
}

internal enum class VoiceSpeechBenchmarkFixture(val label: String, val text: String) {
    SHORT("Short", "the voice is ready for your next question."),
    MEDIUM("Medium", "here are the main points. speech should stay clear while the next phrase is prepared, with natural pauses between ideas."),
    LONG(
        "Long",
        "a useful spoken answer begins with the answer and then adds the details that matter. " +
            "the speech engine prepares the next phrase while the current audio plays. " +
            "each word stays aligned with its sound, and you can stop the reply whenever you want to ask another question."
    )
}

internal enum class VoiceSpeechBenchmarkRunState { RUNNING, COMPLETE, CANCELLED, INTERRUPTED, FAILED }
internal enum class VoiceSpeechBenchmarkBackendState { PENDING, PREPARING, RUNNING, COMPLETE, UNSUPPORTED, FAILED, CANCELLED, NOT_RUN }
internal enum class VoiceSpeechBenchmarkIssue { MODEL_UNAVAILABLE, THERMAL_LIMIT, EXECUTION_FAILED, CANCELLED }

/** Configuration is frozen for a run. deviceKey stays in local storage and never enters exports. */
internal data class VoiceSpeechBenchmarkConfiguration(
    val mode: VoiceSpeechBenchmarkMode,
    val backends: List<VoiceSpeechBackend>,
    val voice: KokoroVoice,
    val threads: Int,
    val speechRate: Float,
    val modelSha256: String,
    val runtimeId: String,
    val appBuild: Int,
    val sdk: Int,
    val deviceKey: String
) {
    init {
        require(backends.isNotEmpty() && backends.distinct().size == backends.size && backends.all { it in LOCAL_BACKENDS })
        require(threads in 1..4 && speechRate.isFinite() && speechRate in .5f..2f)
    }

    companion object {
        val LOCAL_BACKENDS = listOf(VoiceSpeechBackend.CPU, VoiceSpeechBackend.QNN_GPU, VoiceSpeechBackend.QNN_HTP)
    }
}

/** First PCM is returned by this phrase API at completion; audio is deliberately not played. */
internal data class VoiceSpeechBenchmarkSample(
    val fixture: VoiceSpeechBenchmarkFixture,
    val repeat: Int,
    val generationMs: Long,
    val firstPcmMs: Long,
    val prepareToFirstPcmMs: Long?,
    val audioMs: Double,
    val effectiveProvider: String,
    val acceleratorKernels: Int,
    val cpuKernels: Int,
    val acceleratorMicros: Long,
    val cpuMicros: Long,
    val graphPrepareMs: Long,
    val tokenCount: Int,
    val validatedAudio: Boolean,
    val thermalBefore: Int?,
    val thermalAfter: Int?,
    val hardwareType: Int?
) {
    val warm: Boolean get() = repeat > 0
    val realTimeFactor: Double? get() = (generationMs / audioMs).takeIf { audioMs > 0 && it.isFinite() }
}

internal data class VoiceSpeechBenchmarkBackendResult(
    val requestedBackend: VoiceSpeechBackend,
    val state: VoiceSpeechBenchmarkBackendState = VoiceSpeechBenchmarkBackendState.PENDING,
    val sessionPrepareMs: Long? = null,
    val samples: List<VoiceSpeechBenchmarkSample> = emptyList(),
    val failure: VoiceSpeechFailureCode? = null,
    val failureStage: VoiceSpeechStage? = null,
    val nativeCode: Int? = null,
    val failureEffectiveProvider: String? = null,
    val failureAcceleratorKernels: Int? = null,
    val failureCpuKernels: Int? = null
) {
    val warmSamples: List<VoiceSpeechBenchmarkSample> get() = samples.filter { it.warm }
    val medianMs: Long? get() = percentile(warmSamples.map { it.generationMs }, .5)
    val p95Ms: Long? get() = percentile(warmSamples.map { it.generationMs }, .95)
    val medianRtf: Double?
        get() = warmSamples.mapNotNull { it.realTimeFactor }.sorted().let { values ->
            if (values.isEmpty()) null else values[(ceil(values.size * .5).toInt() - 1).coerceAtLeast(0)]
        }
    val verified: Boolean
        get() = state == VoiceSpeechBenchmarkBackendState.COMPLETE &&
            samples.isNotEmpty() &&
            samples.all {
                it.validatedAudio &&
                    it.effectiveProvider == provider(requestedBackend) &&
                    (requestedBackend == VoiceSpeechBackend.CPU || it.acceleratorKernels > 0)
            }

    companion object {
        fun percentile(values: List<Long>, quantile: Double): Long? = values.takeIf { it.isNotEmpty() }
            ?.sorted()?.let { it[(ceil(it.size * quantile).toInt() - 1).coerceIn(0, it.lastIndex)] }
        fun provider(backend: VoiceSpeechBackend): String = when (backend) {
            VoiceSpeechBackend.CPU -> "cpu"
            VoiceSpeechBackend.QNN_GPU -> "qnn-gpu"
            VoiceSpeechBackend.QNN_HTP -> "qnn-htp"
            else -> "unknown"
        }
    }
}

internal data class VoiceSpeechBenchmarkReport(
    val startedAtMillis: Long,
    val configuration: VoiceSpeechBenchmarkConfiguration,
    val state: VoiceSpeechBenchmarkRunState = VoiceSpeechBenchmarkRunState.RUNNING,
    val results: List<VoiceSpeechBenchmarkBackendResult> = configuration.backends.map(::VoiceSpeechBenchmarkBackendResult),
    val finishedAtMillis: Long? = null,
    val issue: VoiceSpeechBenchmarkIssue? = null,
    val activeFixture: VoiceSpeechBenchmarkFixture? = null,
    val activeRepeat: Int? = null
) {
    val completedSamples: Int get() = results.sumOf { it.samples.size }
    val totalSamples: Int get() = configuration.backends.size * VoiceSpeechBenchmarkFixture.entries.size * (configuration.mode.warmRepeats + 1)
    val isRunning: Boolean get() = state == VoiceSpeechBenchmarkRunState.RUNNING

    /** An accelerator must win the same three-shape workload by >=10%, including setup. */
    fun recommendation(): VoiceSpeechBackend? {
        if (state != VoiceSpeechBenchmarkRunState.COMPLETE || issue != null) return null
        val expected = VoiceSpeechBenchmarkFixture.entries.size * (configuration.mode.warmRepeats + 1)
        val eligible = results.filter { result ->
            result.verified &&
                result.samples.size == expected &&
                result.sessionPrepareMs != null &&
                result.samples.all { (it.thermalBefore ?: 0) < 2 && (it.thermalAfter ?: 0) < 2 } &&
                VoiceSpeechBenchmarkFixture.entries.all { fixture ->
                    result.samples.filter { it.fixture == fixture }.map { it.repeat }.sorted() == (0..configuration.mode.warmRepeats).toList()
                }
        }
        val cpu = eligible.firstOrNull { it.requestedBackend == VoiceSpeechBackend.CPU } ?: return null
        fun cost(result: VoiceSpeechBenchmarkBackendResult) = checkNotNull(result.sessionPrepareMs) + result.samples.sumOf { it.generationMs }
        val fastest = eligible.filter { it.requestedBackend != VoiceSpeechBackend.CPU }.minByOrNull(::cost) ?: return VoiceSpeechBackend.CPU
        return if (cost(fastest).toDouble() <= cost(cpu) * .9) fastest.requestedBackend else VoiceSpeechBackend.CPU
    }
}
