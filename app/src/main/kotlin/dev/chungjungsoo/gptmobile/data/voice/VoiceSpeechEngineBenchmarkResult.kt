package dev.chungjungsoo.gptmobile.data.voice

/** A callback receipt describes delivered PCM, not the SDK's undisclosed internal decoding steps. */
internal data class VoiceSpeechEnginePcmReceipt(val index: Int, val receivedAtMs: Long, val frames: Int, val streamCall: Int)

/** Fixed public input identity and numeric observations only. Neither text nor PCM is retained. */
internal data class VoiceSpeechEngineBenchmarkSample(
    val fixture: VoiceSpeechBenchmarkFixture,
    val repeat: Int,
    val inputBytes: Int,
    val inputSha256: String,
    val generationMs: Long,
    val firstPcmMs: Long?,
    val lastPcmMs: Long?,
    val prepareToFirstPcmMs: Long?,
    val audioFrames: Long,
    val pcmChunks: Int,
    val streamCalls: Int,
    val maximumChunkGapMs: Long?,
    val validatedAudio: Boolean,
    val thermalBefore: Int?,
    val thermalAfter: Int?,
    val audioRms: Double?,
    val audioPeak: Double?,
    val clippedSamples: Long,
    val nonSilentSamples: Long,
    val pcmTimeline: List<VoiceSpeechEnginePcmReceipt> = emptyList(),
    val omittedPcmReceipts: Int = 0
) {
    val warm: Boolean get() = repeat > 0
    val audioMs: Double get() = audioFrames * 1_000.0 / VOICE_PCM_SAMPLE_RATE
    val realTimeFactor: Double? get() = (generationMs / audioMs).takeIf { audioMs > 0 && it.isFinite() }
}

/**
 * SDK delegate requests remain distinct from observed placement, which the current SDK does not
 * expose. Structural PCM validation does not prove words were spoken or qualify an engine winner.
 */
internal data class VoiceSpeechEngineBenchmarkResult(
    val requestedBackend: VoiceSpeechBackend,
    val state: VoiceSpeechBenchmarkBackendState = VoiceSpeechBenchmarkBackendState.PENDING,
    val sessionPrepareMs: Long? = null,
    val preparationThermalBefore: Int? = null,
    val samples: List<VoiceSpeechEngineBenchmarkSample> = emptyList(),
    val partialSample: VoiceSpeechEngineBenchmarkSample? = null,
    val failureCode: String? = null,
    val failedFixture: VoiceSpeechBenchmarkFixture? = null,
    val failedRepeat: Int? = null,
    val failedAttemptMs: Long? = null,
    val cancellationMs: Long? = null,
    val nativeRetirementMs: Long? = null,
    val cleanupState: String = "NOT_OBSERVED",
    val requestedDelegate: String = requestedSpeechDelegate(requestedBackend),
    val observedProvider: String? = null,
    val providerEvidenceStatus: String = "NOT_EXPOSED_BY_SDK",
    val timeoutScope: String? = null,
    val timeoutLimitMs: Long? = null,
    val timeoutElapsedMs: Long? = null
) {
    val measuredCostMs: Long get() = (sessionPrepareMs ?: 0) + samples.sumOf { it.generationMs } + (failedAttemptMs ?: 0)

    fun fixtureStatistics(fixture: VoiceSpeechBenchmarkFixture): VoiceSpeechBenchmarkFixtureStatistics {
        val completed = samples.filter { it.fixture == fixture && it.validatedAudio }
        val warm = completed.filter { it.warm }
        return VoiceSpeechBenchmarkFixtureStatistics(
            fixture, completed.size, warm.size,
            VoiceSpeechBenchmarkBackendResult.median(warm.map { it.generationMs.toDouble() }),
            VoiceSpeechBenchmarkBackendResult.percentile(warm.map { it.generationMs }.takeIf { it.size >= VoiceSpeechBenchmarkBackendResult.MIN_PERCENTILE_SAMPLES }.orEmpty(), .95),
            VoiceSpeechBenchmarkBackendResult.median(warm.mapNotNull { it.firstPcmMs?.toDouble() }),
            VoiceSpeechBenchmarkBackendResult.median(warm.mapNotNull { it.realTimeFactor }),
            completed.sumOf { it.generationMs }, completed.sumOf { it.audioMs }, completed.sumOf { it.pcmChunks }
        )
    }
}

internal fun requestedSpeechDelegate(backend: VoiceSpeechBackend): String = when (backend) {
    VoiceSpeechBackend.LITERT_KOKORO_GPU, VoiceSpeechBackend.LITERT_KOKORO_GPU_OPTIMIZED -> "GPU"
    VoiceSpeechBackend.LITERT_KOKORO_NPU, VoiceSpeechBackend.LITERT_KOKORO_SM8750 -> "NPU"
    VoiceSpeechBackend.LITERT_KOKORO_CPU, VoiceSpeechBackend.PHONON_CPU, VoiceSpeechBackend.POCKET_CPU, VoiceSpeechBackend.POCKET_MULTILINGUAL_CPU -> "CPU"
    else -> "UNSUPPORTED"
}

internal fun VoiceSpeechBenchmarkEngine.mediaPipeEngine(): MediaPipeSpeechEngine? = when (this) {
    VoiceSpeechBenchmarkEngine.NATIVE_KOKORO -> null
    VoiceSpeechBenchmarkEngine.MEDIAPIPE_KOKORO -> MediaPipeSpeechEngine.KOKORO
    VoiceSpeechBenchmarkEngine.MEDIAPIPE_KOKORO_GPU -> MediaPipeSpeechEngine.KOKORO_GPU
    VoiceSpeechBenchmarkEngine.MEDIAPIPE_KOKORO_SM8750 -> MediaPipeSpeechEngine.KOKORO_SM8750
    VoiceSpeechBenchmarkEngine.PHONON -> MediaPipeSpeechEngine.PHONON
    VoiceSpeechBenchmarkEngine.POCKET -> MediaPipeSpeechEngine.POCKET
    VoiceSpeechBenchmarkEngine.POCKET_MULTILINGUAL -> MediaPipeSpeechEngine.POCKET_MULTILINGUAL
}
