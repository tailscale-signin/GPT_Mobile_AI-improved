package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.serialization.Serializable

@Serializable
data class BenchmarkNativeLoadObservation(
    val occurred: Boolean?,
    val durationMs: Long?,
    val clientLoadingPhaseMs: Long?,
    val engineInstanceId: String?,
    val receiptId: String?,
    val reason: String? = null
)

/** The app's LOADING phase is not proof of a new native engine allocation. */
internal fun benchmarkNativeLoading(sample: BenchmarkSample, before: BenchmarkRuntimeObservation?, startedAtElapsedMs: Long): BenchmarkNativeLoadObservation {
    val loads = sample.runtimeExecutions.filter { it.knownDispatched }.mapNotNull { execution -> execution.engineLoad?.takeIf { it.engineInstanceId == execution.engineInstanceId } }.distinctBy { it.receiptId }
    val client = sample.phaseElapsedMs.entries.filter { it.key.equals("loading", true) }.sumOf { it.value }.takeIf { sample.phaseElapsedMs.keys.any { phase -> phase.equals("loading", true) } }
    if (loads.size != 1) return BenchmarkNativeLoadObservation(null, null, client, null, null, "NATIVE_ENGINE_CREATION_RECEIPT_NOT_UNIQUE")
    val load = loads.single()
    val completed = load.completedAtElapsedMs
    val occurred = when {
        completed == null -> null
        load.startedAtElapsedMs >= startedAtElapsedMs && completed >= load.startedAtElapsedMs -> true
        completed < startedAtElapsedMs && before?.identity?.engineInstanceId == load.engineInstanceId && before?.loaded != null -> false
        else -> null
    }
    return BenchmarkNativeLoadObservation(occurred, if (occurred == true) requireNotNull(completed) - load.startedAtElapsedMs else null, client, load.engineInstanceId, load.receiptId, if (occurred == null) "LOAD_NOT_JOINED_TO_THIS_ATTEMPT" else null)
}
