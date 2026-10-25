package dev.chungjungsoo.gptmobile.data.localruntime

import dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend
import kotlinx.serialization.Serializable

/** Applied IPC supervision. Native execution and the caller's request deadline have separate receipts. */
@Serializable
data class RuntimeProcessDeadlinePolicy(
    val backend: String?,
    val absoluteMs: Long,
    val prefillMs: Long,
    val decodeStallMs: Long,
    val terminalDeliveryMs: Long = 5_000,
    val toolCallbackMs: Long = 120_000,
    val prefillProgressResetsIdle: Boolean,
    val excludeToolWaitFromIdle: Boolean = true,
    val nativeMonitorGraceMs: Long,
    val promptCharacterEstimate: Int? = null,
    val scope: String = "ipc_generation_command_wall_including_transport_and_tool_wait",
    val clock: String = "android_elapsed_realtime",
    val schemaVersion: Int = 1
) {
    companion object {
        const val LOAD_GRACE_MS = 30_000L
        val loadCommandMs: Long get() = GgufLoadDeadline.ABSOLUTE_LOAD_MS + LOAD_GRACE_MS

        fun forGeneration(backend: LocalRuntimeBackend?, promptCharacters: Int): RuntimeProcessDeadlinePolicy {
            val nativeGgufMonitor = backend in setOf(LocalRuntimeBackend.LLAMA_CPP, LocalRuntimeBackend.GENIEX_LLAMA_CPP)
            val grace = if (nativeGgufMonitor) 30_000L else 0L
            return RuntimeProcessDeadlinePolicy(
                backend = backend?.name,
                absoluteMs = 600_000 + grace,
                prefillMs = LocalPrefillDeadline.processWatchdogBudgetMs(promptCharacters, backend),
                decodeStallMs = 45_000 + grace,
                prefillProgressResetsIdle = nativeGgufMonitor,
                nativeMonitorGraceMs = grace,
                promptCharacterEstimate = promptCharacters
            )
        }
    }
}
