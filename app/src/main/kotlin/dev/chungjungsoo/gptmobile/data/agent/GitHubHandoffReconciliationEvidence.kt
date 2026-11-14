package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Observations from one completed lookup, never authority to replay an unresolved invocation. */
internal object GitHubHandoffReconciliationEvidence {
    fun capture(
        checkpoint: JsonObject,
        replayReceipts: List<ToolHandoffReceipt>,
        durableReceipts: List<ToolHandoffReceipt>,
        selectedToolBinding: String?,
        evidenceDigest: String?,
        journalDigest: String?
    ): JsonObject {
        val pending = GitHubToolHandoff.pending(checkpoint)
        val retained = (checkpoint["completed_results"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val savedContext = checkpoint["context"] as? JsonObject
        val savedBinding = (savedContext?.get("tool_checkpoint_binding") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        return buildJsonObject {
            checkpoint["state"]?.takeIf { it in GitHubToolHandoff.states }?.let { put("handoff_checkpoint_state", it) }
            put("handoff_durable_lookup", if (durableReceipts.isEmpty()) "RETURNED_EMPTY" else "RETURNED_RECEIPTS")
            fun count(key: String, value: Int) {
                if (value in 0..1024) put(key, value)
            }
            count("handoff_replay_receipt_count", replayReceipts.size)
            count("handoff_replay_matched_count", replayReceipts.count { it.invocationFingerprint in pending })
            count("handoff_durable_receipt_count", durableReceipts.size)
            count("handoff_durable_matched_count", durableReceipts.count { it.invocationFingerprint in pending })
            count("handoff_retained_matched_count", retained.count { (it["invocation_fingerprint"] as? JsonPrimitive)?.contentOrNull in pending })
            bindingHash(selectedToolBinding)?.let { put("handoff_tool_binding_hash", it) }
            bindingHash(savedBinding)?.let { put("handoff_saved_tool_binding_hash", it) }
            digestHash("evidence-digest", evidenceDigest)?.let { put("handoff_evidence_digest_hash", it) }
            digestHash("journal-digest", journalDigest)?.let { put("handoff_journal_digest_hash", it) }
        }
    }

    private fun bindingHash(value: String?): String? = value?.takeIf { it.isNotBlank() && it.length <= 4096 }
        ?.let { GitHubEvidenceBook.digest("gptmobile.handoff-evidence.v1:tool-binding:$it") }

    private fun digestHash(kind: String, value: String?): String? = value?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        ?.let { GitHubEvidenceBook.digest("gptmobile.handoff-evidence.v1:$kind:$it") }
}
