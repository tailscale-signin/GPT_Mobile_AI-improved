package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import dev.chungjungsoo.gptmobile.data.agent.ToolFailureMetadata
import dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Revalidate an unsent read against a newly captured authority; never authorize or replay a write. */
internal object GitHubPolicyReadRecovery {
    const val AUTHORITY = "authorization_revision_sha256"
    const val REVALIDATED = "revalidated_read_authorization_sha256"
    private val digest = Regex("[a-f0-9]{64}")

    fun revalidated(receipt: JsonObject, currentRevision: String?): String? {
        if (currentRevision == null || !digest.matches(currentRevision) || receipt.text(AUTHORITY) == currentRevision) return null
        if (receipt["trusted"] != JsonPrimitive(true) ||
            receipt["verification_eligible"] == JsonPrimitive(false) ||
            receipt["success"] != JsonPrimitive(false) ||
            receipt["dispatched"] != JsonPrimitive(false) ||
            receipt.text("error_code") !in ToolFailureMetadata.authorityInvalidationCodes
        ) {
            return null
        }
        val execution = receipt["execution"] as? JsonObject ?: return null
        if (execution.text("authority_failed_stage").ifBlank { execution.text("failed_stage") } !in setOf("tool_precheck", "tool_admission")) return null
        if (execution["requires_reconciliation"] == JsonPrimitive(true)) return null
        if (listOf("read_requests", "mutation_requests", "accepted_mutations", "unknown_mutations").any { key ->
                key in execution && (execution[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull != 0L
            }
        ) {
            return null
        }
        val arguments = receipt["arguments"] as? JsonObject ?: return null
        if (!GitHubAuthorizationBoundary.isReplayableRead(receipt.text("tool"), arguments)) return null
        return currentRevision
    }

    fun next(tools: List<AgentToolDefinition>, receipts: List<JsonObject>): ProviderEvent.ToolCall? {
        for (receipt in receipts.asReversed()) {
            val revision = receipt.text(REVALIDATED).takeIf(digest::matches) ?: continue
            if (revalidated(receipt, revision) == null) continue
            val arguments = receipt["arguments"] as? JsonObject ?: continue
            val tool = tools.singleOrNull { it.name == receipt.text("tool") && it.modelVisible } ?: continue
            if (ToolArgumentValidator.errors(arguments, tool.inputSchema).isNotEmpty()) continue
            val prior = receipts.filter { it !== receipt && it != receipt && GitHubActionIdentity.matches(tool.name, arguments, it) }
            if (prior.any { it["success"] == JsonPrimitive(true) || it.text(AUTHORITY) == revision }) continue
            val source = receipt.text("receipt_id").takeIf { it.matches(Regex("ev_[a-f0-9]{24}")) } ?: continue
            return ProviderEvent.ToolCall("policy-read-" + GitHubEvidenceBook.digest("$source|$revision").take(24), tool.name, arguments)
        }
        return null
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
