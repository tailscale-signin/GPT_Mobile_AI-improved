package dev.chungjungsoo.gptmobile.data.orchestration

import dev.chungjungsoo.gptmobile.data.assist.AssistPreparation
import dev.chungjungsoo.gptmobile.data.assist.LocalAssistPolicy
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import kotlinx.serialization.Serializable

@Serializable
enum class OptimizationMode(val label: String) { OFF("Off"), BALANCED("Balanced"), MAXIMUM_SAVINGS("Maximum savings"), FAST_RESPONSE("Fast response"), CUSTOM("Custom") }

/** Additive settings; no mode grants tool, memory, connection or cloud permission. */
@Serializable
data class ExecutionPolicy(
    val schemaVersion: Int = 1,
    val mode: OptimizationMode = OptimizationMode.OFF,
    val unifiedRunAccounting: Boolean = false,
    val sharedRunBudget: Boolean = false,
    val warmLocalAssist: Boolean = true,
    val evidenceHandoff: Boolean = true,
    val assistCompression: Boolean = false,
    val coldLoadAssist: Boolean = false,
    val remoteInputTokens: Long? = null,
    val remoteOutputTokens: Long? = null,
    val localGeneratedTokens: Long? = null,
    val physicalModelRequests: Int? = null,
    val concurrentModels: Int = 4,
    val modelActiveMillis: Long? = null,
    val finalOutputReserve: Int = 4096
) {
    fun validate(): ExecutionPolicy = apply {
        require(schemaVersion == 1)
        require(listOf(remoteInputTokens, remoteOutputTokens, localGeneratedTokens, modelActiveMillis).all { it == null || it > 0 })
        require(physicalModelRequests == null || physicalModelRequests in 1..1024)
        require(concurrentModels in 1..8 && finalOutputReserve in 1..131072)
        require(remoteOutputTokens == null || remoteOutputTokens >= finalOutputReserve)
    }
    fun restrictWith(other: ExecutionPolicy): ExecutionPolicy {
        if (!sharedRunBudget) return other
        if (!other.sharedRunBudget) return this
        fun min(a: Long?, b: Long?) = if (a == null) {
            b
        } else if (b == null) {
            a
        } else {
            minOf(a, b)
        }
        return other.copy(
            sharedRunBudget = true,
            remoteInputTokens = min(remoteInputTokens, other.remoteInputTokens),
            remoteOutputTokens = min(remoteOutputTokens, other.remoteOutputTokens),
            localGeneratedTokens = min(localGeneratedTokens, other.localGeneratedTokens),
            physicalModelRequests = min(physicalModelRequests?.toLong(), other.physicalModelRequests?.toLong())?.toInt(),
            concurrentModels = minOf(concurrentModels, other.concurrentModels),
            modelActiveMillis = min(modelActiveMillis, other.modelActiveMillis),
            finalOutputReserve = minOf(maxOf(finalOutputReserve, other.finalOutputReserve).toLong(), min(remoteOutputTokens, other.remoteOutputTokens) ?: Long.MAX_VALUE).toInt()
        ).validate()
    }
}

fun AppFeatureSettings.effectiveAssistPolicy(): LocalAssistPolicy {
    val policy = executionPolicy
    if (policy.mode == OptimizationMode.OFF) return localAssistPolicy.validated()
    return localAssistPolicy.copy(
        evidenceSelection = localAssistPolicy.evidenceSelection && policy.evidenceHandoff,
        summarizeRetrievedText = localAssistPolicy.summarizeRetrievedText && policy.assistCompression,
        preparation = if (policy.coldLoadAssist && policy.mode in setOf(OptimizationMode.MAXIMUM_SAVINGS, OptimizationMode.CUSTOM)) localAssistPolicy.preparation else AssistPreparation.WARM_ONLY,
        maxAttempts = if (policy.mode == OptimizationMode.FAST_RESPONSE) 1 else localAssistPolicy.maxAttempts,
        taskTimeoutSeconds = if (policy.mode == OptimizationMode.FAST_RESPONSE) minOf(3, localAssistPolicy.taskTimeoutSeconds) else localAssistPolicy.taskTimeoutSeconds
    ).validated()
}

fun AppFeatureSettings.assistEnabled(): Boolean = localAssist && (executionPolicy.mode == OptimizationMode.OFF || executionPolicy.warmLocalAssist)
