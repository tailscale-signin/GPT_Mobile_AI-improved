package dev.chungjungsoo.gptmobile.data.voice

import kotlin.math.ceil

internal enum class VoiceSpeechBenchmarkMode(val label: String, val warmRepeats: Int) {
    QUICK("Quick", 2),
    FULL("Full", 5),
    STABILITY("Stability", 20)
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
internal enum class VoiceSpeechBenchmarkIssue { MODEL_UNAVAILABLE, THERMAL_LIMIT, EXECUTION_FAILED, INVALID_WORKLOAD, CANCELLED, NATIVE_CLEANUP_PENDING }
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
    val playback: Boolean = false,
    val playbackProtocolVersion: Int = VoiceSpeechPlaybackProtocol.VERSION,
    val playbackScenarios: List<VoiceSpeechPlaybackScenario> = listOf(VoiceSpeechPlaybackScenario.COLD_START),
    val calibrationPolicy: VoiceSpeechCalibrationPolicy = VoiceSpeechCalibrationPolicy.CONTROLLED_ZERO,
    val captionOffsetMs: Int = 0,
    val engine: VoiceSpeechBenchmarkEngine = VoiceSpeechBenchmarkEngine.NATIVE_KOKORO,
    val engineVoiceId: String? = null,
    val deepDiagnostics: Boolean = false,
    val deadlines: VoiceSpeechBenchmarkDeadlines? = VoiceSpeechBenchmarkDeadlines.forMode(mode)
) {
    init {
        require(backends.isNotEmpty() && backends.distinct().size == backends.size && backends.all { it in engine.supportedBackends })
        require(threads in 1..4 && speechRate.isFinite() && speechRate in .5f..2f)
        require(protocolId.matches(Regex("[A-Za-z0-9._-]{1,120}")))
        require(fixtureVersion in 1..1000 && (fixtureHash == null || fixtureHash.matches(Regex("[0-9a-f]{64}"))))
        require(captionOffsetMs in -500..500)
        require(playbackProtocolVersion in 0..VoiceSpeechPlaybackProtocol.VERSION)
        require(playbackScenarios.isNotEmpty() && playbackScenarios.size <= 6 && playbackScenarios.distinct().size == playbackScenarios.size)
        require(sourceCommit == null || sourceCommit.matches(Regex("[0-9a-f]{40,64}")))
        require(engineVoiceId == null || engineVoiceId.isNotBlank() && engineVoiceId.length <= 128)
        require(!playback || mode != VoiceSpeechBenchmarkMode.STABILITY)
    }

    val currentProtocol: Boolean get() = protocolId == VoiceSpeechWorkload.PROTOCOL && fixtureVersion == VoiceSpeechWorkload.FIXTURE_VERSION && fixtureHash == VoiceSpeechWorkload.fixtureHash(workload) && (!playback || playbackProtocolVersion == VoiceSpeechPlaybackProtocol.VERSION)

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
    val discoverySummary: String? = null,
    val frontendMs: Long? = null,
    val validationMs: Long? = null,
    val cacheEvictions: Int? = null,
    val profileFinalizeMs: Long? = null,
    val nativeSynthesisMs: Long? = null,
    val audioObservation: VoiceSpeechAudioObservation? = null,
    val cacheBudgetBytes: Long? = null,
    val activeShapeEstimatedBytes: Long? = null,
    val availableMemoryBytes: Long? = null,
    val cacheAdmission: String? = null
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
    val nonSilentSamples: Long?,
    val frontendMs: Long? = null,
    val validationMs: Long? = null,
    val cacheEvictions: Int? = null,
    val profileFinalizeMs: Long? = null,
    val cacheBudgetBytes: Long? = null,
    val activeShapeEstimatedBytes: Long? = null,
    val availableMemoryBytes: Long? = null,
    val cacheAdmission: String? = null,
    val audioMeasurementAvailable: Boolean? = null,
    val audioFinite: Boolean? = null,
    val validationReason: String? = null,
    val outputTensorCount: Long? = null,
    val outputFrames: Long? = null,
    val outputElementType: Long? = null,
    val outputRank: Long? = null,
    val durationCount: Long? = null,
    val durationElementType: Long? = null,
    val durationRank: Long? = null,
    val durationSum: Long? = null,
    val durationMin: Long? = null,
    val durationMax: Long? = null,
    val durationStatsAvailable: Boolean? = null,
    val durationSumOverflow: Boolean? = null
) {
    companion object {
        fun from(e: VoiceSpeechExecutionEvidence) = VoiceSpeechBenchmarkFailureObservation(
            e.profileAvailable, e.profileId, e.profileReused, e.acceleratorMicros, e.cpuMicros, e.prepareMs, e.currentPrepareMs,
            e.synthesisMs, e.tokenCount, e.hardwareType, e.cacheHit, e.cachedShapeCount, e.cacheLimit, e.cacheEstimatedBytes,
            e.audioRms.takeIf { e.audioMeasurementAvailable == true }, e.audioPeak.takeIf { e.audioMeasurementAvailable == true },
            e.clippedSamples.takeIf { e.audioMeasurementAvailable == true }, e.nonSilentSamples.takeIf { e.audioMeasurementAvailable == true }, e.frontendMs, e.validationMs, e.cacheEvictions, e.profileFinalizeMs,
            e.cacheBudgetBytes, e.activeShapeEstimatedBytes, e.availableMemoryBytes, e.cacheAdmission,
            e.audioMeasurementAvailable, e.audioFinite, e.validationReason, e.outputTensorCount, e.outputFrames, e.outputElementType, e.outputRank,
            e.durationCount, e.durationElementType, e.durationRank, e.durationSum, e.durationMin, e.durationMax, e.durationStatsAvailable, e.durationSumOverflow
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
    val preparationThermalBefore: Int? = null,
    val cancellationStage: VoiceSpeechStage? = null,
    val cancellationNativeRetirementMs: Long? = null,
    val cancellationNativeStage: VoiceSpeechStage? = null,
    val stopReason: VoiceSpeechBenchmarkStopReason? = null,
    val deadline: VoiceSpeechBenchmarkTimeout? = null,
    val deadlineNativeRetirementMs: Long? = null,
    val cleanupState: VoiceSpeechBenchmarkCleanupState? = null
) {
    val warmSamples: List<VoiceSpeechBenchmarkSample> get() = samples.filter { it.warm }
    val medianMs: Double? get() = median(warmSamples.map { it.generationMs.toDouble() })
    val p95Ms: Long? get() = percentile(warmSamples.map { it.generationMs }.takeIf { it.size >= MIN_PERCENTILE_SAMPLES }.orEmpty(), .95)
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
            fixture, rows.size, warm.size, median(warm.map { it.generationMs.toDouble() }), percentile(warm.map { it.generationMs }.takeIf { it.size >= MIN_PERCENTILE_SAMPLES }.orEmpty(), .95),
            median(warm.map { it.firstPcmMs.toDouble() }), median(warm.mapNotNull { it.realTimeFactor }),
            rows.sumOf { it.generationMs }, rows.sumOf { it.audioMs }, rows.sumOf { it.chunks.size }
        )
    }

    companion object {
        const val MIN_PERCENTILE_SAMPLES = 20
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

internal data class VoiceSpeechBenchmarkAudioReference(
    val comparison: VoiceSpeechAudioComparison,
    val comparedChunks: Int = 0,
    val observedChunks: Int = 0,
    val fixture: VoiceSpeechBenchmarkFixture? = null,
    val repeat: Int? = null,
    val chunk: Int? = null
)

internal data class VoiceSpeechBenchmarkReport(
    val startedAtMillis: Long,
    val configuration: VoiceSpeechBenchmarkConfiguration,
    val state: VoiceSpeechBenchmarkRunState = VoiceSpeechBenchmarkRunState.RUNNING,
    val results: List<VoiceSpeechBenchmarkBackendResult> = if (configuration.engine == VoiceSpeechBenchmarkEngine.NATIVE_KOKORO) configuration.backends.map(::VoiceSpeechBenchmarkBackendResult) else emptyList(),
    val finishedAtMillis: Long? = null,
    val issue: VoiceSpeechBenchmarkIssue? = null,
    val activeFixture: VoiceSpeechBenchmarkFixture? = null,
    val activeRepeat: Int? = null,
    val activeChunk: Int? = null,
    val playbackResults: List<VoiceSpeechPlaybackResult> = emptyList(),
    val engineResults: List<VoiceSpeechEngineBenchmarkResult> = emptyList()
) {
    val completedSamples: Int get() = when {
        configuration.playback -> playbackResults.size
        configuration.engine != VoiceSpeechBenchmarkEngine.NATIVE_KOKORO -> engineResults.sumOf { it.samples.size }
        else -> results.sumOf { it.samples.size }
    }
    val completedChunks: Int get() = when {
        configuration.playback -> playbackResults.sumOf { it.chunks }
        configuration.engine != VoiceSpeechBenchmarkEngine.NATIVE_KOKORO -> engineResults.sumOf { result -> result.samples.sumOf { it.pcmChunks } + (result.partialSample?.pcmChunks ?: 0) }
        else -> results.sumOf { result -> result.samples.sumOf { it.chunks.size } + result.incompleteChunks.size }
    }
    val totalSamples: Int get() = configuration.backends.size * if (configuration.playback) VoiceSpeechPlaybackProtocol.plans(configuration).size else VoiceSpeechBenchmarkFixture.entries.size * (configuration.mode.warmRepeats + 1)
    val isRunning: Boolean get() = state == VoiceSpeechBenchmarkRunState.RUNNING
    val outcome: VoiceSpeechBenchmarkOutcome
        get() = when {
            isRunning -> VoiceSpeechBenchmarkOutcome.RUNNING
            state == VoiceSpeechBenchmarkRunState.CANCELLED -> VoiceSpeechBenchmarkOutcome.CANCELLED
            issue == VoiceSpeechBenchmarkIssue.INVALID_WORKLOAD -> VoiceSpeechBenchmarkOutcome.INVALID_WORKLOAD
            state == VoiceSpeechBenchmarkRunState.INTERRUPTED -> VoiceSpeechBenchmarkOutcome.INTERRUPTED
            !configuration.currentProtocol -> VoiceSpeechBenchmarkOutcome.HISTORICAL
            configuration.engine != VoiceSpeechBenchmarkEngine.NATIVE_KOKORO -> if (engineResults.any { it.state == VoiceSpeechBenchmarkBackendState.FAILED || it.state == VoiceSpeechBenchmarkBackendState.UNSUPPORTED }) VoiceSpeechBenchmarkOutcome.PARTIAL else VoiceSpeechBenchmarkOutcome.NO_QUALIFIED_COMPARISON
            configuration.playback -> playbackOutcome()
            qualifiedResults().isEmpty() -> VoiceSpeechBenchmarkOutcome.NO_QUALIFIED_COMPARISON
            results.any { it !in qualifiedResults() } -> VoiceSpeechBenchmarkOutcome.PARTIAL
            else -> VoiceSpeechBenchmarkOutcome.QUALIFIED
        }

    private fun playbackOutcome(): VoiceSpeechBenchmarkOutcome {
        if (playbackResults.isEmpty()) return VoiceSpeechBenchmarkOutcome.NO_QUALIFIED_COMPARISON
        if (playbackResults.none { it.state == VoiceSpeechPlaybackState.COMPLETE && it.chunks > 0 || it.quality == VoiceSpeechPlaybackQuality.CANCELLATION_VALIDATED }) return VoiceSpeechBenchmarkOutcome.NO_QUALIFIED_COMPARISON
        if (playbackResults.any { it.state in setOf(VoiceSpeechPlaybackState.FAILED, VoiceSpeechPlaybackState.UNSUPPORTED) || it.quality in setOf(VoiceSpeechPlaybackQuality.COMPLETE_WITH_STALLS, VoiceSpeechPlaybackQuality.SLOW_START, VoiceSpeechPlaybackQuality.INCOMPLETE, VoiceSpeechPlaybackQuality.CANCELLATION_TARGET_NOT_REACHED) }) return VoiceSpeechBenchmarkOutcome.PARTIAL
        if (configuration.backends.any { playbackAudioReference(it) == VoiceSpeechAudioReferenceStatus.MISMATCH }) return VoiceSpeechBenchmarkOutcome.PARTIAL
        return if (qualifiedPlaybackBackends().size == configuration.backends.size) VoiceSpeechBenchmarkOutcome.QUALIFIED else VoiceSpeechBenchmarkOutcome.NO_QUALIFIED_COMPARISON
    }

    /** Only the controlled full suite may rank devices. A successful smoke test is not a comparison. */
    fun qualifiedPlaybackBackends(): List<VoiceSpeechBackend> {
        if (configuration.engine != VoiceSpeechBenchmarkEngine.NATIVE_KOKORO ||
            !configuration.playback ||
            !configuration.currentProtocol ||
            state != VoiceSpeechBenchmarkRunState.COMPLETE ||
            issue != null ||
            configuration.mode != VoiceSpeechBenchmarkMode.FULL ||
            configuration.calibrationPolicy != VoiceSpeechCalibrationPolicy.CONTROLLED_ZERO ||
            configuration.playbackScenarios.toSet() != VoiceSpeechPlaybackScenario.entries.toSet()
        ) {
            return emptyList()
        }
        val plans = VoiceSpeechPlaybackProtocol.plans(configuration)
        val complete = configuration.backends.filter { backend ->
            val rows = playbackResults.filter { it.requestedBackend == backend }
            results.singleOrNull { it.requestedBackend == backend }?.let { it.state == VoiceSpeechBenchmarkBackendState.COMPLETE && it.failure == null } == true &&
                rows.size == plans.size &&
                rows.zip(plans).all { (row, plan) ->
                    row.scenario == plan.scenario &&
                        row.repeat == plan.repeat &&
                        row.cancellationTarget == plan.cancellationTarget &&
                        row.warmup == plan.warmup &&
                        row.expectedResident == plan.expectedResident &&
                        row.arrivalIntervalMs == plan.arrivalIntervalMs &&
                        row.manifestWordsExpected == plan.manifest.spans.size &&
                        (!plan.expectedResident || row.engineReused == true) &&
                        row.fixtureHash == plan.manifest.hash &&
                        row.cleanupState == "RETIRED" &&
                        row.thermalBefore in 0..1 &&
                        row.thermalAfter in 0..1 &&
                        (plan.cancellationTarget != null || row.chunkRecords.size == row.chunks && row.chunkRecords.all { it.verifiedAudio && it.inputBytes in 1..VoiceSpeechWorkload.MAX_PHRASE_BYTES && it.inputSha256?.matches(Regex("[a-f0-9]{64}")) == true }) &&
                        row.quality in setOf(VoiceSpeechPlaybackQuality.PLAYBACK_VALIDATED, VoiceSpeechPlaybackQuality.CANCELLATION_VALIDATED) &&
                        (plan.cancellationTarget == VoiceSpeechCancellationTarget.PREPARATION || row.requestedThreads == configuration.threads && row.effectiveThreads == configuration.threads) &&
                        row.calibrations.all { it.offsetMs == 0 } &&
                        (plan.cancellationTarget != null || row.calibrations.isNotEmpty())
                }
        }
        if (VoiceSpeechBackend.CPU !in complete) return emptyList()
        return complete.filter { playbackAudioReference(it) in setOf(VoiceSpeechAudioReferenceStatus.CPU_REFERENCE, VoiceSpeechAudioReferenceStatus.CONSISTENT) }
    }

    fun playbackAudioReference(backend: VoiceSpeechBackend): VoiceSpeechAudioReferenceStatus {
        if (configuration.engine != VoiceSpeechBenchmarkEngine.NATIVE_KOKORO) return VoiceSpeechAudioReferenceStatus.UNOBSERVED
        val rows = playbackResults.filter { it.requestedBackend == backend && it.cancellationTarget == null }
        if (rows.isEmpty()) return VoiceSpeechAudioReferenceStatus.UNOBSERVED
        val comparisons = rows.flatMap { row ->
            val cpu = playbackResults.singleOrNull { it.requestedBackend == VoiceSpeechBackend.CPU && it.scenario == row.scenario && it.repeat == row.repeat && it.fixtureHash == row.fixtureHash }
            if (row.chunkRecords.isEmpty() || cpu == null || row.chunkRecords.size != cpu.chunkRecords.size) {
                listOf(VoiceSpeechAudioReferenceStatus.UNOBSERVED)
            } else {
                row.chunkRecords.zip(cpu.chunkRecords).map { (candidate, reference) ->
                    if (candidate.inputSha256 != candidate.audioObservation?.inputSha256 || reference.inputSha256 != reference.audioObservation?.inputSha256) {
                        VoiceSpeechAudioReferenceStatus.UNOBSERVED
                    } else {
                        VoiceSpeechAudioReference.compare(reference.audioObservation, candidate.audioObservation).status
                    }
                }
            }
        }
        return when {
            VoiceSpeechAudioReferenceStatus.MISMATCH in comparisons -> VoiceSpeechAudioReferenceStatus.MISMATCH
            VoiceSpeechAudioReferenceStatus.UNOBSERVED in comparisons -> VoiceSpeechAudioReferenceStatus.UNOBSERVED
            backend == VoiceSpeechBackend.CPU -> VoiceSpeechAudioReferenceStatus.CPU_REFERENCE
            else -> VoiceSpeechAudioReferenceStatus.CONSISTENT
        }
    }

    fun qualifiedResults(): List<VoiceSpeechBenchmarkBackendResult> {
        if (configuration.engine != VoiceSpeechBenchmarkEngine.NATIVE_KOKORO || state != VoiceSpeechBenchmarkRunState.COMPLETE || issue != null || !configuration.currentProtocol || configuration.playback) return emptyList()
        val expected = VoiceSpeechBenchmarkFixture.entries.size * (configuration.mode.warmRepeats + 1)
        val complete = results.filter { result ->
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
                                chunk.audioObservation?.let { it.valid && it.inputSha256 == chunk.inputSha256 && it.inputBytes == chunk.inputBytes && it.tokenDurations.size == chunk.tokenCount && it.audioMs == chunk.audioMs } == true &&
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
        if (complete.none { it.requestedBackend == VoiceSpeechBackend.CPU }) return emptyList()
        return complete.filter { audioReference(it).comparison.status in setOf(VoiceSpeechAudioReferenceStatus.CPU_REFERENCE, VoiceSpeechAudioReferenceStatus.CONSISTENT) }
    }

    /** Match only this run's frozen configuration and exact fixture/repeat/chunk. */
    fun audioReference(result: VoiceSpeechBenchmarkBackendResult): VoiceSpeechBenchmarkAudioReference {
        if (configuration.engine != VoiceSpeechBenchmarkEngine.NATIVE_KOKORO) return VoiceSpeechBenchmarkAudioReference(VoiceSpeechAudioComparison(VoiceSpeechAudioReferenceStatus.UNOBSERVED, VoiceSpeechAudioReferenceReason.MEASUREMENT_MISSING))
        val cpu = results.singleOrNull { it.requestedBackend == VoiceSpeechBackend.CPU }
        val rows = result.samples.flatMap { sample -> sample.chunks.map { Triple(sample.fixture, sample.repeat, it) } } +
            result.incompleteChunks.mapNotNull { chunk -> result.incompleteFixture?.let { fixture -> result.incompleteRepeat?.let { Triple(fixture, it, chunk) } } }
        if (rows.isEmpty()) return VoiceSpeechBenchmarkAudioReference(VoiceSpeechAudioComparison(VoiceSpeechAudioReferenceStatus.UNOBSERVED, VoiceSpeechAudioReferenceReason.MEASUREMENT_MISSING))
        val comparisons = rows.map { (fixture, repeat, chunk) ->
            val reference = cpu?.samples?.singleOrNull { it.fixture == fixture && it.repeat == repeat }?.chunks?.singleOrNull { it.index == chunk.index }
            VoiceSpeechBenchmarkAudioReference(
                VoiceSpeechAudioReference.compare(reference?.audioObservation, chunk.audioObservation),
                fixture = fixture,
                repeat = repeat,
                chunk = chunk.index
            )
        }
        val compared = comparisons.count { it.comparison.status in setOf(VoiceSpeechAudioReferenceStatus.CONSISTENT, VoiceSpeechAudioReferenceStatus.MISMATCH) }
        val first = comparisons.firstOrNull { it.comparison.status == VoiceSpeechAudioReferenceStatus.MISMATCH }
            ?: comparisons.firstOrNull { it.comparison.status == VoiceSpeechAudioReferenceStatus.UNOBSERVED }
        return first?.copy(comparedChunks = compared, observedChunks = rows.size)
            ?: VoiceSpeechBenchmarkAudioReference(
                VoiceSpeechAudioComparison(if (result.requestedBackend == VoiceSpeechBackend.CPU) VoiceSpeechAudioReferenceStatus.CPU_REFERENCE else VoiceSpeechAudioReferenceStatus.CONSISTENT),
                compared,
                rows.size
            )
    }

    /** An accelerator must win the same three-shape workload by >=10%, including setup. */
    fun recommendation(): VoiceSpeechBackend? {
        if (configuration.engine != VoiceSpeechBenchmarkEngine.NATIVE_KOKORO) return null
        if (configuration.playback) {
            val eligible = qualifiedPlaybackBackends()
            if (VoiceSpeechBackend.CPU !in eligible) return null
            fun cost(backend: VoiceSpeechBackend) = playbackResults.filter { it.requestedBackend == backend && it.cancellationTarget == null }.sumOf { it.elapsedMs }
            val fastest = eligible.filter { it != VoiceSpeechBackend.CPU }.minByOrNull(::cost) ?: return VoiceSpeechBackend.CPU
            return if (cost(fastest) * 10 <= cost(VoiceSpeechBackend.CPU) * 9) fastest else VoiceSpeechBackend.CPU
        }
        val eligible = qualifiedResults()
        val cpu = eligible.firstOrNull { it.requestedBackend == VoiceSpeechBackend.CPU } ?: return null
        fun cost(result: VoiceSpeechBenchmarkBackendResult) = checkNotNull(result.sessionPrepareMs) + result.samples.sumOf { it.generationMs }
        val fastest = eligible.filter { it.requestedBackend != VoiceSpeechBackend.CPU }.minByOrNull(::cost) ?: return VoiceSpeechBackend.CPU
        return if (cost(fastest) * 10 <= cost(cpu) * 9) fastest.requestedBackend else VoiceSpeechBackend.CPU
    }
}
