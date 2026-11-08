package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

@Serializable
enum class ProviderFailureStage { GENERATION, CONTINUATION, REVIEW_COMPATIBILITY, ANSWER_REVIEW, CLAIM_VERIFICATION, DELIVERY }

@Serializable
enum class ProviderResponseFormat { TEXT, GITHUB_REVIEW_V1, CLAIM_JSON }

@Serializable
enum class ProviderFinishCategory { STOP, TOOL_CALLS, LENGTH, CONTENT_FILTER, OTHER, UNKNOWN }

/** Fixed fields only: no prompt, endpoint, token, raw model/profile name or exception text. */
@Serializable
data class ProviderFailureReceipt(
    val stage: ProviderFailureStage,
    val provider: String,
    val profileHash: String,
    val modelHash: String,
    val responseFormat: ProviderResponseFormat,
    val errorCode: String,
    val finishReason: ProviderFinishCategory,
    val httpStatus: Int? = null,
    val outputLimitReached: Boolean = false,
    val transportComplete: Boolean = false,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null
) {
    internal fun valid(): Boolean = provider in setOf("OPENAI", "ANTHROPIC", "GOOGLE", "GROQ", "OPENROUTER", "OLLAMA", "CUSTOM", "LITERT_LM", "LLAMA", "FREE", "NVIDIA") &&
        listOf(profileHash, modelHash).all { it.matches(Regex("[a-f0-9]{64}")) } &&
        errorCode in ERROR_CODES &&
        (httpStatus == null || httpStatus in 100..599) &&
        (outputTokens == null || outputTokens >= 0) &&
        (reasoningTokens == null || reasoningTokens >= 0)

    companion object {
        // Provider error strings are untrusted content, even when they look like an enum.
        private val ERROR_CODES = setOf(
            "UNCLASSIFIED_PROVIDER_FAILURE", "HTTP_ERROR", "TRANSPORT_ERROR", "OUTPUT_LIMIT", "CONTEXT_CAPACITY_EXCEEDED",
            "LOCAL_SERVICE_UNREACHABLE", "GATEWAY_OUTCOME_UNKNOWN", "TIMEOUT_OUTCOME_UNKNOWN", "RUN_DEADLINE_REACHED",
            "DISPATCH_POLICY_CHANGED", "SAVED_EVIDENCE_REVOKED", "REPLAY_EVIDENCE_REQUIRED", "NOT_DISPATCHED",
            "GITHUB_REVIEW_INCOMPATIBLE", "GITHUB_REVIEW_INVALID_VERDICT", "GITHUB_REVIEW_INDETERMINATE",
            "GITHUB_REVIEW_REQUIRED", "GITHUB_REVIEW_BLOCKED", "GITHUB_REVIEW_UNAVAILABLE", "GITHUB_REVIEW_CAPACITY",
            "GITHUB_PROFILE_LOCKED", "GITHUB_EVIDENCE_REQUIRED", "GITHUB_EVIDENCE_RETENTION_INCOMPLETE",
            "GITHUB_EXTRACTION_TIMEOUT", "GITHUB_EXTRACTION_INVALID_RESPONSE", "GITHUB_EXTRACTION_UNAVAILABLE", "GITHUB_EXTRACTION_FAILED",
            "GITHUB_OPERATION_BLOCKED", "GITHUB_RUN_INTERRUPTED", "GITHUB_TASK_INCOMPLETE", "GITHUB_UNEXPECTED_RESPONSE",
            "OUTPUT_CONTINUATION_BUDGET_EXHAUSTED", "OUTPUT_CONTINUATION_DISABLED", "OUTPUT_CONTINUATION_NO_PROGRESS",
            "OUTPUT_CONTINUATION_PENDING_TOOLS", "OUTPUT_CONTINUATION_UNAVAILABLE", "OUTPUT_PROTOCOL_INVALID",
            "INVALID_ARGUMENTS", "TOOL_OUTCOME_UNKNOWN", "TOOL_PROTOCOL_INVALID_FRAME", "TOOL_UNAVAILABLE", "FINAL_RESPONSE_REQUIRED"
        ) + dev.chungjungsoo.gptmobile.data.network.error.ProviderContractFailure.codes

        fun from(profile: PlatformV2, role: String, failure: ProviderEvent.Failed, usage: ProviderEvent.Usage? = null): ProviderFailureReceipt {
            val status = ((failure.failureDetails?.get("http_status") as? JsonPrimitive)?.intOrNull ?: failure.errorCode?.toIntOrNull())?.takeIf { it in 100..599 }
            val code = failure.errorCode?.uppercase(java.util.Locale.ROOT)?.takeIf { it in ERROR_CODES } ?: if (status != null) "HTTP_ERROR" else "UNCLASSIFIED_PROVIDER_FAILURE"
            val failedStage = (failure.failureDetails?.get("failed_stage") as? JsonPrimitive)?.content
            val stage = when {
                failedStage == "tool_continuation" -> ProviderFailureStage.CONTINUATION
                failedStage == "gateway_recovery" -> ProviderFailureStage.DELIVERY
                role.endsWith("-preflight") || code == "GITHUB_REVIEW_INCOMPATIBLE" -> ProviderFailureStage.REVIEW_COMPATIBILITY
                role.startsWith("github-review") || code.startsWith("GITHUB_REVIEW_") -> ProviderFailureStage.ANSWER_REVIEW
                role == "github-claim-extraction" || code.startsWith("GITHUB_EVIDENCE_") || code.startsWith("GITHUB_EXTRACTION_") -> ProviderFailureStage.CLAIM_VERIFICATION
                "continuation" in role -> ProviderFailureStage.CONTINUATION
                role == "github-delivery" -> ProviderFailureStage.DELIVERY
                else -> ProviderFailureStage.GENERATION
            }
            val finish = when (usage?.finishReason?.lowercase()) {
                null -> ProviderFinishCategory.UNKNOWN
                "stop", "end_turn" -> ProviderFinishCategory.STOP
                "tool_calls", "tool_use" -> ProviderFinishCategory.TOOL_CALLS
                "length", "max_tokens", "output_limit" -> ProviderFinishCategory.LENGTH
                "content_filter" -> ProviderFinishCategory.CONTENT_FILTER
                else -> ProviderFinishCategory.OTHER
            }
            return ProviderFailureReceipt(
                stage, profile.compatibleType.name, researchHash(profile.uid), researchHash(profile.model),
                when {
                    stage in setOf(ProviderFailureStage.REVIEW_COMPATIBILITY, ProviderFailureStage.ANSWER_REVIEW) && !role.endsWith("repair") -> ProviderResponseFormat.GITHUB_REVIEW_V1
                    stage == ProviderFailureStage.CLAIM_VERIFICATION -> ProviderResponseFormat.CLAIM_JSON
                    else -> ProviderResponseFormat.TEXT
                },
                code, finish,
                status,
                finish == ProviderFinishCategory.LENGTH || code in setOf("OUTPUT_LIMIT", "OUTPUT_CONTINUATION_BUDGET_EXHAUSTED") || dev.chungjungsoo.gptmobile.data.agent.tool.isProviderOutputLimitFailure(failure.message),
                failure.transportComplete, usage?.outputTokens?.takeIf { it >= 0 }, usage?.reasoningTokens?.takeIf { it >= 0 }
            )
        }
    }
}

/** Preserve a minimal failure receipt independently of optional verbose request snapshots. */
internal fun withProviderFailureReceipts(delegate: AgentProviderSession, runId: String, profile: PlatformV2, role: String): AgentProviderSession =
    object : AgentProviderSession by delegate {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
            var usage: ProviderEvent.Usage? = null
            delegate.streamRound(tools, exchanges).collect { event ->
                if (event is ProviderEvent.Usage) {
                    usage = event.copy(
                        outputTokens = event.outputTokens ?: usage?.outputTokens,
                        reasoningTokens = event.reasoningTokens ?: usage?.reasoningTokens,
                        finishReason = event.finishReason ?: usage?.finishReason
                    )
                }
                if (event is ProviderEvent.Failed) {
                    RunDiagnostics.journal?.record(runId, RunDiagnosticKind.FAILURE_RECEIPT, successful = false, providerFailure = ProviderFailureReceipt.from(profile, role, event, usage))
                }
                emit(event)
            }
        }
    }
