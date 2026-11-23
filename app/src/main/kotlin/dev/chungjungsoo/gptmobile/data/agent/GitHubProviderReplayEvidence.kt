package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Stable provider replay identity shared by live checkpoints and legacy journal recovery. */
internal object GitHubProviderReplayEvidence {
    const val JOURNAL_DIGEST_VERSION = 2
    val journalBindingKeys = setOf("tool_journal_complete", "tool_journal_receipt_count", "tool_journal_digest", "tool_journal_digest_version")
    private val sha256 = Regex("[a-f0-9]{64}")
    private const val PERSISTED_PROJECTION = "PERSISTED_JOURNAL_PROJECTION_UTF8"

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

    /**
     * The output checkpoint authenticates these rows independently of the journal.
     * A v1 complete boundary already contains protected stored-byte hashes, so its
     * original digest can be checked before deriving the stronger v2 expectation.
     */
    fun protectedJournalDigest(checkpoint: JsonObject): String? = protectedBoundary(checkpoint)?.let { boundary ->
        if (!boundary.complete && boundary.receipts.any { it.sourceResultSha256 == null && it.journalProjectionSha256 == null }) {
            null
        } else {
            journalDigest(boundary.receipts.map { it.storedProjection() })
        }
    }

    fun hasProtectedJournalBoundary(checkpoint: JsonObject): Boolean = protectedBoundary(checkpoint) != null

    fun unprovenReason(checkpoint: JsonObject, actual: List<ToolHandoffReceipt> = emptyList()): String {
        val boundary = protectedBoundary(checkpoint)
        if ("completed_results" !in checkpoint || boundary?.receipts?.any { it.resultSemanticsSha256 == null } == true) return "TOOL_RESULT_SEMANTICS_UNPROVEN"
        val byCall = actual.associateBy { it.callId }
        if (boundary?.receipts?.any { saved -> byCall[saved.callId]?.let { it.resultSemanticsSha256 != saved.resultSemanticsSha256 } == true } == true) return "TOOL_RESULT_SEMANTICS_CHANGED"
        return "TOOL_REPLAY_PROJECTION_UNPROVEN"
    }

    /** Older replay-only evidence must actually prove every byte restored from disk. */
    fun reconciledJournalDigest(checkpoint: JsonObject, actual: List<ToolHandoffReceipt>): String? {
        val actualDigest = journalDigest(actual) ?: return null
        if ("completed_results" !in checkpoint) {
            if (journalBindingKeys.any { it in checkpoint }) return null
            val expected = checkpoint["provider_evidence_digest"] as? JsonPrimitive ?: return null
            return actualDigest.takeIf { expected.isString && legacyEvidenceMatches(actual, expected.content) }
        }
        val boundary = protectedBoundary(checkpoint) ?: return null
        if (boundary.receipts.size != actual.size) return null
        if (boundary.complete) return actualDigest.takeIf { it == protectedJournalDigest(checkpoint) }
        if (actual.isEmpty() && checkpoint["provider_evidence_digest"] != JsonPrimitive(digest(emptyList()))) return null
        val byCall = actual.associateBy { it.callId }
        return actualDigest.takeIf {
            boundary.receipts.all { saved ->
                val observed = byCall[saved.callId] ?: return@all false
                val sourceMatches = saved.sourceResultSha256 == observed.sourceResultSha256 ||
                    saved.sourceResultSha256 == null &&
                    observed.sourceResultSha256 == observed.projectionSha256 &&
                    saved.projectionSha256 == observed.projectionSha256
                val bytesMatch = saved.journalProjectionSha256?.let { it == observed.projectionSha256 }
                    ?: (observed.projectionSha256 == saved.projectionSha256 || observed.projectionSha256 == saved.sourceResultSha256)
                sameOperation(saved, observed) &&
                    sourceMatches &&
                    bytesMatch &&
                    saved.resultSemanticsSha256 != null &&
                    saved.resultSemanticsSha256 == observed.resultSemanticsSha256
            }
        }
    }

    /** Old source-only digests cannot prove result control metadata; only an empty journal is unambiguous. */
    fun legacyEvidenceMatches(receipts: List<ToolHandoffReceipt>, expected: String): Boolean =
        receipts.isEmpty() && expected.matches(sha256) && digest(receipts) == expected

    /** The durable boundary is already checked; compaction must retain its exact provenance. */
    fun replayMatches(receipt: ToolHandoffReceipt, actual: ToolHandoffReceipt, saved: JsonObject?): Boolean =
        sameOperation(receipt, actual) &&
            (receipt.sourceResultSha256 == null || receipt.sourceResultSha256 == actual.sourceResultSha256) &&
            receipt.resultSemanticsSha256 != null &&
            receipt.resultSemanticsSha256 == actual.resultSemanticsSha256 &&
            (
                receipt.projectionSha256 == actual.projectionSha256 ||
                    receipt.projectionSha256 == actual.sourceResultSha256 ||
                    receipt.journalProjectionSha256 == actual.projectionSha256 ||
                    saved?.get("projection_sha256") == JsonPrimitive(receipt.projectionSha256)
                )

    private fun sameOperation(first: ToolHandoffReceipt, second: ToolHandoffReceipt): Boolean =
        first.callId == second.callId &&
            first.invocationFingerprint == second.invocationFingerprint &&
            first.dispatched == second.dispatched &&
            first.errorCode == second.errorCode &&
            first.requiresReconciliation == second.requiresReconciliation

    private data class ProtectedBoundary(val receipts: List<ToolHandoffReceipt>, val complete: Boolean)

    private fun ToolHandoffReceipt.storedProjection(): ToolHandoffReceipt = copy(
        projectionSha256 = journalProjectionSha256 ?: projectionSha256,
        receiptSource = "TOOL_BATCH_CHECKPOINT"
    )

    private fun protectedBoundary(checkpoint: JsonObject): ProtectedBoundary? {
        val saved = checkpoint["completed_results"] as? JsonArray ?: return null
        val completeJournal = checkpoint["tool_journal_complete"] == JsonPrimitive(true)
        if (!completeJournal && journalBindingKeys.any { it in checkpoint }) return null
        if (saved.size > 1024) return null
        val version = if ("tool_journal_digest_version" in checkpoint) {
            (checkpoint["tool_journal_digest_version"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return null
        } else {
            1
        }
        if (version !in 1..JOURNAL_DIGEST_VERSION) return null
        val receipts = saved.map { element ->
            val receipt = element as? JsonObject ?: return null
            fun text(key: String) = (receipt[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun flag(key: String) = (receipt[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            if (receipt["source_hash_scope"] != JsonPrimitive("ORIGINAL_TOOL_RESULT_PAYLOAD_UTF8")) return null
            val source = if ("source_result_sha256" in receipt) text("source_result_sha256") ?: return null else null
            if ("source_hash_available" in receipt && flag("source_hash_available") != (source != null)) return null
            val projection = text("projection_sha256") ?: return null
            val persisted = receipt["projection_hash_scope"] == JsonPrimitive(PERSISTED_PROJECTION)
            if (persisted && receipt["receipt_source"] != JsonPrimitive("TOOL_BATCH_CHECKPOINT")) return null
            if (!persisted && receipt["projection_hash_scope"] != JsonPrimitive("REPLAY_PROJECTION_PAYLOAD_UTF8")) return null
            if (completeJournal && !persisted) return null
            val journalProjection = if ("journal_projection_sha256" in receipt) {
                // Earlier receipt writers omitted this scope, but the hash itself
                // is still protected by the exact output-checkpoint digest.
                if ("journal_projection_scope" in receipt && receipt["journal_projection_scope"] != JsonPrimitive(PERSISTED_PROJECTION)) return null
                text("journal_projection_sha256")?.takeIf { it.matches(sha256) } ?: return null
            } else {
                if ("journal_projection_scope" in receipt) return null
                null
            }
            if (persisted && journalProjection != null && journalProjection != projection) return null
            val semanticsScope = text("result_semantics_hash_scope")
            val semantics = if ("result_semantics_sha256" in receipt) {
                if (semanticsScope !in setOf(ToolHandoffReceipt.PERSISTED_SEMANTICS, ToolHandoffReceipt.REPLAY_SEMANTICS)) return null
                text("result_semantics_sha256")?.takeIf { it.matches(sha256) } ?: return null
            } else {
                if ("result_semantics_hash_scope" in receipt) return null
                null
            }
            if (completeJournal && version == JOURNAL_DIGEST_VERSION && (semantics == null || semanticsScope != ToolHandoffReceipt.PERSISTED_SEMANTICS)) return null
            val error = if ("error_code" in receipt) text("error_code") ?: return null else null
            ToolHandoffReceipt(
                callId = text("call_id") ?: return null,
                invocationFingerprint = text("invocation_fingerprint") ?: return null,
                sourceResultSha256 = source,
                projectionSha256 = projection,
                dispatched = flag("dispatched") ?: return null,
                errorCode = error,
                requiresReconciliation = flag("requires_reconciliation") ?: return null,
                receiptSource = "TOOL_BATCH_CHECKPOINT",
                journalProjectionSha256 = if (persisted) projection else journalProjection,
                resultSemanticsSha256 = semantics,
                resultSemanticsScope = semanticsScope ?: ToolHandoffReceipt.REPLAY_SEMANTICS
            )
        }
        if (!validReceiptIdentities(receipts)) return null
        val digest = if (version == 1) legacyJournalDigest(receipts) else journalDigest(receipts)
        if (completeJournal &&
            (
                (checkpoint["tool_journal_receipt_count"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull != saved.size ||
                    checkpoint["tool_journal_digest"] != JsonPrimitive(digest)
                )
        ) {
            return null
        }
        return ProtectedBoundary(receipts, completeJournal)
    }

    fun settledHandoffDigest(checkpoint: JsonObject, receipts: List<ToolHandoffReceipt>): String? {
        val calls = checkpoint["pending_calls"] as? JsonArray ?: return null
        val pending = GitHubToolHandoff.pending(checkpoint)
        if (checkpoint["state"] != JsonPrimitive("TOOL_HANDOFF") || pending.isEmpty() || pending.size != calls.size || pending.any { !it.matches(sha256) }) return null
        val completed = checkpoint["completed_results"] as? JsonArray ?: return null
        val (newlySettled, previous) = receipts.partition { it.invocationFingerprint in pending }
        if (newlySettled.map { it.invocationFingerprint }.toSet() != pending || completed.size != previous.size) return null
        if (completed.isNotEmpty() || journalBindingKeys.any { it in checkpoint }) {
            if (reconciledJournalDigest(checkpoint, previous) == null) return null
        }
        return journalDigest(receipts)
    }

    private fun validReceiptIdentities(receipts: List<ToolHandoffReceipt>): Boolean =
        !(
            receipts.size > 1024 ||
                receipts.map { it.callId }.distinct().size != receipts.size ||
                receipts.map { it.invocationFingerprint }.distinct().size != receipts.size ||
                receipts.any {
                    it.callId.isBlank() ||
                        !it.invocationFingerprint.matches(sha256) ||
                        !it.projectionSha256.matches(sha256) ||
                        it.requiresReconciliation ||
                        if (it.sourceResultSha256 != null) {
                            !it.sourceResultSha256.matches(sha256)
                        } else {
                            it.receiptSource != "TOOL_BATCH_CHECKPOINT" || !it.projectionSha256.matches(sha256)
                        }
                }
            )

    /** Bind the full stored result envelope: original identity, replay bytes, and all restored semantics. */
    fun journalDigest(receipts: List<ToolHandoffReceipt>): String? {
        if (!validReceiptIdentities(receipts) || receipts.any { it.resultSemanticsSha256?.matches(sha256) != true }) return null
        return GitHubEvidenceBook.digest(
            buildJsonObject {
                put("schema", "github.tool-journal-digest.v2")
                put(
                    "receipts",
                    JsonArray(
                        receipts.sortedBy { it.callId }.map { receipt ->
                            buildJsonObject {
                                put("call_id", receipt.callId)
                                put("invocation", receipt.invocationFingerprint)
                                put("source_hash_available", receipt.sourceResultSha256 != null)
                                receipt.sourceResultSha256?.let { put("source_result_sha256", it) }
                                put("projection_sha256", receipt.projectionSha256)
                                put("projection_hash_scope", PERSISTED_PROJECTION)
                                put("result_semantics_sha256", requireNotNull(receipt.resultSemanticsSha256))
                                put("result_semantics_hash_scope", ToolHandoffReceipt.PERSISTED_SEMANTICS)
                                put("dispatched", receipt.dispatched)
                                put("requires_reconciliation", receipt.requiresReconciliation)
                                receipt.errorCode?.let { put("error", it) }
                            }
                        }
                    )
                )
            }.toString()
        )
    }

    /** Only for validating an already protected v1 boundary; never a new journal expectation. */
    private fun legacyJournalDigest(receipts: List<ToolHandoffReceipt>): String = GitHubEvidenceBook.digest(
        JsonArray(
            receipts.sortedBy { it.callId }.map { receipt ->
                buildJsonObject {
                    put("call_id", receipt.callId)
                    put("invocation", receipt.invocationFingerprint)
                    put("result", receipt.sourceResultSha256 ?: receipt.projectionSha256)
                    if (receipt.sourceResultSha256 == null) put("result_scope", "PERSISTED_JOURNAL_PROJECTION_UTF8")
                    put("dispatched", receipt.dispatched)
                    put("requires_reconciliation", receipt.requiresReconciliation)
                    receipt.errorCode?.let { put("error", it) }
                }
            }
        ).toString()
    )
}
