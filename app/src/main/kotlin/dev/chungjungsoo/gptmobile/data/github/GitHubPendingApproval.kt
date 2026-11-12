package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** A successful local staging result is a durable decision boundary, not a published commit. */
internal object GitHubPendingApproval {
    fun waiting(exchanges: List<dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange>): Boolean {
        val receipts = linkedMapOf<String, JsonObject>()
        exchanges.forEach { exchange ->
            exchange.calls.forEach callLoop@{ call ->
                val result = exchange.results.lastOrNull { it.callId == call.callId } ?: return@callLoop
                val trace = result.executionReceipt?.get("tool_trace") as? JsonObject ?: return@callLoop
                if (trace.text("schema") != "github.trace.v1") return@callLoop
                val content = result.retainedContent ?: result.content
                val value = when (content) {
                    is dev.chungjungsoo.gptmobile.data.agent.ToolResultContent.Json -> content.value as? JsonObject
                    is dev.chungjungsoo.gptmobile.data.agent.ToolResultContent.Text -> runCatching { kotlinx.serialization.json.Json.parseToJsonElement(content.text) as? JsonObject }.getOrNull()
                    else -> null
                } ?: return@callLoop
                receipts[call.callId] = buildJsonObject {
                    put("tool", call.name)
                    put("call_id", call.callId)
                    put("receipt_id", call.callId)
                    put("arguments", call.arguments)
                    put("success", !result.isError && result.errorCode == null)
                    put("trusted", true)
                    put("result", value)
                }
            }
        }
        return snapshot(receipts).isNotEmpty()
    }

    fun taskId(receipt: JsonObject): String? {
        if (receipt["trusted"] != JsonPrimitive(true) || receipt["verification_eligible"] == JsonPrimitive(false) || receipt["success"] != JsonPrimitive(true)) return null
        val root = root(receipt) ?: return null
        if (root.text("status") !in setOf("AWAITING_APPROVAL", "WAITING_FOR_APPROVAL") || root["committed"] != JsonPrimitive(false)) return null
        val native = receipt.text("tool").substringBefore("__")
        if (native !in setOf("github_write", "github_commit", "github_context", "github")) return null
        return root.text("engineering_task_id").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
    }

    fun publication(receipt: JsonObject, observations: Collection<JsonObject>): JsonObject? = resolution(receipt, observations)?.takeIf { root(it)?.text("approval_state") == "PUBLISHED" }

    fun resolution(receipt: JsonObject, observations: Collection<JsonObject>): JsonObject? {
        val id = taskId(receipt) ?: return null
        return observations.lastOrNull { candidate ->
            val result = root(candidate)
            result?.text("engineering_task_id") == id && resolutionMatches(receipt, candidate)
        }
    }

    /** A user rejection is distinct from remote cancellation and is accepted only from a durable card event. */
    fun isRejectedResolution(receipt: JsonObject): Boolean {
        if (receipt["trusted"] != JsonPrimitive(true) ||
            receipt["verification_eligible"] == JsonPrimitive(false) ||
            receipt["success"] != JsonPrimitive(true) ||
            receipt["local_resolution_event"] != JsonPrimitive("approval_card_rejected")
        ) {
            return false
        }
        val result = root(receipt) ?: return false
        return result.text("approval_state") == "CANCELLED_BEFORE_PUBLICATION" &&
            result["cancellation_confirmed"] == JsonPrimitive(true) &&
            result["remote_mutation_performed"] == JsonPrimitive(false)
    }

    private fun resolutionMatches(previous: JsonObject, candidate: JsonObject): Boolean {
        if (candidate["trusted"] != JsonPrimitive(true) ||
            candidate["verification_eligible"] == JsonPrimitive(false) ||
            candidate["success"] != JsonPrimitive(true) ||
            candidate["binding"] != previous["binding"] ||
            candidate["repository"] != previous["repository"] ||
            candidate.text("tool").substringBefore("__") != "github_context"
        ) {
            return false
        }
        val result = root(candidate) ?: return false
        val resolutionArguments = candidate["arguments"] as? JsonObject ?: return false
        if (resolutionArguments.text("action") != "recovery" || resolutionArguments.text("engineering_task_id") != taskId(previous)) return false
        val args = previous["arguments"] as? JsonObject
        val branch = args?.text("branch").orEmpty().ifBlank { root(previous)?.text("branch").orEmpty().ifBlank { previous.text("branch") } }
        val expected = args?.text("expected_head_sha").orEmpty().ifBlank { root(previous)?.text("expected_head_sha").orEmpty() }
        if (branch.isBlank() || result.text("branch") != branch) return false
        val cancelled = result.text("approval_state") == "CANCELLED_BEFORE_PUBLICATION" &&
            result["remote_mutation_performed"] == JsonPrimitive(false)
        if (cancelled && candidate["evidence_origin"] == JsonPrimitive("LOCAL_DURABLE_APPROVAL_EVENT")) {
            val stagedResult = root(previous) ?: return false
            val stagedDigest = stagedResult.text("patch_digest").ifBlank { args?.text("patch_digest").orEmpty() }
            val stagedExpected = args?.text("expected_head_sha").orEmpty().ifBlank { stagedResult.text("expected_head_sha") }
            val stagedBinding = stagedResult.text("approval_binding_sha256")
            return candidate["local_resolution_event"] == JsonPrimitive("approval_card_rejected") &&
                result["cancellation_confirmed"] == JsonPrimitive(true) &&
                result.text("native_task_state") == "CANCELLED" &&
                stagedExpected.isNotBlank() &&
                result.text("expected_head_sha") == stagedExpected &&
                stagedDigest.isNotBlank() &&
                result.text("patch_digest") == stagedDigest &&
                (stagedBinding.isBlank() || result.text("approval_binding_sha256") == stagedBinding)
        }
        return cancelled ||
            result.text("approval_state") == "PUBLISHED" &&
            result["remote_state_verified"] == JsonPrimitive(true) &&
            result.text("sha").matches(Regex("[a-fA-F0-9]{40}")) &&
            expected.isNotBlank() &&
            result.text("parent_sha") == expected
    }

    fun snapshot(receipts: Map<String, JsonObject>): JsonArray {
        val pending = linkedMapOf<String, Pair<JsonObject, JsonObject>>()
        receipts.values.forEach { receipt ->
            val taskId = taskId(receipt)
            if (taskId != null) {
                val arguments = receipt["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                val root = requireNotNull(root(receipt))
                val chatId = (root["chat_id"] as? JsonPrimitive)?.intOrNull
                    ?: (receipt["chat_id"] as? JsonPrimitive)?.intOrNull
                    ?: 0
                val repository = receipt.text("repository")
                val branch = arguments.text("branch").ifBlank { root.text("branch").ifBlank { receipt.text("branch") } }
                val expectedHead = arguments.text("expected_head_sha").ifBlank { root.text("expected_head_sha") }
                val patchDigest = root.text("patch_digest").ifBlank { arguments.text("patch_digest") }
                val bindingDigest = GitHubApprovalBinding.dedupeKey(chatId, repository, branch, expectedHead, patchDigest, taskId)
                val descriptor = buildJsonObject {
                    put("state", "WAITING_FOR_APPROVAL")
                    put("engineering_task_id", taskId)
                    put("card_id", "github-approval-$taskId")
                    if (chatId > 0) put("chat_id", chatId)
                    put("receipt_id", receipt["receipt_id"] ?: JsonPrimitive("unknown"))
                    put("repository", repository)
                    put("branch", branch)
                    if (expectedHead.isNotBlank()) put("expected_head_sha", expectedHead)
                    if (patchDigest.isNotBlank()) put("patch_digest", patchDigest)
                    put("approval_binding_sha256", bindingDigest)
                    arguments["stage_id"]?.let { put("stage_id", it) }
                    put("staged_arguments_sha256", GitHubEvidenceBook.digest(arguments.toString()))
                    put("patch_source", "EXISTING_ENGINEERING_TASK_AND_IMMUTABLE_RECEIPT")
                    put("risk", "No remote commit has been published. Review the exact frozen patch before approval.")
                    root["recovery_call"]?.let { put("recovery_call", it) }
                    put("next_action", "Open the existing Approve & Commit card to review this exact patch. A generic Continue does not grant approval. Keep its fixture until approval is resolved.")
                }
                // A later status read must not replace the original staged patch identity.
                pending.putIfAbsent(taskId, receipt to descriptor)
            } else if (receipt["trusted"] == JsonPrimitive(true) && receipt["verification_eligible"] != JsonPrimitive(false) && receipt["success"] == JsonPrimitive(true)) {
                val result = root(receipt) ?: return@forEach
                val id = result.text("engineering_task_id")
                val previous = pending[id]?.first ?: return@forEach
                if (resolutionMatches(previous, receipt)) {
                    pending.remove(id)
                } else if (receipt["trusted"] == JsonPrimitive(true) &&
                    receipt["success"] == JsonPrimitive(true) &&
                    receipt["verification_eligible"] != JsonPrimitive(false) &&
                    receipt["binding"] == previous["binding"] &&
                    receipt["repository"] == previous["repository"] &&
                    receipt.text("tool").substringBefore("__") == "github_context" &&
                    result.text("approval_state") in setOf("WAITING_FOR_APPROVAL", "EXACT_PATCH_APPROVAL_PENDING", "APPROVED_AWAITING_DISPATCH", "PUBLICATION_AWAITING_REMOTE_VERIFICATION", "NATIVE_APPROVAL_STATE_UNAVAILABLE")
                ) {
                    val descriptor = requireNotNull(pending[id]).second
                    pending[id] = previous to JsonObject(
                        descriptor + mapOf(
                            "native_state" to JsonPrimitive(result.text("native_state").ifBlank { "UNKNOWN" }),
                            "approval_state" to JsonPrimitive(result.text("approval_state"))
                        )
                    )
                }
            }
        }
        return JsonArray(pending.values.map { it.second })
    }

    private fun root(receipt: JsonObject) = (receipt["result"] as? JsonObject)?.let { (it["data"] as? JsonObject) ?: it }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
