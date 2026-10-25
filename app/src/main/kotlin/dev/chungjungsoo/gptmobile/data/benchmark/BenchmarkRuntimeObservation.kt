package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.localruntime.LocalEngineSpec
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntime
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntimeExecutionIdentity
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntimeState
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeExecutionReceipt
import dev.chungjungsoo.gptmobile.data.localruntime.engineConfiguration
import kotlinx.serialization.Serializable

@Serializable
data class BenchmarkResidentMatch(val regime: String, val exactMatch: Boolean?, val reasons: List<String> = emptyList())

internal data class BenchmarkRuntimeObservation(
    val state: LocalRuntimeState,
    val identity: LocalRuntimeExecutionIdentity,
    val loaded: LocalEngineSpec?,
    val admitted: LocalEngineSpec?,
    val nativeBusy: Boolean,
    val artifactDigest: String?
)

internal fun observeBenchmarkRuntime(runtime: LocalRuntime): BenchmarkRuntimeObservation {
    val identity = runtime.executionIdentity
    val load = runtime.latestEngineLoadReceipt?.takeIf { it.engineInstanceId == identity.engineInstanceId && it.completedAtElapsedMs != null && it.actualBackend != null }
    return BenchmarkRuntimeObservation(runtime.state.value, identity, runtime.loadedEngineSpec(), runtime.admittedEngineSpec(), runtime.isNativeOperationBusy, load?.artifactDigest)
}

/** A label is determined from the case's actual receipt, never an unrelated resident engine. */
internal fun benchmarkResidentMatch(before: BenchmarkRuntimeObservation, after: BenchmarkRuntimeObservation, execution: RuntimeExecutionReceipt?): BenchmarkResidentMatch {
    if (execution?.knownDispatched != true) return BenchmarkResidentMatch("not_dispatched_or_unknown", null, listOf("NO_MATCHING_NATIVE_ENTRY"))
    if (before.loaded == null) return BenchmarkResidentMatch("cold", false)
    if (after.loaded == null) return BenchmarkResidentMatch("unknown_after_execution", null, listOf("NO_OBSERVED_LOADED_CONFIGURATION"))
    if (before.loaded.modelPath != after.loaded.modelPath || before.artifactDigest != null && after.artifactDigest != null && before.artifactDigest != after.artifactDigest) return BenchmarkResidentMatch("model_switch", false)
    if (before.loaded.engineConfiguration() != after.loaded.engineConfiguration() || before.admitted?.engineConfiguration() != after.admitted?.engineConfiguration() || before.state.backend != after.state.backend) return BenchmarkResidentMatch("configuration_switch", false)
    val missing = buildList {
        if (before.artifactDigest == null || after.artifactDigest == null) add("ARTIFACT_DIGEST_UNKNOWN")
        if (before.identity.workerGenerationId == null || after.identity.workerGenerationId == null) add("WORKER_IDENTITY_UNKNOWN")
        if (before.identity.engineInstanceId == null || after.identity.engineInstanceId == null) add("ENGINE_IDENTITY_UNKNOWN")
        if (before.admitted == null || after.admitted == null) add("ADMITTED_CONFIGURATION_UNKNOWN")
        if (before.state.backend == null || after.state.backend == null) add("ACTUAL_BACKEND_UNKNOWN")
        if (execution.engineInstanceId == null || execution.engineInstanceId != after.identity.engineInstanceId) add("EXECUTION_ENGINE_JOIN_UNCONFIRMED")
        if (execution.workerGenerationId == null || execution.workerGenerationId != after.identity.workerGenerationId) add("EXECUTION_WORKER_JOIN_UNCONFIRMED")
    }
    if (missing.isNotEmpty()) return BenchmarkResidentMatch("resident_identity_unknown", null, missing)
    if (before.identity.workerGenerationId != after.identity.workerGenerationId || before.identity.engineInstanceId != after.identity.engineInstanceId) return BenchmarkResidentMatch("reloaded", false)
    if (before.nativeBusy || before.state.engineHealth != "HEALTHY" || after.state.engineHealth != "HEALTHY") return BenchmarkResidentMatch("resident_not_healthy", false)
    return BenchmarkResidentMatch("ready", true)
}

internal fun benchmarkObservedCache(sample: BenchmarkSample): String {
    val configs = sample.runtimeExecutions.mapNotNull { it.configuration }
    val reused = if (configs.isNotEmpty()) configs.map { it.reusedPromptTokens } else sample.localSegments.map { it.native?.reusedPromptTokens?.toLong() }
    return when {
        reused.isEmpty() || reused.any { it == null || it < 0 } -> "unknown"
        reused.all { it == 0L } -> "observed_uncached"
        reused.all { it!! > 0 } -> "observed_cached_prefix"
        else -> "mixed_observed_reuse"
    }
}

internal fun benchmarkExecutionReceipts(sample: BenchmarkSample?, latest: RuntimeExecutionReceipt?, physicalRequests: Set<String>): List<RuntimeExecutionReceipt> {
    val prior = sample?.runtimeExecutions.orEmpty()
    if (latest == null) return prior
    val matching = latest.physicalAttemptId != null &&
        latest.physicalAttemptId in physicalRequests ||
        sample?.phaseIntervals?.any { it.nativeOperationId == latest.nativeOperationId && it.runtimeRequestId != null && it.runtimeRequestId == latest.runtimeRequestId } == true ||
        prior.any { it.nativeOperationId == latest.nativeOperationId && it.workerGenerationId == latest.workerGenerationId && it.runtimeRequestId == latest.runtimeRequestId }
    if (!matching) return prior
    val index = prior.indexOfFirst { it.nativeOperationId == latest.nativeOperationId && it.workerGenerationId == latest.workerGenerationId && it.runtimeRequestId == latest.runtimeRequestId }
    if (index < 0) return prior + latest
    val old = prior[index]
    val updated = latest.retainingBenchmarkFacts(old)
    return prior.toMutableList().apply { this[index] = updated }
}

/** Later command bookkeeping cannot erase a native entry or terminal already observed. */
internal fun RuntimeExecutionReceipt.retainingBenchmarkFacts(previous: RuntimeExecutionReceipt?): RuntimeExecutionReceipt {
    if (previous == null) return this
    val newest = if ((observedAtElapsedMs ?: Long.MIN_VALUE) < (previous.observedAtElapsedMs ?: Long.MIN_VALUE)) previous else this
    return newest.copy(
        nativeEntered = if (previous.nativeEntered == true || nativeEntered == true) true else newest.nativeEntered,
        nativeEnteredAtElapsedMs = previous.nativeEnteredAtElapsedMs ?: nativeEnteredAtElapsedMs,
        nativeReturned = if (previous.nativeReturned == true || nativeReturned == true) true else newest.nativeReturned,
        nativeReturnedAtElapsedMs = previous.nativeReturnedAtElapsedMs ?: nativeReturnedAtElapsedMs,
        terminalKind = if (previous.hasNativeTerminal) previous.terminalKind else newest.terminalKind ?: terminalKind ?: previous.terminalKind,
        configuration = newest.configuration ?: configuration ?: previous.configuration,
        engineLoad = newest.engineLoad ?: engineLoad ?: previous.engineLoad
    )
}

/** Sum only measured native intervals. Admission and fixture-tool time are outside this scope. */
internal fun benchmarkNativeGenerationDuration(receipts: List<RuntimeExecutionReceipt>): Long? {
    if (receipts.isEmpty()) return null
    val durations = receipts.map { receipt ->
        val entered = receipt.nativeEnteredAtElapsedMs
        val returned = receipt.nativeReturnedAtElapsedMs
        if (!receipt.knownDispatched || !receipt.hasNativeTerminal || entered == null || returned == null || returned < entered) return null
        returned - entered
    }
    return durations.sum()
}

/** Runtime/backend cohorts use observed load receipts, never a profile's requested label. */
internal fun benchmarkTimingRuntimeContexts(run: BenchmarkRun): Set<String> = run.samples.filter { it.category == "speed" && !it.warmup && it.knownExecuted() }.flatMap { sample ->
    val entered = sample.runtimeExecutions.filter { it.knownDispatched }
    if (entered.isEmpty()) {
        listOf("unknown")
    } else {
        entered.map { receipt ->
            val load = receipt.engineLoad
            if (load == null || load.runtimeRevision.isBlank() || load.actualBackend.isNullOrBlank()) "unknown" else listOf(load.runtimeRevision, load.actualBackend, load.backendRegistry ?: "registry-unknown", load.driverIdentity ?: "driver-unknown").joinToString(":")
        }
    }
}.toSortedSet()

internal fun benchmarkTimingExecutionExclusion(run: BenchmarkRun): String? {
    if (!run.local || run.measurementVersion < 4) return null
    val contexts = benchmarkTimingRuntimeContexts(run)
    return when {
        contexts.isEmpty() || "unknown" in contexts -> "ACTUAL_EXECUTION_CONTEXT_UNKNOWN"
        contexts.size > 1 -> "MIXED_ACTUAL_EXECUTION_CONTEXTS"
        else -> null
    }
}
