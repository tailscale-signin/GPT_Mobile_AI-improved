package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Deterministic task observations survive claim extraction and replay compaction. */
internal object GitHubFailureSummary {
    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeUnless { it == "null" || it.isBlank() }

    fun render(receipts: Map<String, JsonObject>): String {
        val failures = receipts.entries.filter { (_, receipt) ->
            receipt["success"] == JsonPrimitive(false) &&
                (receipt["trusted"] == JsonPrimitive(true) || receipt["dispatched"] == JsonPrimitive(false))
        }.distinctBy { (key, receipt) ->
            receipt.text("call_id")?.let { "call:$it" } ?: receipt.text("receipt_id")?.let { "receipt:$it" } ?: "entry:$key"
        }.groupBy { (_, receipt) -> receipt.text("error_code") ?: "UNCLASSIFIED" }
        if (failures.isEmpty()) return ""
        return buildString {
            append("\n\n## Recorded operation failures\n\nThese are observed failures during this task; they do not establish the cause of an earlier interruption. Recovery actions below are recommendations.\n\n| Failure | Observations | Recommended action |\n| --- | ---: | --- |\n")
            failures.toSortedMap().forEach { (code, observations) ->
                val action = when (code) {
                    in dev.chungjungsoo.gptmobile.data.agent.ToolFailureMetadata.authorityInvalidationCodes -> GitHubVerificationRecovery.advice(observations.map { it.value })
                    "SAVED_VIEW_UNAVAILABLE" -> "Reopen the immutable source using its retained source-recovery call."
                    "SAVED_VIEW_WRONG_OPERATION", "SAVED_VIEW_CURSOR_ALTERED" -> "Use the exact issued correction call and originating tool."
                    "INVALID_ARGUMENTS" -> "Discover exact paths and validate executable arguments; omit recovery envelopes."
                    "DUPLICATE_ACTION" -> "Inspect the pending approval or saved mutation receipt. Reconcile unknown outcomes; do not repeat the write or change its message to bypass deduplication."
                    "WORKING_BRANCH_REQUIRED" -> "Select an authorized working branch, resolve its head and restage against its observed content."
                    "GITHUB_NOT_FOUND_OR_HIDDEN", "GITHUB_NOT_FOUND_OR_INACCESSIBLE" -> "Verify the observed resource ID and access once. Active-job logs remain pending; do not invent IDs or blindly retry a 404."
                    in GitHubFailurePolicy.codes, "REPOSITORY_GRAPH_UNAVAILABLE", "GATEWAY_CAPABILITY_UNAVAILABLE", "GITHUB_REF_UNAVAILABLE", "GITHUB_CIRCUIT_OPEN", "CIRCUIT_BREAKER_OPEN", "RECONCILIATION_REQUIRED", "UNKNOWN_OUTCOME" -> GitHubFailurePolicy.recoveryHint(code)
                    "BUDGET_EXHAUSTED" -> "Resume unfinished work with a fresh bounded allowance; preserve completed receipts."
                    in dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope.unknownOutcomeCodes -> "Reconcile the exact saved remote operation before another mutation. A lost response is not permission to repeat the write."
                    else -> "Inspect the retained error receipt and correct its cause before retrying."
                }
                append("| ").append(code.replace(Regex("[^A-Za-z0-9_]"), "").take(80)).append(" | ").append(observations.size).append(" | ").append(action).append(" |\n")
            }
        }
    }
}
