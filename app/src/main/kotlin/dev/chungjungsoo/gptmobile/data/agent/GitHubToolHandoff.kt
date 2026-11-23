package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
    val receiptSource: String = "OBSERVED_TOOL_RESULT",
    val journalProjectionSha256: String? = null,
    val resultSemanticsSha256: String? = null,
    val resultSemanticsScope: String = REPLAY_SEMANTICS
) {
    fun settled(reconciled: Set<String>): Boolean = !requiresReconciliation || callId in reconciled

    fun json(): JsonObject = buildJsonObject {
        put("call_id", callId)
        put("invocation_fingerprint", invocationFingerprint)
        put("receipt_reference", "tool-batch/$callId")
        put("receipt_source", receiptSource)
        sourceResultSha256?.let { put("source_result_sha256", it) }
        val storedProjection = if (receiptSource == "TOOL_BATCH_CHECKPOINT") projectionSha256 else journalProjectionSha256
        storedProjection?.let {
            put("journal_projection_sha256", it)
            put("journal_projection_scope", "PERSISTED_JOURNAL_PROJECTION_UTF8")
        }
        put("projection_sha256", projectionSha256)
        put("hash_scope", "TOOL_RESULT_PAYLOAD_UTF8")
        put("source_hash_scope", "ORIGINAL_TOOL_RESULT_PAYLOAD_UTF8")
        put("projection_hash_scope", if (receiptSource == "TOOL_BATCH_CHECKPOINT") "PERSISTED_JOURNAL_PROJECTION_UTF8" else "REPLAY_PROJECTION_PAYLOAD_UTF8")
        put("source_hash_available", sourceResultSha256 != null)
        resultSemanticsSha256?.let {
            put("result_semantics_sha256", it)
            put("result_semantics_hash_scope", if (receiptSource == "TOOL_BATCH_CHECKPOINT") PERSISTED_SEMANTICS else resultSemanticsScope)
        }
        put("dispatched", dispatched)
        put("requires_reconciliation", requiresReconciliation)
        errorCode?.let { put("error_code", it) }
    }

    /** A later replay summary must not erase the independently retained stored-byte witness. */
    fun retaining(previous: JsonObject?): JsonObject {
        val sameResult = previous?.get("invocation_fingerprint") == JsonPrimitive(invocationFingerprint) &&
            previous["dispatched"] == JsonPrimitive(dispatched) &&
            previous["error_code"] == errorCode?.let(::JsonPrimitive) &&
            previous["requires_reconciliation"] == JsonPrimitive(requiresReconciliation)
        val prior = if (sameResult) previous.orEmpty() else previous.orEmpty() - setOf("source_result_sha256", "journal_projection_sha256", "journal_projection_scope", "result_semantics_sha256", "result_semantics_hash_scope")
        val merged = prior + json()
        val priorProjection = if (previous?.get("projection_hash_scope") == JsonPrimitive("PERSISTED_JOURNAL_PROJECTION_UTF8") &&
            previous["receipt_source"] == JsonPrimitive("TOOL_BATCH_CHECKPOINT")
        ) {
            previous["projection_sha256"]
        } else {
            previous?.get("journal_projection_sha256")
        }
        val retained = if (sameResult && receiptSource != "TOOL_BATCH_CHECKPOINT" && priorProjection != null) {
            merged + mapOf("journal_projection_sha256" to priorProjection, "journal_projection_scope" to JsonPrimitive("PERSISTED_JOURNAL_PROJECTION_UTF8"))
        } else {
            merged
        }
        val semantics = if (sameResult && previous["result_semantics_hash_scope"] == JsonPrimitive(PERSISTED_SEMANTICS)) {
            retained + mapOf(
                "result_semantics_sha256" to previous.getValue("result_semantics_sha256"),
                "result_semantics_hash_scope" to JsonPrimitive(PERSISTED_SEMANTICS)
            )
        } else {
            retained
        }
        return JsonObject(semantics + ("source_hash_available" to JsonPrimitive(semantics["source_result_sha256"] != null)))
    }

    companion object {
        const val PERSISTED_SEMANTICS = "PERSISTED_TOOL_RESULT_SEMANTICS_V1"
        const val REPLAY_SEMANTICS = "REPLAY_TOOL_RESULT_SEMANTICS_V1"

        /** Every stored field that can change restored control or provider-result semantics. */
        fun semanticsDigest(json: Boolean, error: Boolean, callLimit: Boolean, outputLimit: Boolean, execution: JsonObject?): String {
            fun canonical(value: JsonElement): JsonElement = when (value) {
                is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
                is JsonArray -> JsonArray(value.map(::canonical))
                else -> value
            }
            return GitHubEvidenceBook.digest(
                buildJsonObject {
                    put("schema", "github.stored-tool-result-semantics.v1")
                    put("json", json)
                    put("error", error)
                    put("call_limit", callLimit)
                    put("output_limit", outputLimit)
                    put("execution", canonical(execution ?: JsonNull))
                }.toString()
            )
        }

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
            val journalSemantics = result.executionReceipt?.takeIf { it["journal_semantics_scope"] == JsonPrimitive(PERSISTED_SEMANTICS) }
                ?.get("journal_semantics_sha256")?.let { it as? JsonPrimitive }?.takeIf { it.isString && it.content.matches(Regex("[a-f0-9]{64}")) }?.content
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
                if (originalAvailable) "OBSERVED_TOOL_RESULT" else "REPLAY_PROJECTION",
                result.executionReceipt?.takeIf { it["journal_projection_scope"] == JsonPrimitive("PERSISTED_JOURNAL_PROJECTION_UTF8") }
                    ?.get("journal_projection_sha256")?.let { it as? JsonPrimitive }?.takeIf { it.isString && it.content.matches(Regex("[a-f0-9]{64}")) }?.content,
                journalSemantics ?: semanticsDigest(result.content is ToolResultContent.Json, result.isError, result.toolCallBudgetExhausted, result.outputBudgetExhausted, result.executionReceipt),
                if (journalSemantics != null) PERSISTED_SEMANTICS else REPLAY_SEMANTICS
            )
        }
    }
}

internal object GitHubToolHandoff {
    val states = setOf(JsonPrimitive("TOOL_HANDOFF"), JsonPrimitive("TOOL_RESULTS_READY"))

    fun pending(checkpoint: JsonObject): Set<String> =
        (checkpoint["pending_calls"] as? JsonArray).orEmpty().mapNotNullTo(linkedSetOf()) { (it as? JsonPrimitive)?.contentOrNull }

    /** Durable or reconciled results absent from the provider's replay need a saved-context synthesis. */
    fun needsRecoveryContext(checkpoint: JsonObject, replay: List<ToolHandoffReceipt>, reconciled: Set<String>): Boolean {
        val byFingerprint = replay.associateBy { it.invocationFingerprint }
        val completed = (checkpoint["completed_results"] as? JsonArray).orEmpty()
        return completed.filterIsInstance<JsonObject>().any { saved ->
            val fingerprint = (saved["invocation_fingerprint"] as? JsonPrimitive)?.contentOrNull ?: return@any false
            val current = byFingerprint[fingerprint]
            current == null || current.requiresReconciliation && current.callId in reconciled
        }
    }

    fun bindingChanged(checkpoint: JsonObject, selectedBinding: String?): Boolean {
        val context = checkpoint["context"] as? JsonObject ?: return false
        val saved = (context["tool_checkpoint_binding"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        return selectedBinding != null && saved != null && selectedBinding != saved
    }

    /** Reentrant projection of durable terminal receipts; unknown effects remain pending. */
    fun reconcile(checkpoint: JsonObject, receipts: Collection<ToolHandoffReceipt>, reconciled: Set<String>): JsonObject {
        if (checkpoint["state"] !in states) return checkpoint
        if (GitHubProviderReplayEvidence.journalBindingKeys.any { it in checkpoint } && GitHubProviderReplayEvidence.protectedJournalDigest(checkpoint) == null) return checkpoint
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
            completed[receipt.callId] = receipt.retaining(completed[receipt.callId])
        }
        val ids = (checkpoint["reconciled_call_ids"] as? JsonArray).orEmpty().mapNotNullTo(linkedSetOf()) { (it as? JsonPrimitive)?.contentOrNull }
        ids += resolved.map { it.callId }
        return JsonObject(
            // New results change the complete set. Keep individual byte witnesses,
            // and let the next provider wait rebuild all full-journal fields together.
            checkpoint - GitHubProviderReplayEvidence.journalBindingKeys + buildJsonObject {
                put("state", if (remaining.isEmpty()) "TOOL_RESULTS_READY" else "TOOL_HANDOFF")
                put("pending_calls", JsonArray(remaining.map(::JsonPrimitive)))
                put("completed_results", JsonArray(completed.values.toList()))
                put("reconciled_call_ids", JsonArray(ids.map(::JsonPrimitive)))
                put("reconciliation_source", "TERMINAL_RECEIPT_IDENTITIES")
                put("resume_allowed", remaining.isEmpty())
                put("resume_kind", "AFTER_TOOL_RESULTS")
                put("requires_reconciliation", remaining.isNotEmpty())
                put("next_action", if (remaining.isEmpty()) "CONTINUE_SELECTED_MODEL" else "RECONCILE_TOOL_RECEIPTS")
            }
        )
    }
}
