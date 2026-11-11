package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Tool completion, a local proposal and verified remote publication are distinct observations. */
internal object GitHubOperationOutcome {
    fun snapshot(receipt: JsonObject): JsonObject {
        val source = (receipt["result"] as? JsonObject)?.let(GitHubSemanticSummary::data) ?: JsonObject(emptyMap())
        val execution = receipt["execution"] as? JsonObject
        fun count(key: String) = (execution?.get(key) as? JsonPrimitive)?.longOrNull ?: 0
        val accepted = count("accepted_mutations")
        val pending = source.text("status") in setOf("AWAITING_APPROVAL", "WAITING_FOR_APPROVAL") || source.text("approval_state") in setOf("PENDING", "AWAITING_APPROVAL", "WAITING_FOR_APPROVAL")
        val proposal = source["committed"] == JsonPrimitive(false) && (pending || source["stage_id"] != null || source["engineering_task_id"] != null || source["proposal"] != null) || source.text("status") in setOf("STAGED", "PROPOSAL_PREPARED")
        val verified = !proposal &&
            !pending &&
            count("unknown_mutations") == 0L &&
            receipt["success"] == JsonPrimitive(true) &&
            (source["remote_state_verified"] == JsonPrimitive(true) || source["receipt_verified"] == JsonPrimitive(true) || source["absence_verified"] == JsonPrimitive(true))
        val transportKnown = execution?.containsKey("mutation_requests") == true || execution?.containsKey("read_requests") == true
        val remote = count("mutation_requests") > 0 || count("read_requests") > 0 || accepted > 0
        return buildJsonObject {
            put("schema", "github.operation-outcome.v1")
            put("tool_invoked", true)
            put("proposal_prepared", proposal)
            put("approval_pending", pending)
            put("remote_dispatched", if (transportKnown || remote) JsonPrimitive(remote) else JsonNull)
            put("accepted_mutations", accepted)
            put("remote_outcome_verified", verified)
            put(
                "state",
                when {
                    count("unknown_mutations") > 0 -> "UNKNOWN_REMOTE_OUTCOME"
                    pending -> "WAITING_FOR_APPROVAL"
                    proposal -> "PROPOSAL_PREPARED"
                    verified -> "REMOTE_OUTCOME_VERIFIED"
                    accepted > 0 -> "MUTATION_ACCEPTED"
                    remote -> "REMOTE_DISPATCH_OBSERVED"
                    receipt["success"] == JsonPrimitive(true) -> "LOCAL_RESULT_AVAILABLE"
                    else -> "TOOL_REJECTED_OR_FAILED"
                }
            )
        }
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
