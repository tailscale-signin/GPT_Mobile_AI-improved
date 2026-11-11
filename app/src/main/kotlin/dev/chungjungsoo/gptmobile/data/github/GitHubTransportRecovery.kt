package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Transport recovery uses the exact retained operation and never renews its authorization. */
internal object GitHubTransportRecovery {
    const val MAX_WAIT_MILLIS = 30_000L
    val codes = setOf("GITHUB_CIRCUIT_OPEN", "GITHUB_TRANSIENT_NETWORK", "GITHUB_TRANSIENT_SERVER")

    fun provenNoMutation(receipt: JsonObject): Boolean {
        if (receipt["trusted"] != JsonPrimitive(true) ||
            receipt["verification_eligible"] == JsonPrimitive(false) ||
            receipt["success"] != JsonPrimitive(false)
        ) {
            return false
        }
        val execution = receipt["execution"] as? JsonObject ?: return false
        return execution["schema"] == JsonPrimitive("github.dispatch.v1") &&
            execution.text("phase") in setOf("PREFLIGHT_REJECTED", "READ_FAILED") &&
            execution["requires_reconciliation"] == JsonPrimitive(false) &&
            listOf("mutation_requests", "accepted_mutations", "rejected_mutations", "unknown_mutations")
                .all { execution[it] == JsonPrimitive(0) }
    }

    fun retryable(receipt: JsonObject): Boolean {
        if (!provenNoMutation(receipt)) return false
        return receipt.text("error_code") in codes ||
            receipt.text("error_code") == "GITHUB_OPERATION_FAILED" &&
            (receipt["result"] as? JsonObject)?.text("data").orEmpty().let {
                it.startsWith("GitHub operation ") && it.contains(" failed: GITHUB_CIRCUIT_OPEN:")
            }
    }

    fun retryNotBefore(receipts: Collection<JsonObject>): Long? = receipts.asSequence()
        .filter(::retryable)
        .mapNotNull { ((it["execution"] as? JsonObject)?.get("retry_not_before_ms") as? JsonPrimitive)?.longOrNull }
        .maxOrNull()

    fun nextRead(tools: List<AgentToolDefinition>, receipts: List<JsonObject>): ProviderEvent.ToolCall? {
        for (receipt in receipts) {
            if (!retryable(receipt) || receipt["remote_mutation"] == JsonPrimitive(true)) continue
            val arguments = receipt["arguments"] as? JsonObject ?: continue
            val name = receipt.text("tool")
            if (!GitHubAuthorizationBoundary.isReplayableRead(name, arguments) ||
                GitHubAuthorizationBoundary.isBranchSelection(name, arguments) ||
                name.substringBefore("__") == "repository_graph" ||
                "view_cursor" in arguments
            ) {
                continue
            }
            val tool = tools.singleOrNull { it.name == name && it.modelVisible } ?: continue
            if (ToolArgumentValidator.errors(arguments, tool.inputSchema).isNotEmpty()) continue
            if (receipts.any { it != receipt && GitHubActionIdentity.matches(name, arguments, it) }) continue
            val id = receipt.text("receipt_id").takeIf { it.isNotBlank() } ?: continue
            return ProviderEvent.ToolCall("transport-read-" + GitHubEvidenceBook.digest(id).take(24), tool.name, arguments)
        }
        return null
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.content.orEmpty()
}
