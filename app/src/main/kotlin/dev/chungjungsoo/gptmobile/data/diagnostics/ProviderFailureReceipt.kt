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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

@Serializable
enum class ProviderFailureStage { ADMISSION, ENGINEERING_GUARD, GENERATION, CONTINUATION, REVIEW_COMPATIBILITY, ANSWER_REVIEW, CLAIM_VERIFICATION, DELIVERY }

@Serializable
enum class ProviderResponseFormat { TEXT, GITHUB_REVIEW_V1, CLAIM_JSON }

@Serializable
enum class ProviderFinishCategory { STOP, TOOL_CALLS, LENGTH, CONTENT_FILTER, OTHER, UNKNOWN }

@Serializable
enum class FailureDomain { PROVIDER, APPROVAL, APPLICATION_VERIFICATION, TOOL_EXECUTION, UNKNOWN }

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
    val reasoningTokens: Int? = null,
    val dispatched: Boolean? = null,
    val remoteAcceptance: dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance = dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance.UNKNOWN,
    val generationCompleted: Boolean? = null,
    val requestReplayAllowed: Boolean? = null,
    val logicalContinuationAllowed: Boolean? = null,
    val serverRequestId: String? = null,
    val generationId: String? = null,
    val causeTypes: List<String> = emptyList(),
    val failureDomain: FailureDomain = FailureDomain.UNKNOWN,
    val invocationId: String? = null,
    val usageInvocationId: String? = null,
    val outputTokenSource: String? = null,
    val causeStage: String? = null
) {
    internal fun valid(): Boolean = provider in setOf("OPENAI", "ANTHROPIC", "GOOGLE", "GROQ", "OPENROUTER", "OLLAMA", "CUSTOM", "LITERT_LM", "LLAMA", "FREE", "NVIDIA") &&
        listOf(profileHash, modelHash).all { it.matches(Regex("[a-f0-9]{64}")) } &&
        errorCode in ERROR_CODES &&
        (httpStatus == null || httpStatus in 100..599) &&
        (outputTokens == null || outputTokens >= 0) &&
        (reasoningTokens == null || reasoningTokens >= 0) &&
        listOfNotNull(invocationId, usageInvocationId).all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) } &&
        (outputTokenSource == null || safeUsageSource(outputTokenSource) != null) &&
        listOfNotNull(serverRequestId, generationId).all { dev.chungjungsoo.gptmobile.data.network.safeProviderRequestIdentity(it) != null } &&
        causeTypes.size <= 16 &&
        causeTypes.all { it.length <= 160 && it.matches(Regex("[A-Za-z0-9_.$]+")) }

    companion object {
        private fun safeUsageSource(value: String?): String? = value?.takeIf {
            it in setOf("provider_reported", "runtime_reported", "gateway_reported", "mixed_reported", "gateway_reported_partial", "estimated", "unavailable") || it.matches(Regex("(?:provider|runtime|gateway|mixed)_reported_(?:initial|interim)"))
        }

        // Provider error strings are untrusted content, even when they look like an enum.
        private val ERROR_CODES = setOf(
            "UNCLASSIFIED_PROVIDER_FAILURE", "HTTP_ERROR", "TRANSPORT_ERROR", "OUTPUT_LIMIT", "CONTEXT_CAPACITY_EXCEEDED",
            "LOCAL_SERVICE_UNREACHABLE", "GATEWAY_OUTCOME_UNKNOWN", "TIMEOUT_OUTCOME_UNKNOWN", "RUN_DEADLINE_REACHED",
            "DISPATCH_POLICY_CHANGED", "SAVED_EVIDENCE_REVOKED", "REPLAY_EVIDENCE_REQUIRED", "NOT_DISPATCHED",
            "GITHUB_REVIEW_INCOMPATIBLE", "GITHUB_REVIEW_INVALID_VERDICT", "GITHUB_REVIEW_INDETERMINATE",
            "GITHUB_REVIEW_REQUIRED", "GITHUB_REVIEW_BLOCKED", "GITHUB_REVIEW_UNAVAILABLE", "GITHUB_REVIEW_CAPACITY",
            "GITHUB_REVIEW_BUDGET_EXHAUSTED",
            "GITHUB_PROFILE_LOCKED", "GITHUB_EVIDENCE_REQUIRED", "GITHUB_EVIDENCE_RETENTION_INCOMPLETE",
            "GITHUB_EXTRACTION_TIMEOUT", "GITHUB_EXTRACTION_INVALID_RESPONSE", "GITHUB_EXTRACTION_UNAVAILABLE", "GITHUB_EXTRACTION_FAILED", "GITHUB_EXTRACTION_OUTPUT_LIMIT",
            "GITHUB_OPERATION_BLOCKED", "GITHUB_RUN_INTERRUPTED", "GITHUB_TASK_INCOMPLETE", "GITHUB_UNEXPECTED_RESPONSE",
            "OUTPUT_CONTINUATION_BUDGET_EXHAUSTED", "OUTPUT_CONTINUATION_DISABLED", "OUTPUT_CONTINUATION_NO_PROGRESS",
            "OUTPUT_CONTINUATION_PENDING_TOOLS", "OUTPUT_CONTINUATION_UNAVAILABLE", "OUTPUT_PROTOCOL_INVALID",
            "INVALID_ARGUMENTS", "TOOL_OUTCOME_UNKNOWN", "TOOL_PROTOCOL_INVALID_FRAME", "TOOL_UNAVAILABLE", "FINAL_RESPONSE_REQUIRED",
            "WAITING_FOR_APPROVAL", "PROVIDER_INVOCATION_FAILED", "INCOMPLETE_PROVIDER_STREAM"
        ) + dev.chungjungsoo.gptmobile.data.context.RequestCapacityException.CODES +
            dev.chungjungsoo.gptmobile.data.network.error.ProviderContractFailure.codes +
            dev.chungjungsoo.gptmobile.data.network.NetworkFailureKind.entries.map { it.code } +
            dev.chungjungsoo.gptmobile.data.github.GatewayAdmissionReason.entries.map { "GATEWAY_${it.name}" }

        fun from(profile: PlatformV2, role: String, failure: ProviderEvent.Failed, usage: ProviderEvent.Usage? = null): ProviderFailureReceipt {
            val status = ((failure.failureDetails?.get("http_status") as? JsonPrimitive)?.intOrNull ?: failure.errorCode?.toIntOrNull())?.takeIf { it in 100..599 }
            val code = failure.errorCode?.uppercase(java.util.Locale.ROOT)?.takeIf { it in ERROR_CODES } ?: if (status != null) "HTTP_ERROR" else "UNCLASSIFIED_PROVIDER_FAILURE"
            val failedStage = (failure.failureDetails?.get("failed_stage") as? JsonPrimitive)?.content
            val reviewStage = (failure.failureDetails?.get("review_stage") as? JsonPrimitive)?.content
            val classified = dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.fromProvider(failure.message, failure.errorCode, failure.failureDetails)
            val invocationId = failure.invocationId ?: classified.invocationId
            val correlatedUsage = usage?.takeIf {
                if (invocationId != null || it.invocationId != null) invocationId != null && invocationId == it.invocationId else role != "github-delivery"
            } ?: dev.chungjungsoo.gptmobile.data.accounting.GatewayRecoveryUsage.from(failure.failureDetails)?.event(invocationId)
            val stage = when {
                code == "ENGINEERING_STATE_OR_INPUT_CHANGED" && failedStage in setOf("request_validation", "provider_admission", "binding_admission", "message_admission", "conversation_admission", "execution_admission") -> ProviderFailureStage.ADMISSION
                code == "ENGINEERING_STATE_OR_INPUT_CHANGED" -> ProviderFailureStage.ENGINEERING_GUARD
                classified.dispatched == false && failedStage in setOf("provider_admission", "connection_preflight", "backend_admission", "request_validation", "message_admission") -> ProviderFailureStage.ADMISSION
                failedStage in setOf("tool_continuation", "tool_reconciliation") || code == "OUTPUT_CONTINUATION_PENDING_TOOLS" -> ProviderFailureStage.CONTINUATION
                role.endsWith("-preflight") || reviewStage == "review_compatibility" || failedStage == "review_compatibility" || code == "GITHUB_REVIEW_INCOMPATIBLE" -> ProviderFailureStage.REVIEW_COMPATIBILITY
                role.startsWith("github-review") || reviewStage == "answer_review" || failedStage == "answer_review" || code.startsWith("GITHUB_REVIEW_") -> ProviderFailureStage.ANSWER_REVIEW
                failedStage == "gateway_recovery" -> ProviderFailureStage.DELIVERY
                role == "github-claim-extraction" || code.startsWith("GITHUB_EVIDENCE_") || code.startsWith("GITHUB_EXTRACTION_") -> ProviderFailureStage.CLAIM_VERIFICATION
                "continuation" in role -> ProviderFailureStage.CONTINUATION
                role == "github-delivery" -> ProviderFailureStage.DELIVERY
                else -> ProviderFailureStage.GENERATION
            }
            val finish = when (correlatedUsage?.finishReason?.lowercase()) {
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
                finish == ProviderFinishCategory.LENGTH || code in setOf("OUTPUT_LIMIT", "OUTPUT_CONTINUATION_BUDGET_EXHAUSTED", "COMPLETION_REPAIR_BUDGET_EXHAUSTED") || dev.chungjungsoo.gptmobile.data.agent.tool.isProviderOutputLimitFailure(failure.message),
                failure.transportComplete, correlatedUsage?.outputTokens?.takeIf { it >= 0 }, correlatedUsage?.reasoningTokens?.takeIf { it >= 0 },
                dispatched = classified.dispatched,
                remoteAcceptance = classified.remoteAcceptance,
                generationCompleted = classified.generationCompleted,
                requestReplayAllowed = classified.requestReplayAllowed,
                logicalContinuationAllowed = if (classified.category in setOf("GATEWAY_OUTCOME_UNKNOWN", "TOOL_OUTCOME_UNKNOWN") || failure.failureDetails?.get("requires_reconciliation") == JsonPrimitive(true)) {
                    false
                } else {
                    (failure.failureDetails?.get("logical_continuation_allowed") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                },
                serverRequestId = classified.serverRequestId,
                generationId = dev.chungjungsoo.gptmobile.data.network.safeProviderRequestIdentity((failure.failureDetails?.get("generation_id") as? JsonPrimitive)?.contentOrNull),
                causeTypes = classified.causeTypes,
                failureDomain = when {
                    code == "WAITING_FOR_APPROVAL" || failedStage == "approval" -> FailureDomain.APPROVAL
                    code == "GITHUB_TASK_INCOMPLETE" || failedStage == "acceptance_verification" -> FailureDomain.APPLICATION_VERIFICATION
                    code in setOf("TOOL_OUTCOME_UNKNOWN", "INVALID_ARGUMENTS", "TOOL_PROTOCOL_INVALID_FRAME", "TOOL_UNAVAILABLE", "GITHUB_OPERATION_BLOCKED") -> FailureDomain.TOOL_EXECUTION
                    invocationId != null || status != null || code in dev.chungjungsoo.gptmobile.data.context.RequestCapacityException.CODES || code in setOf("TRANSPORT_ERROR", "OUTPUT_LIMIT", "INCOMPLETE_PROVIDER_STREAM") -> FailureDomain.PROVIDER
                    else -> FailureDomain.UNKNOWN
                },
                invocationId = invocationId,
                usageInvocationId = correlatedUsage?.invocationId,
                outputTokenSource = correlatedUsage?.let { safeUsageSource(it.outputSource) ?: "unavailable" },
                causeStage = classified.phase.takeUnless { it.isBlank() || it == "stage_not_recorded" }
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
                    val prior = usage?.takeIf { it.invocationId == event.invocationId }
                    usage = event.copy(
                        outputTokens = event.outputTokens ?: prior?.outputTokens,
                        reasoningTokens = event.reasoningTokens ?: prior?.reasoningTokens,
                        finishReason = event.finishReason ?: prior?.finishReason
                    )
                }
                if (event is ProviderEvent.Failed) {
                    RunDiagnostics.journal?.record(runId, RunDiagnosticKind.FAILURE_RECEIPT, invocationId = event.invocationId, successful = false, providerFailure = ProviderFailureReceipt.from(profile, role, event, usage))
                }
                emit(event)
            }
        }
    }
