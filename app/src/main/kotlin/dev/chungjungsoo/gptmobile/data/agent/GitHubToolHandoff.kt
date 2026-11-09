package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Receipt identities live outside the evictable provider replay. Payloads never grant reconciliation. */
internal data class ToolHandoffReceipt(
    val callId: String,
    val invocationFingerprint: String,
    val sourceResultSha256: String?,
    val projectionSha256: String,
    val dispatched: Boolean,
    val errorCode: String?,
    val requiresReconciliation: Boolean,
    val receiptSource: String = "OBSERVED_TOOL_RESULT"
) {
    fun settled(reconciled: Set<String>): Boolean = !requiresReconciliation || callId in reconciled

    fun json(): JsonObject = buildJsonObject {
        put("call_id", callId)
        put("invocation_fingerprint", invocationFingerprint)
        put("receipt_reference", "tool-batch/$callId")
        put("receipt_source", receiptSource)
        sourceResultSha256?.let { put("source_result_sha256", it) }
        put("projection_sha256", projectionSha256)
        put("hash_scope", "TOOL_RESULT_PAYLOAD_UTF8")
        put("source_hash_scope", "ORIGINAL_TOOL_RESULT_PAYLOAD_UTF8")
        put("projection_hash_scope", "REPLAY_PROJECTION_PAYLOAD_UTF8")
        put("source_hash_available", sourceResultSha256 != null)
        put("dispatched", dispatched)
        put("requires_reconciliation", requiresReconciliation)
        errorCode?.let { put("error_code", it) }
    }

    companion object {
        fun fingerprint(call: ProviderEvent.ToolCall): String = call.originalInvocationFingerprint
            ?: GitHubEvidenceBook.digest("${call.callId}:${call.name}:${call.arguments}")

        fun payload(content: ToolResultContent): String = when (content) {
            is ToolResultContent.Json -> content.value.toString()
            is ToolResultContent.Text -> content.text
            is ToolResultContent.ResourceLinks -> content.links.joinToString("\n") { it.uri }
        }

        /** Capture identity before ingress bounds can replace the original body with an excerpt. */
        fun identifySource(result: AgentToolResult): AgentToolResult {
            if (result.sharedResult) return result
            val digest = GitHubEvidenceBook.digest(payload(result.retainedContent ?: result.content))
            return result.copy(
                executionReceipt = JsonObject(
                    result.executionReceipt.orEmpty() + mapOf(
                        "source_result_sha256" to JsonPrimitive(digest),
                        "source_hash_scope" to JsonPrimitive("ORIGINAL_TOOL_RESULT_PAYLOAD_UTF8")
                    )
                )
            )
        }

        fun sourceHash(result: AgentToolResult, originalAvailable: Boolean = true): String? {
            val identity = result.executionReceipt
            if (identity?.get("source_hash_scope") == JsonPrimitive("ORIGINAL_TOOL_RESULT_PAYLOAD_UTF8")) {
                (identity["source_result_sha256"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let { return it }
            }
            if (!originalAvailable || result.sharedResult && result.retainedContent == null) return null
            return GitHubEvidenceBook.digest(payload(result.retainedContent ?: result.content))
        }

        fun from(call: ProviderEvent.ToolCall, result: AgentToolResult, originalAvailable: Boolean = true): ToolHandoffReceipt {
            require(call.callId == result.callId) { "Handoff receipt does not match its invocation." }
            return ToolHandoffReceipt(
                call.callId,
                fingerprint(call),
                sourceHash(result, originalAvailable),
                GitHubEvidenceBook.digest(payload(result.content)),
                result.dispatched,
                result.errorCode,
                result.errorCode in ToolResultEnvelope.unknownOutcomeCodes ||
                    result.executionReceipt?.get("requires_reconciliation") == JsonPrimitive(true) ||
                    GatewayOutcomeRecovery.requiresReconciliation("", result.errorCode),
                if (originalAvailable) "OBSERVED_TOOL_RESULT" else "REPLAY_PROJECTION"
            )
        }
    }
}

internal object GitHubToolHandoff {
    val states = setOf(JsonPrimitive("TOOL_HANDOFF"), JsonPrimitive("TOOL_RESULTS_READY"))

    fun pending(checkpoint: JsonObject): Set<String> =
        (checkpoint["pending_calls"] as? JsonArray).orEmpty().mapNotNullTo(linkedSetOf()) { (it as? JsonPrimitive)?.contentOrNull }

    /** Reentrant projection of durable terminal receipts; unknown effects remain pending. */
    fun reconcile(checkpoint: JsonObject, receipts: Collection<ToolHandoffReceipt>, reconciled: Set<String>): JsonObject {
        if (checkpoint["state"] !in states) return checkpoint
        val pending = pending(checkpoint)
        if (pending.isEmpty()) return checkpoint
        val matching = receipts.filter { it.invocationFingerprint in pending }.groupBy { it.invocationFingerprint }.values.map { versions ->
            // Restored display projections can be stale. The durable execution
            // record decides whether the operation is terminal; uncertainty wins
            // if there is no authoritative record to resolve conflicting views.
            versions.lastOrNull { it.receiptSource == "TOOL_BATCH_CHECKPOINT" }
                ?: versions.lastOrNull { it.requiresReconciliation }
                ?: versions.last()
        }
        if (matching.isEmpty()) return checkpoint
        val resolved = matching.filter { it.settled(reconciled) }
        val remaining = pending - resolved.mapTo(hashSetOf()) { it.invocationFingerprint }
        val completed = (checkpoint["completed_results"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .associateByTo(linkedMapOf()) { (it["call_id"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
        matching.forEach { receipt ->
            val prior = completed[receipt.callId]
            val merged = prior.orEmpty() + receipt.json()
            completed[receipt.callId] = JsonObject(merged + ("source_hash_available" to JsonPrimitive(merged["source_result_sha256"] != null)))
        }
        val ids = (checkpoint["reconciled_call_ids"] as? JsonArray).orEmpty().mapNotNullTo(linkedSetOf()) { (it as? JsonPrimitive)?.contentOrNull }
        ids += resolved.map { it.callId }
        return JsonObject(
            checkpoint + buildJsonObject {
                put("state", if (remaining.isEmpty()) "TOOL_RESULTS_READY" else "TOOL_HANDOFF")
                put("pending_calls", JsonArray(remaining.map(::JsonPrimitive)))
                put("completed_results", JsonArray(completed.values.toList()))
                put("reconciled_call_ids", JsonArray(ids.map(::JsonPrimitive)))
                put("reconciliation_source", "TERMINAL_RECEIPT_IDENTITIES")
                put("resume_allowed", remaining.isEmpty())
                put("resume_kind", "AFTER_TOOL_RESULTS")
                put("requires_reconciliation", matching.any { !it.settled(reconciled) })
                put("next_action", if (remaining.isEmpty()) "CONTINUE_SELECTED_MODEL" else "RECONCILE_TOOL_RECEIPTS")
            }
        )
    }
}
