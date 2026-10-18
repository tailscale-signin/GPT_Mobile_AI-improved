package dev.chungjungsoo.gptmobile.data.localruntime

import kotlinx.serialization.Serializable

@Serializable
enum class LocalExecutionPolicy { PROFILE, AUTO, STABILITY, SPEED, BATTERY }

@Serializable
enum class LocalExecutionMode { SINGLE, GPU_VISION_NPU_TEXT, BOUNDED_HELPER, GENIEX_CPU_NPU, TENSOR_GPU_NPU, SPLIT_STAGES, CROSS_DEVICE_SPECULATION }

@Serializable
enum class QualificationStatus { NOT_TESTED, MEASURED, QUALIFIED, HISTORICAL, QUARANTINED }

/** An artifact-specific placement. A backend name never converts a compiled package. */
@Serializable
data class LocalExecutionPlan(
    val id: String,
    val artifactDigest: String,
    val runtimeFamily: String,
    val languageBackend: String,
    val visionBackend: String? = null,
    val speculativeDecoding: Boolean? = null,
    val contextTokens: Int,
    val tokenizerTemplateIdentity: String,
    val mode: LocalExecutionMode = LocalExecutionMode.SINGLE,
    val capabilityRevision: Int = 1
)

/** No paths, prompts or raw firmware identifiers in portable performance evidence. */
@Serializable
data class RuntimeQualificationKey(
    val deviceIdentity: String,
    val firmwareDigest: String,
    val runtimeIdentity: String,
    val dispatchDigest: String,
    val artifactDigest: String,
    val backend: String,
    val visionBackend: String? = null,
    val contextTokens: Int,
    val samplerIdentity: String,
    val speculativeDecoding: Boolean? = null,
    val workload: String = "plain_text",
    val precisionIdentity: String = "artifact_defined",
    val suiteVersion: Int = 1,
    val cpuThreads: Int? = null,
    val cacheEnabled: Boolean = true
)

@Serializable
data class RuntimeTrial(
    val id: String,
    val key: RuntimeQualificationKey,
    val plan: LocalExecutionPlan,
    val timestamp: Long,
    val completed: Boolean,
    val finished: Boolean = true,
    val nativeMetrics: NativeInferenceMetrics? = null,
    val firstVisibleAnswerMs: Long? = null,
    val loadMs: Long? = null,
    val durationMs: Long,
    val failure: String? = null,
    val thermalState: String = "unknown",
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val availableMemoryBytes: Long? = null,
    val processPssBytes: Long? = null,
    val memoryMethod: String = "android_debug_pss",
    val cold: Boolean = false,
    val sustained: Boolean = false,
    val warmup: Boolean = false,
    val outputCharacters: Int = 0,
    val metricScope: String = "last_segment",
    val requestedOutputTokens: Int? = null,
    val appCommit: String = "",
    val historical: Boolean = false,
    val promptTokenBand: Int? = null,
    val conversationReused: Boolean = false
)

@Serializable
data class RuntimeQualificationResult(
    val key: RuntimeQualificationKey,
    val plan: LocalExecutionPlan,
    val status: QualificationStatus = QualificationStatus.NOT_TESTED,
    val sourceRunIds: List<String> = emptyList(),
    val completedRuns: Int = 0,
    val failedRuns: Int = 0,
    val medianDecodeTokensPerSecond: Double? = null,
    val p95FirstVisibleMs: Long? = null,
    val medianLoadMs: Long? = null,
    val sustainedMedianDecodeTokensPerSecond: Double? = null,
    val energyPerToken: Double? = null,
    val qualityEquivalent: Boolean = false,
    val actualBackendConfirmed: Boolean = false,
    val measuredAt: Long = 0,
    val reason: String? = null
)

@Serializable
data class LocalPlanDecision(
    val selected: LocalExecutionPlan,
    val qualification: QualificationStatus,
    val candidateIds: List<String> = emptyList(),
    val rejections: Map<String, String> = emptyMap(),
    val reason: String,
    val policyVersion: Int = 2,
    val profilePreserved: Boolean = true,
    val requestCompleted: Boolean? = null,
    val actualLanguageBackend: String? = null
)

@Serializable
data class RuntimePlanDecisionRecord(
    val id: String,
    val timestamp: Long,
    val key: RuntimeQualificationKey,
    val decision: LocalPlanDecision,
    val actualBackend: String?,
    val historical: Boolean = false
)

/** Requirements are checked before ranking. Unknown evidence never grants eligibility. */
class LocalExecutionPlanner {
    fun select(
        policy: LocalExecutionPolicy,
        requested: LocalExecutionPlan,
        key: RuntimeQualificationKey,
        evidence: List<RuntimeQualificationResult>,
        expectedOutputTokens: Int = 128,
        warmPlanId: String? = null
    ): LocalPlanDecision {
        val rejected = linkedMapOf<String, String>()
        val candidates = evidence.filter { result ->
            val k = result.key
            val reason = when {
                result.status != QualificationStatus.QUALIFIED -> result.status.name
                !result.actualBackendConfirmed || !result.qualityEquivalent -> "Backend or quality unverified"
                requested.speculativeDecoding != null && result.plan.speculativeDecoding != requested.speculativeDecoding -> "Explicit MTP policy preserved"
                result.plan.mode != LocalExecutionMode.SINGLE -> "Experiment requires separate qualification"
                k.copy(backend = key.backend, speculativeDecoding = key.speculativeDecoding) != key -> "Different device, runtime, artifact or workload"
                result.plan.contextTokens != requested.contextTokens || result.plan.tokenizerTemplateIdentity != requested.tokenizerTemplateIdentity -> "Incompatible conversation identity"
                result.medianDecodeTokensPerSecond?.let { it.isFinite() && it > 0 } != true -> "Native decode measurement missing"
                (policy == LocalExecutionPolicy.SPEED || expectedOutputTokens >= 512) && result.sustainedMedianDecodeTokensPerSecond?.let { it.isFinite() && it > 0 } != true -> "Sustained decode measurement missing"
                policy == LocalExecutionPolicy.BATTERY && result.energyPerToken?.let { it.isFinite() && it > 0 } != true -> "Energy measurement missing"
                else -> null
            }
            if (reason != null) rejected[result.plan.id] = reason
            reason == null
        }
        val winner = if (policy == LocalExecutionPolicy.PROFILE) {
            null
        } else {
            candidates.minByOrNull { candidate ->
                if (policy == LocalExecutionPolicy.BATTERY) {
                    candidate.energyPerToken!!
                } else {
                    val switchingMs = if (candidate.plan.id == warmPlanId) 0.0 else (candidate.medianLoadMs ?: 30_000).toDouble()
                    val rate = if (policy == LocalExecutionPolicy.SPEED || expectedOutputTokens >= 512) candidate.sustainedMedianDecodeTokensPerSecond!! else candidate.medianDecodeTokensPerSecond!!
                    val decodeMs = 1000.0 * expectedOutputTokens.coerceAtLeast(1) / rate
                    val failurePenalty = if (policy == LocalExecutionPolicy.STABILITY) 1e9 * candidate.failedRuns / (candidate.completedRuns + candidate.failedRuns).coerceAtLeast(1) else 0.0
                    failurePenalty + switchingMs + decodeMs + (candidate.p95FirstVisibleMs ?: 30_000)
                }
            }
        }
        return LocalPlanDecision(
            selected = winner?.plan ?: requested,
            qualification = winner?.status ?: evidence.filter { it.key == key && it.plan.id == requested.id }.maxByOrNull { it.measuredAt }?.status ?: QualificationStatus.NOT_TESTED,
            profilePreserved = winner == null || winner.plan.id == requested.id,
            candidateIds = candidates.map { it.plan.id },
            rejections = rejected,
            reason = if (winner == null) "Keeping profile configuration; no qualified replacement selected" else "Selected from comparable on-device evidence"
        )
    }
}

/** Advanced modes remain closed until a native adapter and a complete device gate report exist. */
object LocalExecutionExperiments {
    fun unavailableReason(mode: LocalExecutionMode): String? = when (mode) {
        LocalExecutionMode.SINGLE -> null
        LocalExecutionMode.GPU_VISION_NPU_TEXT -> "Requires matching modality metadata and SM8750 image tests"
        LocalExecutionMode.BOUNDED_HELPER -> "Requires independent handles, a resource lease and measured end-to-end gain"
        LocalExecutionMode.GENIEX_CPU_NPU -> "Requires a packaged Hexagon/hybrid native adapter in the GenieX preview"
        LocalExecutionMode.TENSOR_GPU_NPU -> "Requires native buffer import, graph partitioning and synchronization"
        LocalExecutionMode.SPLIT_STAGES -> "Requires compatible KV serialization and native state transfer"
        LocalExecutionMode.CROSS_DEVICE_SPECULATION -> "Requires token verification, rollback and rejection sampling"
    }
}
