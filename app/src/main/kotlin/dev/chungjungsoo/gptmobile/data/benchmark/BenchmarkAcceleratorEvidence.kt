package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.serialization.Serializable

@Serializable
data class BenchmarkAcceleratorEvidence(
    val status: String,
    val requestedBackend: String?,
    val actualBackend: String?,
    val artifactDigest: String?,
    val actualOffloadLayers: Int?,
    val nativeEntry: Boolean?,
    val prefillObserved: Boolean?,
    val decodeObserved: Boolean?,
    val terminalObserved: Boolean?,
    val visibleOutput: Boolean,
    val cleanupConfirmed: Boolean?,
    val qualificationStatus: String = "NOT_QUALIFIED",
    val scope: String = "one measured case; full device qualification additionally requires repeated generation and cancellation recovery for the exact device/build/backend"
)

internal fun benchmarkAcceleratorEvidence(run: BenchmarkRun, sample: BenchmarkSample): BenchmarkAcceleratorEvidence {
    val executions = sample.runtimeExecutions
    val backends = executions.mapNotNull { it.engineLoad?.actualBackend }.distinct()
    val backend = backends.singleOrNull()
    val canonicalBackends = executions.map { benchmarkBackendKind(it.engineLoad?.actualBackend) }.distinct()
    val canonicalBackend = canonicalBackends.singleOrNull()
    val artifacts = executions.mapNotNull { it.engineLoad?.artifactDigest }.distinct()
    val offloads = executions.map { it.engineLoad?.actualOffloadLayers }
    val offload = offloads.takeIf { it.isNotEmpty() && it.all { layers -> layers != null } }?.minOf { requireNotNull(it) }
    val nativeEntry = executions.takeIf { it.isNotEmpty() }?.all { it.knownDispatched }
    val prefill = executions.takeIf { it.isNotEmpty() && it.all { receipt -> receipt.promptTokens != null } }?.all { requireNotNull(it.promptTokens) > 0 }
    val decode = executions.takeIf { it.isNotEmpty() && it.all { receipt -> receipt.sampledTokens != null } }?.all { requireNotNull(it.sampledTokens) > 0 }
    val terminal = executions.takeIf { it.isNotEmpty() }?.all { it.hasNativeTerminal }
    val cleanup = sample.runtimeCleanup?.safeForSuccessor
    val proven = canonicalBackend != null && artifacts.size == 1 && nativeEntry == true && prefill == true && decode == true && terminal == true && sample.outputCharacters > 0 && cleanup == true && (canonicalBackend !in setOf("gpu", "opencl") || offload != null && offload > 0)
    return BenchmarkAcceleratorEvidence(
        when {
            sample.outcome == BenchmarkOutcome.UNSUPPORTED -> "UNSUPPORTED"
            !sample.knownExecuted() -> "NOT_TESTED"
            proven -> "EXECUTION_PROVEN"
            else -> "NOT_PROVEN"
        },
        run.requestedAccelerator, backend, artifacts.singleOrNull(), offload, nativeEntry, prefill, decode, terminal, sample.outputCharacters > 0, cleanup
    )
}
