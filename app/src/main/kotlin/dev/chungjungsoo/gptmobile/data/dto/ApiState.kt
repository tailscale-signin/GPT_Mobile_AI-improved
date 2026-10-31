package dev.chungjungsoo.gptmobile.data.dto

import dev.chungjungsoo.gptmobile.data.agent.ToolPayloadMetrics
import dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayProgress
import dev.chungjungsoo.gptmobile.data.localruntime.LocalInferencePhase
import dev.chungjungsoo.gptmobile.data.rag.RecalledFactRef

sealed class ApiState {
    data class ResearchReplacement(val previousDigest: String, val text: String, val acknowledgement: kotlinx.coroutines.CompletableDeferred<Boolean> = kotlinx.coroutines.CompletableDeferred()) : ApiState()

    /** Internal ownership handoff; the editor waits for durable compare-and-swap acknowledgement. */
    data class CombinedReplacement(
        val candidate: dev.chungjungsoo.gptmobile.data.agent.CombinedReplacementCandidate,
        val acknowledgement: kotlinx.coroutines.CompletableDeferred<Boolean> = kotlinx.coroutines.CompletableDeferred()
    ) : ApiState()
    data class CombinedQuality(val report: dev.chungjungsoo.gptmobile.data.agent.CombinedQualityReport) : ApiState()
    data class CombinedAnswer(val text: String, val report: dev.chungjungsoo.gptmobile.data.agent.CombinedQualityReport, val formatVersion: Int = 1) : ApiState()
    data class AssistStatus(val status: dev.chungjungsoo.gptmobile.data.assist.AssistTaskState) : ApiState()
    data class TokenUsage(val inputTokens: Int?, val outputTokens: Int?, val totalTokens: Int?, val finishReason: String? = null, val outputAllowance: Int? = null) : ApiState()
    data object Loading : ApiState()
    data object GitHubRoundStarted : ApiState()
    data class ProgressCheckpoint(val text: String, val modelAuthored: Boolean = false) : ApiState()
    data class ActivitySummary(val text: String, val modelAuthored: Boolean = false) : ApiState()
    data class DelegationText(val invocationId: String, val profileName: String, val text: String, val remote: Boolean) : ApiState()
    data class Thinking(val thinkingChunk: String) : ApiState()
    data class Success(val textChunk: String) : ApiState()
    data class ToolCall(val toolSequence: Int, val metrics: ToolPayloadMetrics? = null, val delegated: Boolean = false, val personalData: List<dev.chungjungsoo.gptmobile.data.permissions.PersonalDataProvenance> = emptyList()) : ApiState()
    data class MemoryRecalled(val facts: List<RecalledFactRef>) : ApiState()
    data class Notice(val message: String, val persistent: Boolean = false, val researchDelivery: dev.chungjungsoo.gptmobile.data.research.delivery.ResearchDeliveryReceipt? = null, val personalData: List<dev.chungjungsoo.gptmobile.data.permissions.PersonalDataProvenance> = emptyList()) : ApiState()
    data class PhaseChanged(val phase: LocalInferencePhase, val nativeProgress: dev.chungjungsoo.gptmobile.data.localruntime.GgufNativeProgress? = null) : ApiState()
    data class GatewayProgressChanged(val progress: GatewayProgress) : ApiState()
    data class Error(val message: String, val progressDeadline: dev.chungjungsoo.gptmobile.data.agent.ProgressDeadlineOutcome? = null, val recoveryBlock: dev.chungjungsoo.gptmobile.data.agent.RecoveryBlock? = null, val combinedFailure: dev.chungjungsoo.gptmobile.data.agent.CombinedRecoveryFailure? = null, val providerLimit: dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision? = null, val errorCode: String? = null) : ApiState()
    data object Done : ApiState()
}
