package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Corrective guidance is derived from the observed stage; a rejection is never a sent write. */
internal object GitHubVerificationRecovery {
    fun authorizationBlocked(code: String): Boolean = code in setOf("AUTH_REQUIRED", "AUTH_FAILED", "AUTH_FAILURE", "GITHUB_SSO_REQUIRED") ||
        code.contains("PERMISSION") ||
        code.contains("AUTHORIZATION") ||
        code.contains("SCOPE_MISMATCH")

    fun advice(failures: List<JsonObject>): String {
        val codes = failures.map { it.text("error_code") }.toSet()
        return when {
            "AUTH_REQUIRED" in codes && failures.filter { it.text("error_code") == "AUTH_REQUIRED" }.all { it["dispatched"] == JsonPrimitive(false) } ->
                "Authentication/permission was rejected before dispatch. Review this tool's selected connection and conversation source/destination grants. The legacy AUTH_REQUIRED label alone does not prove a GitHub token expired. Do not repeat the unchanged request."
            codes.any(::authorizationBlocked) ->
                "Review the selected connection, repository access and tool grants; reconnect the same GitHub account only when a remote authentication rejection or expired credential is established. Unchanged authorization failures are not retried."
            "REPOSITORY_GRAPH_UNAVAILABLE" in codes ->
                "Check graph readiness on the selected gateway: its host GitHub connection, durable storage, graph service and protocol. Use separately authorized native repository reads while unavailable; their receipts do not prove graph coverage. Retry graph status only after recovery, never replay graph writes."
            "GRAPH_CONFIGURATION_REQUIRED" in codes ->
                "Configure the selected private gateway profile, persistent engineering memory and graph service in GitHub settings. No profile substitution or automatic permission change is allowed."
            codes.any { it.startsWith("SAVED_VIEW_") || it == "RETAINED_RESULT_CAPACITY" } ->
                "Use the exact retained read-only recovery recipe once; retain the unavailable result if reopening fails. Never replay a write to recover its output."
            "INVALID_ARGUMENTS" in codes ->
                "Use identifiers and required fields from the matching inventory and current tool schema; retry only corrected read arguments."
            codes.any { it in setOf("GITHUB_NOT_FOUND_OR_HIDDEN", "GITHUB_NOT_FOUND_OR_INACCESSIBLE") } ->
                "Check the observed resource, permissions and retention; a missing response does not prove deletion. Log sampling permits one different executed job."
            codes.any { it.contains("RATE_LIMIT") } ->
                "Wait until the recorded retry deadline before an authorized read."
            failures.any { it["dispatched"] != JsonPrimitive(false) && (it.text("error_code") in setOf("RECONCILIATION_REQUIRED", "UNKNOWN_OUTCOME", "DISPATCH_OUTCOME_UNKNOWN", "TIMEOUT_OUTCOME_UNKNOWN") || (it["execution"] as? JsonObject)?.get("requires_reconciliation") == JsonPrimitive(true)) } ->
                "Reconcile the exact uncertain remote operation from its saved receipt before another mutation; a lost response is not permission to repeat a write."
            else -> "Inspect the retained error, exact resource and recorded dispatch stage; correct its cause before retrying. A failed read is not an uncertain write."
        }
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
