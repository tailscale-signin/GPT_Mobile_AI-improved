package dev.chungjungsoo.gptmobile.data.localruntime

import kotlinx.serialization.Serializable

/** Immutable numeric decision, including rejected requests that never enter native code. */
@Serializable
data class LocalAdmissionReceipt(
    val schemaVersion: Int = 1,
    val stage: String,
    val accepted: Boolean,
    val requiredBytes: Long,
    val reserveBytes: Long,
    val availableBytes: Long?,
    val shortfallBytes: Long?,
    val contextTokens: Int,
    val method: String,
    val existingEngineResident: Boolean,
    val nativeLoadDispatched: Boolean = false,
    val observedAtMillis: Long = System.currentTimeMillis(),
    val components: Map<String, Long> = emptyMap(),
    val requestedContextTokens: Int = contextTokens,
    val planIdentity: String? = null,
    val systemMemory: LocalSystemMemorySnapshot? = null
)

@Serializable
data class LocalSystemMemorySnapshot(val availableBytes: Long, val totalBytes: Long, val lowMemoryThresholdBytes: Long, val lowMemory: Boolean, val observedAtMillis: Long = System.currentTimeMillis())
