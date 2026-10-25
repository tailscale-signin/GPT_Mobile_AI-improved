package dev.chungjungsoo.gptmobile.data.localruntime

import kotlinx.serialization.Serializable

/** Estimates are separate from native high-water observations and OS PSS. */
@Serializable
data class MemoryPlanReceipt(
    val planId: String,
    val artifactDigest: String,
    val runtimeRevision: String,
    val selectedBackend: String,
    val contextPolicy: String,
    val contextTokens: Int,
    val requestedContextTokens: Int,
    val kvBytesPerToken: Long?,
    val kvType: String?,
    val geometryEvidence: String,
    val requestedOffloadLayers: Int,
    val actualOffloadLayers: Int? = null,
    val residentEngineReuse: Boolean,
    val residentEngineInstanceId: String? = null,
    val mappedModelFileBytes: Long,
    val committedResidentPagesBytes: Long? = null,
    val repackedWeightsBytes: Long? = null,
    val components: Map<String, Long>,
    val calibrationSchemaVersion: Int? = null,
    val uncertaintyBasis: String,
    val availableBytes: Long?,
    val reserveBytes: Long,
    val observedAtMillis: Long,
    val observedAtElapsedMs: Long,
    val freshnessPolicy: String = "fresh_before_allocation;auto_descending_context;32MiB_hysteresis",
    val schemaVersion: Int = 1
)

/** Only initial allocation admission can request a bounded complete prompt replan. */
class LocalContextReplanRequired(
    val attemptedContextTokens: Int,
    val suggestedContextTokens: Int,
    val receipt: LocalAdmissionReceipt? = null
) : IllegalStateException("AUTO_CONTEXT_REPLAN_REQUIRED: $attemptedContextTokens -> $suggestedContextTokens before native allocation") {
    init {
        require(suggestedContextTokens in 1 until attemptedContextTokens)
    }
}

internal const val AUTO_CONTEXT_HYSTERESIS_BYTES = 32L * 1024 * 1024

internal fun Map<String, Long>.withContextHysteresis(policy: String): Map<String, Long> =
    if (policy == "AUTO") this + ("admissionHysteresis" to AUTO_CONTEXT_HYSTERESIS_BYTES) else this
