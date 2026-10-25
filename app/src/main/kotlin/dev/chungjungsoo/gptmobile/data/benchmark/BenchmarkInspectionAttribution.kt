package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeInspectionReceipt
import kotlinx.serialization.Serializable

@Serializable
data class BenchmarkInspectionAttribution(
    val joined: Boolean,
    val basis: String,
    val scope: String = "latest inspection invocation only; phaseElapsedMs retains whole client preparation time"
)

internal fun BenchmarkSample.withPreparationEvidence(
    beforeInspectionId: String?,
    latest: RuntimeInspectionReceipt?,
    startedElapsedMs: Long,
    finishedElapsedMs: Long,
    beforeRuntime: BenchmarkRuntimeObservation?
): BenchmarkSample {
    val fresh = latest?.takeIf { it.inspectionId != beforeInspectionId && it.startedAtElapsedMs >= startedElapsedMs && it.observedAtElapsedMs in it.startedAtElapsedMs..finishedElapsedMs }
    val requestMatch = fresh?.runtimeRequestId?.let { id -> runtimeExecutions.any { it.runtimeRequestId == id } } == true
    val artifactMatch = fresh?.artifactDigest?.let { digest -> runtimeExecutions.any { it.engineLoad?.artifactDigest == digest } } == true
    val joined = fresh != null && (requestMatch || artifactMatch)
    return copy(
        nativeLoading = benchmarkNativeLoading(this, beforeRuntime, startedElapsedMs),
        inspectionReceipt = fresh,
        inspectionAttribution = BenchmarkInspectionAttribution(
            joined,
            when {
                requestMatch -> "EXACT_RUNTIME_REQUEST_AND_ATTEMPT_CLOCK"
                artifactMatch -> "EXACT_ARTIFACT_AND_EXCLUSIVE_ATTEMPT_CLOCK"
                fresh != null -> "FRESH_INVOCATION_IN_ATTEMPT_WINDOW_IDENTITY_UNJOINED"
                else -> "NO_FRESH_INSPECTION_RECEIPT"
            }
        )
    )
}
