package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.agent.ProviderUsageCounter
import dev.chungjungsoo.gptmobile.data.agent.measuredUsageSource
import dev.chungjungsoo.gptmobile.data.localruntime.GgufNativeProgress
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntime
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntimeExecutionIdentity
import dev.chungjungsoo.gptmobile.data.localruntime.ResourceLease
import kotlinx.serialization.Serializable

internal const val STANDARD_BENCHMARK_DEADLINE_MS = 90_000L

/** The recorded total is the same bound used by the runner and the provider request. */
internal fun benchmarkCaseDeadlineMs(mode: BenchmarkMode, test: BenchmarkCase, suiteVersion: Int = 9): Long = when {
    test.performanceTrial != null -> test.performanceTrial.deadlineMs.coerceIn(1, 600_000)
    mode == BenchmarkMode.DELEGATION && test.workload == "delegation-paired" -> 360_000L
    mode == BenchmarkMode.DELEGATION -> 180_000L
    suiteVersion < 10 || mode == BenchmarkMode.QUICK -> STANDARD_BENCHMARK_DEADLINE_MS
    test.workload == "long" || test.workload.contains("context") || test.prompt.length > 12_000 -> 360_000L
    test.category == "tools" -> 300_000L
    else -> 180_000L
}

@Serializable
data class BenchmarkDeadlineReceipt(
    val totalRequestMs: Long,
    val policy: String,
    val suiteVersion: Int,
    val workload: String,
    val preflightLimitMs: Long = 60_000,
    val cleanupWatchdogMs: Long = 15_000,
    val admissionWaitExcluded: Boolean = true,
    val phasePolicyScope: String = "Actual native per-generation load/prefill/decode limits, where exposed, remain in runtimeExecutions.configuration.deadlinePolicy; this receipt is the enclosing app request bound."
)

internal fun benchmarkDeadlineReceipt(mode: BenchmarkMode, test: BenchmarkCase, suiteVersion: Int, readiness: Boolean = false): BenchmarkDeadlineReceipt =
    BenchmarkDeadlineReceipt(
        if (readiness) BenchmarkReadinessRunner.PROBE_DEADLINE_MS else benchmarkCaseDeadlineMs(mode, test, suiteVersion),
        if (readiness) {
            "benchmark-readiness-deadline-v1"
        } else if (suiteVersion >= 10) {
            "benchmark-workload-deadlines-v2"
        } else {
            "benchmark-request-deadline-v1"
        },
        suiteVersion,
        test.workload
    )

@Serializable
data class BenchmarkPhaseInterval(
    val phase: String,
    val startedElapsedMs: Long,
    val durationMs: Long,
    val round: Int? = null,
    val nativeStage: String? = null,
    val nativeOperationId: Long? = null,
    val runtimeRequestId: String? = null,
    val physicalAttemptId: String? = null
)

/** Sequential client-observed phases; native segment counters remain separate measurements. */
internal class BenchmarkPhaseTimeline(private val started: Long, private val now: () -> Long) {
    private val completed = mutableListOf<BenchmarkPhaseInterval>()
    private val totals = linkedMapOf<String, Long>()
    var truncated: Boolean = false
        private set
    private var current = BenchmarkPhaseInterval("session_setup", 0, 0)
    val phase: String get() = current.phase

    fun transition(phase: String, round: Int? = null, native: GgufNativeProgress? = null) {
        if (phase == current.phase && round == current.round && native?.stage == current.nativeStage && native?.operationId == current.nativeOperationId) return
        val elapsed = (now() - started).coerceAtLeast(current.startedElapsedMs)
        val duration = elapsed - current.startedElapsedMs
        totals[current.phase] = (totals[current.phase] ?: 0) + duration
        if (completed.size < 256) completed += current.copy(durationMs = duration) else truncated = true
        current = BenchmarkPhaseInterval(phase, elapsed, 0, round, native?.stage, native?.operationId, native?.runtimeRequestId, native?.physicalAttemptId)
    }

    fun snapshot(at: Long = now()): List<BenchmarkPhaseInterval> = completed + current.copy(durationMs = (at - started - current.startedElapsedMs).coerceAtLeast(0))
    fun elapsedByPhase(at: Long = now()): Map<String, Long> = totals + (current.phase to ((totals[current.phase] ?: 0) + (at - started - current.startedElapsedMs).coerceAtLeast(0)))
}

internal fun benchmarkUsageConfidence(counter: ProviderUsageCounter): String = when {
    counter.value == null -> "unknown"
    measuredUsageSource(counter.source, counter.phase) -> "final_reported"
    counter.source.contains("estimated") -> "estimated"
    else -> "partial_observation"
}

internal fun benchmarkCombinedTokenSource(sources: List<String>): String {
    val distinct = sources.distinct()
    return when {
        distinct.isEmpty() -> "unknown"
        distinct.size == 1 -> distinct.single()
        distinct.all { measuredUsageSource(it) } -> "mixed_reported"
        "unknown" in distinct || "unavailable" in distinct -> "mixed_unknown"
        distinct.all { it.contains("estimated") } -> "mixed_estimated"
        else -> "mixed_observation"
    }
}

internal fun benchmarkCombinedConfidence(confidences: List<String>): String = confidences.distinct().let {
    if (it.isEmpty()) {
        "unknown"
    } else if (it.size == 1) {
        it.single()
    } else {
        "mixed"
    }
}

/** Null means the runtime did not expose that observation; requested settings never fill it. */
@Serializable
data class BenchmarkNativeIdentity(
    val runId: String,
    val attemptId: String,
    val measurementLeaseId: String? = null,
    val measurementOwnerId: String? = null,
    val measurementGeneration: Long? = null,
    val workerGenerationId: String? = null,
    val conversationId: String? = null,
    val engineInstanceId: String? = null,
    val loadOperationId: Long? = null,
    val artifactDigest: String? = null,
    val planId: String? = null,
    val actualRuntime: String? = null,
    val actualAccelerator: String? = null,
    val driverIdentity: String? = null,
    val actualOffloadLayers: Int? = null,
    val configuredOffloadLayers: Int? = null,
    val configuredCpuThreads: Int? = null,
    val observedAt: Long,
    val unknownFields: Set<String> = emptySet(),
    val measurementScope: String = "isolated trial; worker identity captured before cleanup"
)

internal fun benchmarkNativeIdentity(runtime: LocalRuntime, runId: String, attemptId: String, lease: ResourceLease?, identity: LocalRuntimeExecutionIdentity = runtime.executionIdentity): BenchmarkNativeIdentity {
    val actual = runtime.state.value
    return BenchmarkNativeIdentity(
        runId, attemptId, lease?.leaseId, lease?.ownerId, lease?.generation,
        identity.workerGenerationId, identity.conversationId,
        engineInstanceId = identity.engineInstanceId,
        loadOperationId = actual.loadProgress?.operationId,
        artifactDigest = actual.decision?.selected?.artifactDigest,
        planId = actual.decision?.selected?.id ?: actual.admissionReceipt?.planIdentity,
        actualRuntime = actual.backend?.name,
        actualAccelerator = actual.engineSpec?.accelerator,
        driverIdentity = identity.driverIdentity,
        actualOffloadLayers = identity.actualOffloadLayers,
        configuredOffloadLayers = actual.engineSpec?.ggufGpuLayers,
        configuredCpuThreads = actual.engineSpec?.cpuThreads,
        observedAt = System.currentTimeMillis(),
        unknownFields = buildSet {
            if (identity.workerGenerationId == null) add("workerGenerationId")
            if (identity.conversationId == null) add("conversationId")
            if (identity.engineInstanceId == null) add("engineInstanceId")
            if (identity.driverIdentity == null) add("driverIdentity")
            if (identity.actualOffloadLayers == null) add("actualOffloadLayers")
            if (actual.backend == null) add("actualRuntime")
            if (actual.engineSpec == null) add("actualAccelerator")
        }
    )
}
