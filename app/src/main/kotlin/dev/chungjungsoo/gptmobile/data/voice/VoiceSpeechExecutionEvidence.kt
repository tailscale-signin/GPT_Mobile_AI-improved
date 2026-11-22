package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CancellationException

internal enum class VoiceSpeechStage { FRONTEND, PREPARING, SYNTHESIS, VALIDATING, READY, CPU_FALLBACK }

internal enum class VoiceSpeechFailureCode {
    DEVICE_UNAVAILABLE,
    PROVIDER_LOAD,
    GRAPH_PARTITION,
    GRAPH_PREPARATION,
    CPU_ONLY,
    OUTPUT_INVALID,
    EXECUTION_FAILED
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
    val failedTokenCount: Int? = null
) {
    fun diagnostics(): Map<String, Any> = buildMap {
        put("speech_backend_selected", selectedBackend.name)
        put("speech_backend_effective", effectiveProvider)
        put("speech_model_sha256", modelSha256)
        put("speech_runtime", runtimeId)
        put("speech_device_key", deviceKey)
        put("speech_stage", stage.name)
        failure?.let { put("speech_failure_code", it.name) }
        put("speech_accelerator_kernels", acceleratorKernels)
        put("speech_cpu_kernels", cpuKernels)
        put("speech_accelerator_us", acceleratorMicros)
        put("speech_cpu_us", cpuMicros)
        put("speech_profile_scope", "first_inference_of_exact_graph_shape")
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
    }
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
    val tokenCount: Int? = null
) {
    val scoreMs: Long?
        get() = sampleMs.takeIf { it.size == VoiceMeasuredBackendPolicy.FIXTURE_COUNT && it.all { value -> value in 1..300_000 } }
            ?.let { samples ->
                // Every current phrase shape contributes, including preparation in the first sample.
                // Legacy pairs measure a different workload and cannot rank against these receipts.
                (samples.sum() + samples.size - 1) / samples.size
            }

    fun current(now: Long): Boolean {
        val ttl = if (failure == null) 7 * 24 * 60 * 60_000L else 6 * 60 * 60_000L
        return now >= observedAtMillis && now - observedAtMillis <= ttl
    }
}

internal object VoiceMeasuredBackendPolicy {
    // Bump whenever the fixed fixtures or their cost aggregation changes.
    const val COMPARISON_PROTOCOL = "fixed-three-phrases-v1-mean-complete-cost-v1"
    const val FIXTURE_COUNT = 3

    /** Hardware names are not a ranking. Require measured CPU and real accelerator execution. */
    fun choose(records: List<VoiceBackendCapability>, now: Long): VoiceSpeechBackend {
        val eligible = records.filter { it.current(now) && it.failure == null && it.scoreMs != null }
        val cpu = eligible.firstOrNull { it.backend == VoiceSpeechBackend.CPU } ?: return VoiceSpeechBackend.CPU
        val accelerated = eligible.filter {
            it.backend in setOf(VoiceSpeechBackend.QNN_GPU, VoiceSpeechBackend.QNN_HTP) && it.verifiedAccelerator
        }.minByOrNull { it.scoreMs!! } ?: return VoiceSpeechBackend.CPU
        // Small/noisy differences do not justify slower graph preparation or a
        // backend switch. Scores include graph preparation for different shapes.
        return if (accelerated.scoreMs!! * 10 <= cpu.scoreMs!! * 9) accelerated.backend else VoiceSpeechBackend.CPU
    }
}
