package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
enum class PreparationPhase { SOURCE_COLLECTION, MEMORY_RECALL, TOOL_DISCOVERY, READINESS, NORMALIZATION, CONTEXT_ASSEMBLY, SERIALIZATION }

/** Monotonic app preparation timing is separate from provider time and contains no source content. */
internal suspend fun <T> measurePreparation(
    runId: String,
    phase: PreparationPhase,
    clockNanos: () -> Long = System::nanoTime,
    record: (Long, Boolean) -> Unit = { elapsed, successful ->
        RunDiagnostics.journal?.record(runId, RunDiagnosticKind.PREPARATION_PHASE, phase = phase, elapsedMillis = elapsed, successful = successful)
    },
    block: suspend () -> T
): T = withContext(Dispatchers.Default) {
    val started = clockNanos()
    var completed = false
    try {
        block().also { completed = true }
    } finally {
        runCatching { record(((clockNanos() - started) / 1_000_000L).coerceAtLeast(0L), completed) }
    }
}
