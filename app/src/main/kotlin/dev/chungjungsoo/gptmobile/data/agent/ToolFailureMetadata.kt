package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.network.NetworkFailureKind
import dev.chungjungsoo.gptmobile.data.network.error.ErrorClassification
import dev.chungjungsoo.gptmobile.data.network.networkFailureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Fixed diagnostic metadata travels with the original receipt, including compacted/replayed results. */
internal object ToolFailureMetadata {
    private val unavailableCode = setOf("", "none", "null", "unknown")
    val authorityInvalidationCodes = setOf("DISPATCH_POLICY_CHANGED", "POLICY_STALE")

    private fun causes(error: Throwable) = generateSequence(error) { current -> current.cause?.takeUnless { it === current } }.take(16)

    fun authorityCode(error: Throwable): String? = causes(error).firstNotNullOfOrNull { cause ->
        when {
            cause is dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException -> "DISPATCH_POLICY_CHANGED"
            cause is dev.chungjungsoo.gptmobile.data.permissions.ToolAuthorizationRejected && cause.code in authorityInvalidationCodes -> cause.code
            cause is IllegalStateException && cause.message?.startsWith("DISPATCH_POLICY_CHANGED:") == true -> "DISPATCH_POLICY_CHANGED"
            else -> null
        }
    }

    /** Only structured local control metadata can stop the authority; payload prose cannot. */
    fun authorityCode(result: AgentToolResult): String? =
        (result.executionReceipt?.get("authority_invalidation_code") as? JsonPrimitive)?.content?.takeIf { it in authorityInvalidationCodes }
            ?: result.errorCode?.takeIf { result.isError && it in authorityInvalidationCodes }
            ?: (result.executionReceipt?.get("cause_code") as? JsonPrimitive)?.content?.takeIf { result.isError && it in authorityInvalidationCodes }

    /** Preserve a stronger observed receipt, including success or uncertainty after invocation. */
    fun withAuthorityFailure(result: AgentToolResult, error: Throwable, stage: String): AgentToolResult? {
        val code = authorityCode(error) ?: return null
        val policy = causes(error).filterIsInstance<dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException>().firstOrNull()
        return result.copy(
            executionReceipt = JsonObject(
                result.executionReceipt.orEmpty() + buildJsonObject {
                    put("authority_invalidation_code", code)
                    put("authority_failed_stage", stage)
                    put("resume_required", true)
                    policy?.let { put("changed_policy_fields", JsonArray(it.fields.map(::JsonPrimitive))) }
                }
            )
        )
    }

    /** Call only before invoking the tool; exception type alone does not prove non-dispatch. */
    fun authorityPrecheck(callId: String, tool: String, error: Throwable): AgentToolResult? {
        val code = authorityCode(error) ?: return null
        val result = requireNotNull(withAuthorityFailure(ToolResultEnvelope.error(callId, code, error.message.orEmpty()), error, "tool_precheck"))
        return receipt(result, tool, diagnosticCode = code, replaySafe = false)
    }

    fun authorityBlocked(callId: String, tool: String, prior: AgentToolResult): AgentToolResult {
        val code = requireNotNull(authorityCode(prior))
        val result = ToolResultEnvelope.error(callId, code, "The selected execution authority or tool configuration changed. This queued call was not dispatched. Resume under the current settings; completed actions and uncertain outcomes remain recorded.")
        return receipt(
            result.copy(
                executionReceipt = buildJsonObject {
                    put("authority_invalidation_code", code)
                    put("authority_failed_stage", "tool_precheck")
                    put("resume_required", true)
                    prior.executionReceipt?.get("changed_policy_fields")?.let { put("changed_policy_fields", it) }
                }
            ),
            tool,
            replaySafe = false
        )
    }

    fun authorityFailure(result: AgentToolResult, hasUnknownOutcome: Boolean): ProviderEvent.Failed {
        val code = requireNotNull(authorityCode(result))
        val unknown = hasUnknownOutcome || result.errorCode in ToolResultEnvelope.unknownOutcomeCodes
        val stage = (result.executionReceipt?.get("authority_failed_stage") as? JsonPrimitive)?.content
            ?: if (result.dispatched) "tool_execution" else "tool_precheck"
        return ProviderEvent.Failed(
            if (unknown) {
                "Execution authority changed while an earlier operation has an unknown outcome. Reconcile the retained receipts before any mutation; no model or tool continuation was started."
            } else {
                "The selected profile, connection or tool configuration changed. Resume to revalidate the current settings and retained receipts. No further model or tool continuation was started."
            },
            errorCode = if (unknown) "RECONCILIATION_REQUIRED" else code,
            taskOutcome = if (unknown) "PARTIAL" else "CONFIGURATION_INVALIDATED",
            failureDetails = buildJsonObject {
                put("causal_id", result.callId)
                put("causal_provenance", "TOOL_CALL")
                put("causal_failure_code", result.errorCode ?: code)
                put("failed_stage", if (unknown) "tool_dispatch" else stage)
                if (!unknown) put("dispatched", result.dispatched)
                put("authority_invalidation_code", code)
                put("blocked_stage", stage)
                put("blocked_stage_dispatched", result.dispatched)
                put("resume_required", true)
                put("requires_reconciliation", unknown)
                result.executionReceipt?.get("changed_policy_fields")?.let { put("changed_policy_fields", it) }
            }
        )
    }

    fun normalize(result: AgentToolResult): AgentToolResult {
        if (!result.isError) return result
        val code = result.errorCode?.trim()?.takeUnless { it.lowercase(java.util.Locale.ROOT) in unavailableCode }
            ?: if (result.outputBudgetExhausted || result.toolCallBudgetExhausted) "BUDGET_EXHAUSTED" else classify(Exception(text(result.content)))
        return result.copy(errorCode = code)
    }

    fun classify(error: Throwable): String {
        causes(error).filterIsInstance<RequiredToolReadinessFailure>().firstOrNull()?.let { return it.errorCode }
        val classified = ErrorClassification.classify(error)
        if (classified.errorCode in ToolResultEnvelope.unknownOutcomeCodes) return classified.errorCode
        val network = networkFailureKind(error)
        if (network != NetworkFailureKind.UNKNOWN) return network.code
        val details = generateSequence(error) { it.cause }.take(16).joinToString(" ") { it.javaClass.simpleName + " " + it.message.orEmpty() }.lowercase(java.util.Locale.ROOT)
        return when {
            "unknownhostexception" in details || "unable to resolve host" in details || "name or service not known" in details || "dns resolution" in details -> "DNS_RESOLUTION_FAILED"
            "unexpected end of stream" in details || "eofexception" in details -> "TRANSPORT_EOF"
            "connection abort" in details || "connection reset" in details || "socketexception" in details -> "CONNECTION_ABORTED"
            "serializationexception" in details || "jsondecodingexception" in details || "malformedjsonexception" in details -> "PARSING_ERROR"
            classified.errorCode != "UNKNOWN" -> classified.errorCode
            else -> "TOOL_FAILURE"
        }
    }

    fun receipt(result: AgentToolResult, tool: String, diagnosticCode: String? = null, replaySafe: Boolean? = null): AgentToolResult {
        val normalized = normalize(result)
        if (!normalized.isError) return normalized
        val classified = if (replaySafe == null) normalized else normalized.copy(executionReceipt = JsonObject(normalized.executionReceipt.orEmpty() + ("replay_safe" to JsonPrimitive(replaySafe))))
        val fields = buildJsonObject {
            put("schema", "gptmobile.tool-execution.v1")
            put("call_id", normalized.callId)
            put("tool", tool)
            put("error_code", requireNotNull(normalized.errorCode))
            diagnosticCode?.let { put("cause_code", it) }
            put("execution_state", ToolResultEnvelope.executionState(normalized))
            put("dispatched", normalized.dispatched)
            put("phase", if (normalized.dispatched) "tool_execution" else "tool_precheck")
            put("retryable", retryable(classified))
            put("requires_reconciliation", normalized.errorCode in ToolResultEnvelope.unknownOutcomeCodes)
        }
        // Domain receipts carry stronger remote-state evidence and must keep every existing field.
        return normalized.copy(executionReceipt = JsonObject(fields + classified.executionReceipt.orEmpty()))
    }

    fun retryable(result: AgentToolResult): Boolean = result.isError &&
        result.errorCode !in ToolResultEnvelope.unknownOutcomeCodes &&
        (result.executionReceipt?.get("replay_safe") == JsonPrimitive(true) || result.errorCode in setOf("READ_DEADLINE", "READ_FAILED")) &&
        result.executionReceipt?.get("requires_reconciliation") != JsonPrimitive(true) &&
        result.errorCode in setOf("READ_DEADLINE", "READ_FAILED", "PROVIDER_UNAVAILABLE", "DNS_RESOLUTION_FAILED", "NETWORK_TIMEOUT", "NETWORK_DISCONNECTED", "CONNECTION_ABORTED", "TRANSPORT_EOF", "SERVICE_UNAVAILABLE", "DNS_FAILED", "DNS_RECOVERY", "CONNECTION_FAILED", "STREAM_INTERRUPTED", "TIMEOUT", "RATE_LIMITED", "UNAVAILABLE", "SEARCH_PROVIDERS_UNAVAILABLE", "GITHUB_CIRCUIT_OPEN", "GITHUB_TRANSIENT_NETWORK", "GITHUB_TRANSIENT_SERVER")

    private fun text(content: ToolResultContent): String = when (content) {
        is ToolResultContent.Text -> content.text.take(8192)
        is ToolResultContent.Json -> content.value.toString().take(8192)
        is ToolResultContent.ResourceLinks -> "Tool returned an error with resource references"
    }
}
