package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Stable provider replay identity shared by live checkpoints and legacy journal recovery. */
internal object GitHubProviderReplayEvidence {
    private val sha256 = Regex("[a-f0-9]{64}")

    fun receipts(exchanges: List<AgentToolExchange>): List<ToolHandoffReceipt> = exchanges.flatMap { exchange ->
        exchange.calls.mapNotNull { call ->
            val result = exchange.results.firstOrNull { it.callId == call.callId } ?: return@mapNotNull null
            ToolHandoffReceipt.from(call, result, originalAvailable = false)
        }
    }

    fun digest(receipts: List<ToolHandoffReceipt>): String = GitHubEvidenceBook.digest(
        JsonArray(
            receipts.sortedBy { it.invocationFingerprint }.map { receipt ->
                buildJsonObject {
                    put("invocation", receipt.invocationFingerprint)
                    put("result", receipt.sourceResultSha256 ?: receipt.projectionSha256)
                    put("dispatched", receipt.dispatched)
                    put("requires_reconciliation", receipt.requiresReconciliation)
                    receipt.errorCode?.let { put("error", it) }
                }
            }
        ).toString()
    )

    /** The replay may omit completed calls. Its digest is not the full durable journal boundary. */
    fun protectedJournalDigest(checkpoint: JsonObject): String? {
        val saved = checkpoint["completed_results"] as? JsonArray ?: return null
        if (saved.isEmpty() || saved.size > 1024) return null
        val receipts = saved.map { element ->
            val receipt = element as? JsonObject ?: return null
            fun text(key: String) = (receipt[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun flag(key: String) = (receipt[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            if (receipt["source_hash_scope"] != JsonPrimitive("ORIGINAL_TOOL_RESULT_PAYLOAD_UTF8")) return null
            val source = text("source_result_sha256") ?: return null
            val error = if ("error_code" in receipt) text("error_code") ?: return null else null
            ToolHandoffReceipt(
                callId = text("call_id") ?: return null,
                invocationFingerprint = text("invocation_fingerprint") ?: return null,
                sourceResultSha256 = source,
                projectionSha256 = source,
                dispatched = flag("dispatched") ?: return null,
                errorCode = error,
                requiresReconciliation = flag("requires_reconciliation") ?: return null
            )
        }
        return journalDigest(receipts)
    }

    /** Bind call IDs as well as source identities; retained fingerprints can outlive replay call projections. */
    fun journalDigest(receipts: List<ToolHandoffReceipt>): String? {
        if (receipts.size > 1024 ||
            receipts.map { it.callId }.distinct().size != receipts.size ||
            receipts.map { it.invocationFingerprint }.distinct().size != receipts.size ||
            receipts.any { it.callId.isBlank() || !it.invocationFingerprint.matches(sha256) || it.sourceResultSha256?.matches(sha256) != true || it.requiresReconciliation }
        ) {
            return null
        }
        return GitHubEvidenceBook.digest(
            JsonArray(
                receipts.sortedBy { it.callId }.map { receipt ->
                    buildJsonObject {
                        put("call_id", receipt.callId)
                        put("invocation", receipt.invocationFingerprint)
                        put("result", receipt.sourceResultSha256)
                        put("dispatched", receipt.dispatched)
                        put("requires_reconciliation", receipt.requiresReconciliation)
                        receipt.errorCode?.let { put("error", it) }
                    }
                }
            ).toString()
        )
    }
}
