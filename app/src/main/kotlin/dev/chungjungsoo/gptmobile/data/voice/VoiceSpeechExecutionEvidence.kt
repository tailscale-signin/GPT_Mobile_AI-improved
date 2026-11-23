package dev.chungjungsoo.gptmobile.data.voice

import dev.melo.kokoro.TimedKokoro
import java.util.concurrent.CancellationException

internal enum class VoiceSpeechStage { FRONTEND, PREPARING, SYNTHESIS, VALIDATING, READY, CPU_FALLBACK }

internal enum class VoiceSpeechFailureCode {
    FRONTEND_INPUT_EMPTY,
    FRONTEND_INPUT_TOO_LONG,
    FRONTEND_INPUT_INVALID,
    FRONTEND_INVALID_SEPARATOR,
    FRONTEND_NO_WORDS,
    FRONTEND_TOKEN_LIMIT,
    FRONTEND_INVALID_VOICE,
    FRONTEND_INVALID_SPEED,
    DEVICE_UNAVAILABLE,
    PROVIDER_LOAD,
    GRAPH_PARTITION,
    GRAPH_PREPARATION,
    EXECUTION_EVIDENCE_MISSING,
    CPU_ONLY,
    OUTPUT_INVALID,
    EXECUTION_FAILED;

    val isInputFailure: Boolean get() = name.startsWith("FRONTEND_")
}

/** Numeric/operator provenance only. Kernel profiling is the first inference of this exact graph shape. */
internal data class VoiceSpeechExecutionEvidence(
    val selectedBackend: VoiceSpeechBackend,
    val effectiveProvider: String,
    val modelSha256: String,
    val runtimeId: String,
    val deviceKey: String,
    val stage: VoiceSpeechStage,
    val failure: VoiceSpeechFailureCode? = null,
    val acceleratorKernels: Int = 0,
    val cpuKernels: Int = 0,
    val acceleratorMicros: Long = 0,
    val cpuMicros: Long = 0,
    val prepareMs: Long = 0,
    val synthesisMs: Long = 0,
    val tokenCount: Int = 0,
    val verifiedAudio: Boolean = false,
    val observedAtMillis: Long,
    val nativeCode: Int? = null,
    val hardwareType: Int? = null,
    val hardwareVendorId: Long? = null,
    val hardwareDeviceId: Long? = null,
    val failureStage: VoiceSpeechStage? = null,
    val failedProvider: String? = null,
    val failedTokenCount: Int? = null,
    val profileAvailable: Boolean? = null,
    val profileId: Long? = null,
    val cacheHit: Boolean? = null,
    val currentPrepareMs: Long? = null,
    val cachedShapeCount: Int? = null,
    val cacheLimit: Int? = null,
    val cacheEstimatedBytes: Long? = null,
    val audioRms: Double? = null,
    val audioPeak: Double? = null,
    val clippedSamples: Long? = null,
    val nonSilentSamples: Long? = null,
    val profileReused: Boolean? = null,
    val graphPreparedThisCall: Boolean? = null,
    val discoverySummary: String? = null,
    val failedInputBytes: Int? = null,
    val failedInputByteLimit: Int? = null,
    val failedTokenLimit: Int? = null,
    val failedDiscoverySummary: String? = null
) {
    fun diagnostics(): Map<String, Any> = buildMap {
        put("speech_backend_selected", selectedBackend.name)
        put("speech_backend_effective", effectiveProvider)
        put("speech_model_sha256", modelSha256)
        put("speech_runtime", runtimeId)
        put("speech_device_key", deviceKey)
        put("speech_stage", stage.name)
        failure?.let { put("speech_failure_code", it.name) }
        if (profileAvailable == true) {
            put("speech_accelerator_kernels", acceleratorKernels)
            put("speech_cpu_kernels", cpuKernels)
            put("speech_accelerator_us", acceleratorMicros)
            put("speech_cpu_us", cpuMicros)
        }
        put(
            "speech_profile_scope",
            when (profileAvailable) {
                true -> "first_inference_of_exact_graph_shape"
                false -> "not_collected"
                null -> "unknown"
            }
        )
        profileAvailable?.let { put("speech_profile_available", it) }
        profileId?.let { put("speech_profile_id", it) }
        profileReused?.let { put("speech_profile_reused", it) }
        graphPreparedThisCall?.let { put("speech_graph_prepared_this_call", it) }
        cacheHit?.let { put("speech_graph_cache_hit", it) }
        currentPrepareMs?.let { put("speech_current_prepare_ms", it) }
        cachedShapeCount?.let { put("speech_cached_shape_count", it) }
        cacheLimit?.let { put("speech_graph_cache_limit", it) }
        cacheEstimatedBytes?.let { put("speech_graph_cache_estimated_bytes", it) }
        audioRms?.takeIf { it.isFinite() && it >= 0 }?.let { put("speech_audio_rms", it) }
        audioPeak?.takeIf { it.isFinite() && it >= 0 }?.let { put("speech_audio_peak", it) }
        clippedSamples?.takeIf { it >= 0 }?.let { put("speech_audio_clipped_samples", it) }
        nonSilentSamples?.takeIf { it >= 0 }?.let { put("speech_audio_non_silent_samples", it) }
        safeSpeechDiscoverySummary(discoverySummary)?.let { put("speech_discovery_summary", it) }
        put("speech_prepare_ms", prepareMs)
        put("speech_synthesis_ms", synthesisMs)
        put("speech_token_count", tokenCount)
        put("speech_audio_timing_validated", verifiedAudio)
        put("speech_observed_at_ms", observedAtMillis)
        nativeCode?.let { put("speech_native_code", it) }
        hardwareType?.let { put("speech_hardware_type", it) }
        hardwareVendorId?.let { put("speech_hardware_vendor_id", it) }
        hardwareDeviceId?.let { put("speech_hardware_device_id", it) }
        failureStage?.let { put("speech_failure_stage", it.name) }
        failedProvider?.let { put("speech_failed_provider", it) }
        failedTokenCount?.let { put("speech_failed_token_count", it) }
        failedInputBytes?.let { put("speech_failed_input_bytes", it) }
        failedInputByteLimit?.let { put("speech_failed_input_byte_limit", it) }
        failedTokenLimit?.let { put("speech_failed_token_limit", it) }
        safeSpeechDiscoverySummary(failedDiscoverySummary)?.let { put("speech_failed_discovery_summary", it) }
    }
}

internal fun safeSpeechDiscoverySummary(value: String?): String? = value?.takeIf {
    it.length in 1..512 && it.all { character -> character.isLetterOrDigit() && character.code < 128 || character in "_.=,:;-" }
}

/** Never reads the engine's previous success after a throw: this snapshot belongs to this failed native call. */
internal fun speechFailureExecutionEvidence(error: Exception, requested: VoiceSpeechBackend, deviceKey: String): VoiceSpeechExecutionEvidence? {
    val failure = error as? TimedKokoro.Failure ?: return null
    val native = failure.currentEvidence ?: return null
    val stage = VoiceSpeechStage.entries.firstOrNull { it.name.equals(failure.stage, ignoreCase = true) }
        ?.takeIf { it == VoiceSpeechStage.SYNTHESIS || it == VoiceSpeechStage.VALIDATING } ?: return null
    val provider = failure.provider.takeIf { it in setOf("cpu", "qnn-gpu", "qnn-htp") }
    val effective = when {
        requested == VoiceSpeechBackend.CPU -> "cpu"
        native.profileAvailable && native.acceleratorKernels > 0 -> provider ?: "unverified"
        native.profileAvailable && native.cpuKernels > 0 -> "cpu"
        else -> "unverified"
    }
    return VoiceSpeechExecutionEvidence(
        requested, effective, KokoroModelPack.MODEL_SHA256, TimedKokoro.RUNTIME_ID, deviceKey, stage,
        failure = VoiceSpeechFailureCode.entries.firstOrNull { it.name == failure.code } ?: VoiceSpeechFailureCode.EXECUTION_FAILED,
        acceleratorKernels = native.acceleratorKernels, cpuKernels = native.cpuKernels,
        acceleratorMicros = native.acceleratorMicros, cpuMicros = native.cpuMicros,
        prepareMs = native.prepareMs, synthesisMs = native.synthesisMs, tokenCount = native.tokenCount,
        verifiedAudio = false, observedAtMillis = System.currentTimeMillis(), nativeCode = failure.nativeCode,
        hardwareType = native.deviceType, hardwareVendorId = native.vendorId, hardwareDeviceId = native.deviceId,
        failureStage = stage, failedProvider = provider, failedTokenCount = failure.tokenCount,
        profileAvailable = native.profileAvailable, profileId = native.profileId, cacheHit = native.cacheHit,
        currentPrepareMs = native.currentPrepareMs, cachedShapeCount = native.cachedShapeCount,
        cacheLimit = native.cacheLimit, cacheEstimatedBytes = native.cacheEstimatedBytes,
        audioRms = native.audioRms, audioPeak = native.audioPeak, clippedSamples = native.clippedSamples,
        nonSilentSamples = native.nonSilentSamples, profileReused = native.profileReused,
        graphPreparedThisCall = native.graphPreparedThisCall,
        discoverySummary = safeSpeechDiscoverySummary(native.discoverySummary),
        failedInputBytes = failure.inputBytes, failedInputByteLimit = failure.inputByteLimit,
        failedTokenLimit = failure.tokenLimit, failedDiscoverySummary = safeSpeechDiscoverySummary(failure.discoverySummary)
    )
}

/** CPU preparation failed after a coded accelerator rejection; no phrase or driver log is added. */
internal class VoiceSpeechPreparationFailure(
    val executionEvidence: VoiceSpeechExecutionEvidence,
    cause: Exception,
    originalAcceleratorFailure: Throwable? = null
) : IllegalStateException("CPU speech recovery could not be prepared.", cause) {
    init {
        if (originalAcceleratorFailure != null && originalAcceleratorFailure !== cause) {
            addSuppressed(originalAcceleratorFailure)
        }
    }
}

/** Only accelerator-to-CPU recovery acquires this receipt. Cancellation and Error keep their identity. */
internal inline fun <T> preserveVoicePreparationFailure(
    executionEvidence: VoiceSpeechExecutionEvidence?,
    originalAcceleratorFailure: Throwable? = null,
    prepare: () -> T
): T = try {
    prepare()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    if (executionEvidence?.failure == null || executionEvidence.failedProvider !in setOf("qnn-gpu", "qnn-htp")) throw error
    throw VoiceSpeechPreparationFailure(executionEvidence, error, originalAcceleratorFailure)
}

internal data class VoiceBackendCapability(
    val key: String,
    val backend: VoiceSpeechBackend,
    val observedAtMillis: Long,
    val sampleMs: List<Long> = emptyList(),
    val verifiedAccelerator: Boolean = false,
    val failure: VoiceSpeechFailureCode? = null,
    val failureStage: VoiceSpeechStage? = null,
    val nativeCode: Int? = null,
    val tokenCount: Int? = null,
    val comparisonScope: String? = null,
    val protocol: String = VoiceMeasuredBackendPolicy.COMPARISON_PROTOCOL,
    val speakerId: Int? = null,
    val speechRate: Float? = null,
    val inputBytes: Int? = null,
    val inputByteLimit: Int? = null,
    val tokenLimit: Int? = null,
    val discoverySummary: String? = null,
    val thermalStatuses: List<Int?> = emptyList()
) {
    /** Invalid text cannot disable hardware that successfully synthesized earlier text. */
    val cacheable: Boolean get() = failure?.isInputFailure != true && failureStage != VoiceSpeechStage.FRONTEND

    val scoreMs: Long?
        get() = sampleMs.takeIf {
            protocol == VoiceMeasuredBackendPolicy.COMPARISON_PROTOCOL &&
                VoiceMeasuredBackendPolicy.thermalQualified(thermalStatuses) &&
                it.size == VoiceMeasuredBackendPolicy.FIXTURE_COUNT &&
                it.all { value -> value in 1..300_000 }
        }
            ?.let { samples ->
                // Both traversals include every chunk and graph transition; setup belongs to the first group.
                (samples.sum() + samples.size - 1) / samples.size
            }

    fun current(now: Long): Boolean {
        val ttl = if (failure == null) 7 * 24 * 60 * 60_000L else 6 * 60 * 60_000L
        return now >= observedAtMillis && now - observedAtMillis <= ttl
    }
}

internal object VoiceMeasuredBackendPolicy {
    // Bump whenever the fixed fixtures or their cost aggregation changes.
    const val COMPARISON_PROTOCOL = VoiceSpeechWorkload.PROTOCOL + "-thermal-v1"
    const val FIXTURE_COUNT = VoiceSpeechWorkload.CALIBRATION_FIXTURE_COUNT
    val thermalSampleCount: Int get() = 1 + VoiceSpeechWorkload.calibrationPhrases.size * 2

    fun thermalQualified(statuses: List<Int?>): Boolean = statuses.size == thermalSampleCount && statuses.all { it != null && it in 0..1 }

    fun comparisonOrder(revision: Long): List<VoiceSpeechBackend> {
        val backends = listOf(VoiceSpeechBackend.CPU, VoiceSpeechBackend.QNN_GPU, VoiceSpeechBackend.QNN_HTP)
        val start = Math.floorMod(revision, backends.size.toLong()).toInt()
        return backends.drop(start) + backends.take(start)
    }

    /** Hardware names are not a ranking. Require measured CPU and real accelerator execution. */
    fun choose(records: List<VoiceBackendCapability>, now: Long): VoiceSpeechBackend {
        val eligible = records.filter {
            it.current(now) &&
                it.failure == null &&
                it.scoreMs != null &&
                it.comparisonScope?.matches(Regex("[0-9a-f]{64}")) == true &&
                it.speakerId != null &&
                it.speakerId >= 0 &&
                it.speechRate?.let { rate -> rate.isFinite() && rate in .5f..2f } == true
        }
        // Ambiguous baselines must not choose an arbitrary settings/device cohort.
        val cpu = eligible.singleOrNull { it.backend == VoiceSpeechBackend.CPU } ?: return VoiceSpeechBackend.CPU
        val accelerated = eligible.filter {
            it.backend in setOf(VoiceSpeechBackend.QNN_GPU, VoiceSpeechBackend.QNN_HTP) &&
                it.verifiedAccelerator &&
                it.comparisonScope == cpu.comparisonScope &&
                it.speakerId == cpu.speakerId &&
                it.speechRate == cpu.speechRate
        }.minByOrNull { it.sampleMs.sum() } ?: return VoiceSpeechBackend.CPU
        // Small/noisy differences do not justify slower graph preparation or a
        // backend switch. Rounding the means could admit a smaller gain or hide the faster engine.
        // Eligibility bounds each six-group total to 1,800,000 ms, so scaling is safe.
        return if (accelerated.sampleMs.sum() * 10 <= cpu.sampleMs.sum() * 9) accelerated.backend else VoiceSpeechBackend.CPU
    }
}

internal data class VoiceQualificationMeasurement(val sampleMs: List<Long>, val thermalStatuses: List<Int?>) {
    val rankingEligible: Boolean get() = VoiceMeasuredBackendPolicy.thermalQualified(thermalStatuses)
}

/** The complete grouped workload is validated before its first native call. */
internal fun measureVoiceQualification(
    fixtures: List<List<String>>,
    backend: VoiceSpeechBackend,
    speakerId: Int,
    speechRate: Float,
    openedAt: Long,
    nanoTime: () -> Long,
    checkActive: () -> Unit,
    openedThermalStatus: Int? = null,
    thermalStatus: () -> Int? = { null },
    generate: (String, Int, Float) -> VoiceSpeechExecutionEvidence
): VoiceQualificationMeasurement {
    check(fixtures.size == VoiceMeasuredBackendPolicy.FIXTURE_COUNT && fixtures.all { it.isNotEmpty() }) { "FRONTEND_INPUT_INVALID" }
    fixtures.flatten().forEach(VoiceSpeechWorkload::validate)
    check(speakerId >= 0) { "FRONTEND_INVALID_VOICE" }
    check(speechRate.isFinite() && speechRate in .5f..2f) { "FRONTEND_INVALID_SPEED" }
    val temperatures = mutableListOf(openedThermalStatus)
    val samples = fixtures.mapIndexed { index, phrases ->
        checkActive()
        val started = if (index == 0) openedAt else nanoTime()
        for (phrase in phrases) {
            checkActive()
            temperatures += thermalStatus()
            val receipt = generate(phrase, speakerId, speechRate)
            temperatures += thermalStatus()
            checkActive()
            check(receipt.verifiedAudio && receipt.nonSilentSamples?.let { it > 0 } == true) { "OUTPUT_INVALID" }
            check(receipt.effectiveProvider == VoiceSpeechBenchmarkBackendResult.provider(backend)) { "CPU_ONLY" }
            if (backend != VoiceSpeechBackend.CPU) {
                check(receipt.profileAvailable == true) { "EXECUTION_EVIDENCE_MISSING" }
                check(receipt.acceleratorKernels > 0) { "CPU_ONLY" }
            }
        }
        val ended = nanoTime()
        check(ended > started) { "EXECUTION_FAILED" }
        ((ended - started) / 1_000_000).coerceAtLeast(1)
    }
    return VoiceQualificationMeasurement(samples, temperatures)
}
