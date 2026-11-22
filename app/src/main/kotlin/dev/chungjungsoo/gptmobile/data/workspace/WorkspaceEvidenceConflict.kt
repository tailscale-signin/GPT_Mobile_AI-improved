package dev.chungjungsoo.gptmobile.data.workspace

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A local evidence contract failure; raw record names and protected payloads stay out of diagnostics. */
internal class WorkspaceEvidenceConflict(
    recordId: String,
    private val proposedDigest: String,
    private val retainedDigest: String?,
    private val reason: String,
    private val recordKind: String = "github_evidence",
    private val writerPhase: String = "IMMUTABLE_APPEND",
    private val ownershipFence: Long? = null
) : IllegalStateException("Saved application evidence conflicts with an immutable record") {
    private val recordDigest = MessageDigest.getInstance("SHA-256").digest(recordId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    init {
        require(proposedDigest.matches(Regex("[a-f0-9]{64}")))
        require(retainedDigest == null || retainedDigest.matches(Regex("[a-f0-9]{64}")))
        require(reason in setOf("IMMUTABLE_PAYLOAD_CHANGED", "RETAINED_PAYLOAD_UNAVAILABLE", "CONCURRENT_APPEND_CONFLICT"))
        require(recordKind in setOf("github_evidence", "github_engineering_memory", "synthesis_checkpoint"))
        require(writerPhase in setOf("IMMUTABLE_APPEND", "CHECKPOINT_REPLACE", "TERMINAL_RECONCILIATION"))
        require(ownershipFence == null || ownershipFence >= 0)
    }

    fun event(): ProviderEvent.Failed = ProviderEvent.Failed(
        "The app could not save a new evidence version because it conflicted with an immutable record. Previously saved evidence remains available.",
        errorCode = "WORKSPACE_EVIDENCE_CONFLICT",
        taskOutcome = "BLOCKED",
        failureDetails = buildJsonObject {
            put("failed_stage", "application_persistence")
            put("failure_origin", "local_persistence")
            put("code", "WORKSPACE_EVIDENCE_CONFLICT")
            put("task_outcome", "BLOCKED")
            put("evidence_conflict_reason", reason)
            put("evidence_record_kind", recordKind)
            put("evidence_writer_phase", writerPhase)
            ownershipFence?.let { put("ownership_fence", it) }
            put("record_identity_sha256", recordDigest)
            put("proposed_payload_sha256", proposedDigest)
            retainedDigest?.let { put("retained_payload_sha256", it) }
            put("retryable", false)
            put("resume_allowed", false)
            put("request_replay_allowed", false)
            put("user_input_required", false)
            put("next_action", "REVIEW_APPLICATION_EVIDENCE")
        }
    )

    companion object {
        fun retainedDetails(details: JsonObject?): JsonObject? {
            if (details?.get("code") != JsonPrimitive("WORKSPACE_EVIDENCE_CONFLICT") || details["failure_origin"] != JsonPrimitive("local_persistence")) return null
            return buildJsonObject {
                put("code", "WORKSPACE_EVIDENCE_CONFLICT")
                put("failure_origin", "local_persistence")
                put("failed_stage", "application_persistence")
                put("next_action", "REVIEW_APPLICATION_EVIDENCE")
                (details["evidence_conflict_reason"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it in setOf("IMMUTABLE_PAYLOAD_CHANGED", "RETAINED_PAYLOAD_UNAVAILABLE", "CONCURRENT_APPEND_CONFLICT") }
                    ?.let { put("evidence_conflict_reason", it) }
                for (key in listOf("record_identity_sha256", "proposed_payload_sha256", "retained_payload_sha256")) {
                    (details[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let { put(key, it) }
                }
                (details["evidence_record_kind"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it in setOf("github_evidence", "github_engineering_memory", "synthesis_checkpoint") }?.let { put("evidence_record_kind", it) }
                (details["evidence_writer_phase"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it in setOf("IMMUTABLE_APPEND", "CHECKPOINT_REPLACE", "TERMINAL_RECONCILIATION") }?.let { put("evidence_writer_phase", it) }
                (details["ownership_fence"] as? JsonPrimitive)?.takeUnless { it.isString }?.let { fence ->
                    fence.content.toLongOrNull()?.takeIf { it >= 0 }?.let { put("ownership_fence", it) }
                }
            }
        }
    }
}
