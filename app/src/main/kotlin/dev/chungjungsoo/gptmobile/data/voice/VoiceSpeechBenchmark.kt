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
internal enum class VoiceSpeechBenchmarkIssue { MODEL_UNAVAILABLE, THERMAL_LIMIT, EXECUTION_FAILED, INVALID_WORKLOAD, CANCELLED }
internal enum class VoiceSpeechBenchmarkOutcome { RUNNING, QUALIFIED, PARTIAL, NO_QUALIFIED_COMPARISON, INVALID_WORKLOAD, CANCELLED, INTERRUPTED, HISTORICAL }

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
    val deviceKey: String,
    val workload: VoiceSpeechBenchmarkWorkload = VoiceSpeechBenchmarkWorkload.UTTERANCE,
    val protocolId: String = VoiceSpeechWorkload.PROTOCOL,
    val fixtureVersion: Int = VoiceSpeechWorkload.FIXTURE_VERSION,
    val fixtureHash: String? = VoiceSpeechWorkload.fixtureHash(workload),
    val backendOrderSeed: Long = 0,
    val sourceCommit: String? = null,
    val playback: Boolean = false
) {
    init {
        require(backends.isNotEmpty() && backends.distinct().size == backends.size && backends.all { it in LOCAL_BACKENDS })
        require(threads in 1..4 && speechRate.isFinite() && speechRate in .5f..2f)
        require(protocolId.matches(Regex("[A-Za-z0-9._-]{1,120}")))
        require(fixtureVersion in 1..1000 && (fixtureHash == null || fixtureHash.matches(Regex("[0-9a-f]{64}"))))
        require(sourceCommit == null || sourceCommit.matches(Regex("[0-9a-f]{40,64}")))
    }

    val currentProtocol: Boolean get() = protocolId == VoiceSpeechWorkload.PROTOCOL && fixtureVersion == VoiceSpeechWorkload.FIXTURE_VERSION && fixtureHash == VoiceSpeechWorkload.fixtureHash(workload)

    /** A retained rotation is reproducible; successive runs can counterbalance backend order. */
    val plannedBackendOrder: List<VoiceSpeechBackend>
        get() = Math.floorMod(backendOrderSeed, backends.size.toLong()).toInt().let { offset -> backends.drop(offset) + backends.take(offset) }

    companion object {
        val LOCAL_BACKENDS = listOf(VoiceSpeechBackend.CPU, VoiceSpeechBackend.QNN_GPU, VoiceSpeechBackend.QNN_HTP)
    }
}

/** One bounded native call. A reused first-shape profile is not a new kernel timing sample. */
internal data class VoiceSpeechBenchmarkChunk(
    val index: Int,
    val inputBytes: Int,
    val inputSha256: String,
    val generationMs: Long,
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
    val hardwareType: Int?,
    val profileAvailable: Boolean? = null,
    val profileId: Long? = null,
    val profileReused: Boolean? = null,
    val cacheHit: Boolean? = null,
    val currentPrepareMs: Long? = null,
    val graphPreparedThisCall: Boolean? = null,
    val cachedShapeCount: Int? = null,
    val cacheLimit: Int? = null,
    val cacheEstimatedBytes: Long? = null,
    val audioRms: Double? = null,
    val audioPeak: Double? = null,
    val clippedSamples: Long? = null,
    val nonSilentSamples: Long? = null,
    val discoverySummary: String? = null
) {
    val realTimeFactor: Double? get() = (generationMs / audioMs).takeIf { audioMs > 0 && it.isFinite() }
}

/** A fixture sums its bounded native calls; first PCM is the first complete chunk, without playback. */
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
    val hardwareType: Int?,
    val chunks: List<VoiceSpeechBenchmarkChunk> = emptyList()
) {
    val warm: Boolean get() = repeat > 0
    val realTimeFactor: Double? get() = (generationMs / audioMs).takeIf { audioMs > 0 && it.isFinite() }
}

/** Failed-inference observations stay separate from successful audio and cannot qualify a backend. */
internal data class VoiceSpeechBenchmarkFailureObservation(
    val profileAvailable: Boolean?,
    val profileId: Long?,
    val profileReused: Boolean?,
    val acceleratorMicros: Long,
    val cpuMicros: Long,
    val graphPrepareMs: Long,
    val currentPrepareMs: Long?,
    val synthesisMs: Long,
    val tokenCount: Int,
    val hardwareType: Int?,
    val cacheHit: Boolean?,
    val cachedShapeCount: Int?,
    val cacheLimit: Int?,
    val cacheEstimatedBytes: Long?,
    val audioRms: Double?,
    val audioPeak: Double?,
    val clippedSamples: Long?,
    val nonSilentSamples: Long?
) {
    companion object {
        fun from(e: VoiceSpeechExecutionEvidence) = VoiceSpeechBenchmarkFailureObservation(
            e.profileAvailable, e.profileId, e.profileReused, e.acceleratorMicros, e.cpuMicros, e.prepareMs, e.currentPrepareMs,
            e.synthesisMs, e.tokenCount, e.hardwareType, e.cacheHit, e.cachedShapeCount, e.cacheLimit, e.cacheEstimatedBytes,
            e.audioRms, e.audioPeak, e.clippedSamples, e.nonSilentSamples
        )
    }
}

/** Missing preparation thermal evidence remains unknown, including in older retained runs. */
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
    val failureCpuKernels: Int? = null,
    val failedFixture: VoiceSpeechBenchmarkFixture? = null,
    val failedRepeat: Int? = null,
    val failedChunk: Int? = null,
    val failedInputBytes: Int? = null,
    val failedInputByteLimit: Int? = null,
    val failedTokenCount: Int? = null,
    val failedTokenLimit: Int? = null,
    val failedRequestedProvider: String? = null,
    val failedDiscoverySummary: String? = null,
    val failedAttemptMs: Long? = null,
    val incompleteFixture: VoiceSpeechBenchmarkFixture? = null,
    val incompleteRepeat: Int? = null,
    val incompleteChunks: List<VoiceSpeechBenchmarkChunk> = emptyList(),
    val failureObservation: VoiceSpeechBenchmarkFailureObservation? = null,
    val preparationThermalBefore: Int? = null
) {
    val warmSamples: List<VoiceSpeechBenchmarkSample> get() = samples.filter { it.warm }
    val medianMs: Double? get() = median(warmSamples.map { it.generationMs.toDouble() })
    val p95Ms: Long? get() = percentile(warmSamples.map { it.generationMs }, .95)
    val medianRtf: Double?
        get() = median(warmSamples.mapNotNull { it.realTimeFactor })
    val executionVerified: Boolean
        get() = (samples.isNotEmpty() || incompleteChunks.isNotEmpty()) &&
            samples.all { sample ->
                sample.validatedAudio &&
                    sample.effectiveProvider == provider(requestedBackend) &&
                    (requestedBackend == VoiceSpeechBackend.CPU || sample.acceleratorKernels > 0) &&
                    sample.chunks.all { it.validatedAudio && it.effectiveProvider == provider(requestedBackend) && (requestedBackend == VoiceSpeechBackend.CPU || (it.profileAvailable == true && it.acceleratorKernels > 0)) }
            } &&
            incompleteChunks.all { it.validatedAudio && it.effectiveProvider == provider(requestedBackend) && (requestedBackend == VoiceSpeechBackend.CPU || (it.profileAvailable == true && it.acceleratorKernels > 0)) }
    val verified: Boolean
        get() = state == VoiceSpeechBenchmarkBackendState.COMPLETE && samples.isNotEmpty() && executionVerified && failure == null
    val measuredCostMs: Long get() = (sessionPrepareMs ?: 0) + samples.sumOf { it.generationMs } + incompleteChunks.sumOf { it.generationMs } + (failedAttemptMs ?: 0)

    fun fixtureStatistics(fixture: VoiceSpeechBenchmarkFixture): VoiceSpeechBenchmarkFixtureStatistics {
        val rows = samples.filter { it.fixture == fixture }
        val warm = rows.filter { it.warm }
        return VoiceSpeechBenchmarkFixtureStatistics(
            fixture, rows.size, warm.size, median(warm.map { it.generationMs.toDouble() }), percentile(warm.map { it.generationMs }, .95),
            median(warm.map { it.firstPcmMs.toDouble() }), median(warm.mapNotNull { it.realTimeFactor }),
            rows.sumOf { it.generationMs }, rows.sumOf { it.audioMs }, rows.sumOf { it.chunks.size }
        )
    }

    companion object {
        fun median(values: List<Double>): Double? = values.takeIf { it.isNotEmpty() }?.sorted()?.let {
            val middle = it.size / 2
            if (it.size % 2 == 0) it[middle - 1] / 2 + it[middle] / 2 else it[middle]
        }
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

internal data class VoiceSpeechBenchmarkFixtureStatistics(
    val fixture: VoiceSpeechBenchmarkFixture,
    val sampleCount: Int,
    val warmSampleCount: Int,
    val medianWarmMs: Double?,
    val p95WarmMs: Long?,
    val medianFirstPcmMs: Double?,
    val medianWarmRtf: Double?,
    val totalGenerationMs: Long,
    val totalAudioMs: Double,
    val completedChunks: Int
)

internal data class VoiceSpeechBenchmarkReport(
    val startedAtMillis: Long,
    val configuration: VoiceSpeechBenchmarkConfiguration,
    val state: VoiceSpeechBenchmarkRunState = VoiceSpeechBenchmarkRunState.RUNNING,
    val results: List<VoiceSpeechBenchmarkBackendResult> = configuration.backends.map(::VoiceSpeechBenchmarkBackendResult),
    val finishedAtMillis: Long? = null,
    val issue: VoiceSpeechBenchmarkIssue? = null,
    val activeFixture: VoiceSpeechBenchmarkFixture? = null,
    val activeRepeat: Int? = null,
    val activeChunk: Int? = null,
    val playbackResults: List<VoiceSpeechPlaybackResult> = emptyList()
) {
    val completedSamples: Int get() = results.sumOf { it.samples.size }
    val completedChunks: Int get() = results.sumOf { result -> result.samples.sumOf { it.chunks.size } + result.incompleteChunks.size }
    val totalSamples: Int get() = configuration.backends.size * VoiceSpeechBenchmarkFixture.entries.size * (configuration.mode.warmRepeats + 1)
    val isRunning: Boolean get() = state == VoiceSpeechBenchmarkRunState.RUNNING
    val outcome: VoiceSpeechBenchmarkOutcome
        get() = when {
            isRunning -> VoiceSpeechBenchmarkOutcome.RUNNING
            state == VoiceSpeechBenchmarkRunState.CANCELLED -> VoiceSpeechBenchmarkOutcome.CANCELLED
            issue == VoiceSpeechBenchmarkIssue.INVALID_WORKLOAD -> VoiceSpeechBenchmarkOutcome.INVALID_WORKLOAD
            state == VoiceSpeechBenchmarkRunState.INTERRUPTED -> VoiceSpeechBenchmarkOutcome.INTERRUPTED
            !configuration.currentProtocol -> VoiceSpeechBenchmarkOutcome.HISTORICAL
            configuration.playback -> playbackOutcome()
            qualifiedResults().isEmpty() -> VoiceSpeechBenchmarkOutcome.NO_QUALIFIED_COMPARISON
            results.any { it.state == VoiceSpeechBenchmarkBackendState.FAILED || it.state == VoiceSpeechBenchmarkBackendState.NOT_RUN } -> VoiceSpeechBenchmarkOutcome.PARTIAL
            else -> VoiceSpeechBenchmarkOutcome.QUALIFIED
        }

    private fun playbackOutcome(): VoiceSpeechBenchmarkOutcome {
        val qualified = playbackResults.filter {
            it.requestedBackend in configuration.backends &&
                it.state == VoiceSpeechPlaybackState.COMPLETE &&
                it.firstPresentedAudioMs != null &&
                it.totalAudioMs > 0 &&
                it.chunks > 0 &&
                it.validatedChunks == it.chunks &&
                it.captionWordsExpected > 0 &&
                it.captionWordsObserved == it.captionWordsExpected &&
                it.captionClockRegressions == 0 &&
                it.captionTimingViolations == 0 &&
                it.thermalBefore in 0..1 &&
                it.thermalAfter in 0..1 &&
                it.failure == null
        }
        if (qualified.isEmpty()) return VoiceSpeechBenchmarkOutcome.NO_QUALIFIED_COMPARISON
        val complete = state == VoiceSpeechBenchmarkRunState.COMPLETE &&
            issue == null &&
            qualified.size == playbackResults.size &&
            qualified.size == configuration.backends.size &&
            qualified.map { it.requestedBackend }.toSet() == configuration.backends.toSet()
        return if (complete) VoiceSpeechBenchmarkOutcome.QUALIFIED else VoiceSpeechBenchmarkOutcome.PARTIAL
    }

    fun qualifiedResults(): List<VoiceSpeechBenchmarkBackendResult> {
        if (state != VoiceSpeechBenchmarkRunState.COMPLETE || issue != null || !configuration.currentProtocol || configuration.playback) return emptyList()
        val expected = VoiceSpeechBenchmarkFixture.entries.size * (configuration.mode.warmRepeats + 1)
        return results.filter { result ->
            result.verified &&
                result.samples.size == expected &&
                result.sessionPrepareMs != null &&
                result.preparationThermalBefore in 0..1 &&
                result.incompleteChunks.isEmpty() &&
                result.samples.all { sample ->
                    sample.thermalBefore in 0..1 &&
                        sample.thermalAfter in 0..1 &&
                        sample.chunks.size == VoiceSpeechWorkload.phrases(sample.fixture, configuration.workload).size &&
                        sample.chunks.withIndex().all { (index, chunk) ->
                            val phrase = VoiceSpeechWorkload.phrases(sample.fixture, configuration.workload)[index]
                            chunk.index == index &&
                                chunk.inputBytes == phrase.toByteArray(Charsets.UTF_8).size &&
                                chunk.inputSha256 == VoiceSpeechWorkload.digest(phrase) &&
                                chunk.validatedAudio &&
                                chunk.effectiveProvider == VoiceSpeechBenchmarkBackendResult.provider(result.requestedBackend) &&
                                (result.requestedBackend == VoiceSpeechBackend.CPU || (chunk.profileAvailable == true && chunk.acceleratorKernels > 0)) &&
                                chunk.thermalBefore in 0..1 &&
                                chunk.thermalAfter in 0..1
                        }
                } &&
                VoiceSpeechBenchmarkFixture.entries.all { fixture ->
                    result.samples.filter { it.fixture == fixture }.map { it.repeat }.sorted() == (0..configuration.mode.warmRepeats).toList()
                }
        }
    }

    /** An accelerator must win the same three-shape workload by >=10%, including setup. */
    fun recommendation(): VoiceSpeechBackend? {
        val eligible = qualifiedResults()
        val cpu = eligible.firstOrNull { it.requestedBackend == VoiceSpeechBackend.CPU } ?: return null
        fun cost(result: VoiceSpeechBenchmarkBackendResult) = checkNotNull(result.sessionPrepareMs) + result.samples.sumOf { it.generationMs }
        val fastest = eligible.filter { it.requestedBackend != VoiceSpeechBackend.CPU }.minByOrNull(::cost) ?: return VoiceSpeechBackend.CPU
        return if (cost(fastest) * 10 <= cost(cpu) * 9) fastest.requestedBackend else VoiceSpeechBackend.CPU
    }
}
