package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Classification describes evidence; it never grants permission to replay a mutation. */
internal object GitHubFailurePolicy {
    val codes = setOf(
        "AUTH_REQUIRED", "GITHUB_PRIMARY_RATE_LIMITED", "GITHUB_SECONDARY_RATE_LIMITED", "GITHUB_SSO_REQUIRED",
        "GITHUB_BRANCH_RULES_BLOCKED", "GITHUB_ENDPOINT_PERMISSION_REQUIRED", "GITHUB_FORBIDDEN_UNKNOWN",
        "GITHUB_NOT_FOUND_OR_INACCESSIBLE", "GITHUB_CONFLICT", "GITHUB_VALIDATION_FAILED", "GITHUB_TRANSIENT_SERVER", "GITHUB_HTTP_ERROR",
        "PERMISSION_DENIED", "PERMISSION_REVOKED", "GRAPH_CONFIGURATION_REQUIRED", "INVALID_ARGUMENTS", "FIXTURE_RECONCILE_REQUIRED", "APPROVAL_PUBLICATION_UNCONFIRMED",
        "REPOSITORY_TARGET_REQUIRED", "REPOSITORY_TARGET_UNVERIFIED", "GITHUB_REPOSITORY_UNAVAILABLE", "GITHUB_PAGINATION_STALLED", "GITHUB_PAGINATION_INCOMPLETE"
    )
    private val typedCode = Regex("\\b(?:" + codes.joinToString("|") + ")\\b", RegexOption.IGNORE_CASE)

    fun recordedCode(text: String): String? = typedCode.find(text.take(32_768))?.value?.uppercase(java.util.Locale.ROOT)

    fun requiresCorrection(code: String): Boolean = code in codes && !isRateLimited(code) && code != "GITHUB_TRANSIENT_SERVER"

    fun classify(status: Int, message: String = "", remaining: String? = null, retryAfter: String? = null, sso: String? = null): String = when {
        status == 401 -> "AUTH_REQUIRED"
        status in setOf(403, 429) && remaining == "0" -> "GITHUB_PRIMARY_RATE_LIMITED"
        status == 429 || status == 403 && (retryAfter != null || Regex("(?i)secondary rate|abuse detection|temporarily blocked.*rate").containsMatchIn(message)) -> "GITHUB_SECONDARY_RATE_LIMITED"
        status == 403 && (sso != null || Regex("(?i)saml|single.sign.on").containsMatchIn(message)) -> "GITHUB_SSO_REQUIRED"
        status in setOf(403, 409, 422) && Regex("(?i)protected branch|branch protection|repository rule|GH006|GH013").containsMatchIn(message) -> "GITHUB_BRANCH_RULES_BLOCKED"
        status == 403 && Regex("(?i)resource not accessible|insufficient.*permission|requires?.*(?:scope|permission)|not permitted.*(?:workflow|action)").containsMatchIn(message) -> "GITHUB_ENDPOINT_PERMISSION_REQUIRED"
        status == 403 -> "GITHUB_FORBIDDEN_UNKNOWN"
        status == 404 -> "GITHUB_NOT_FOUND_OR_INACCESSIBLE"
        status == 409 -> "GITHUB_CONFLICT"
        status == 422 -> "GITHUB_VALIDATION_FAILED"
        GitHubReadRetry.transientStatus(status) -> "GITHUB_TRANSIENT_SERVER"
        else -> "GITHUB_HTTP_ERROR"
    }

    fun isRateLimited(code: String) = code in setOf("GITHUB_PRIMARY_RATE_LIMITED", "GITHUB_SECONDARY_RATE_LIMITED")

    fun notFoundEvidence(
        action: String = "",
        arguments: JsonObject = JsonObject(emptyMap()),
        endpoint: String? = null,
        reportedOAuthScopes: String? = null,
        acceptedOAuthScopes: String? = null
    ): JsonObject = buildJsonObject {
        put("schema", "github.failure-evidence.v1")
        put("error_code", "GITHUB_NOT_FOUND_OR_INACCESSIBLE")
        put("http_status", 404)
        action.takeIf(String::isNotBlank)?.let { put("action", it) }
        put("requested_target", JsonObject(arguments.filterKeys { it in setOf("owner", "repo", "ref", "branch", "path", "run_id", "job_id", "workflow_id") }))
        endpoint?.substringBefore('?')?.take(512)?.let { put("endpoint", it) }
        put("resource_absence_verified", false)
        put("permission_deficit_verified", false)
        reportedOAuthScopes?.take(2048)?.let { put("reported_token_oauth_scopes", it) }
        acceptedOAuthScopes?.take(2048)?.let { put("endpoint_accepted_oauth_scopes", it) }
        put("oauth_scope_interpretation", "Reported token scopes and endpoint-accepted scopes have different meanings. Accepted scopes are endpoint requirements, not a list of missing grants. These headers do not establish why a 404 occurred.")
        put("message", "The exact requested GitHub resource returned 404. It may be absent or inaccessible to this account; no deletion or permission deficit has been established.")
        put("next_action", recoveryHint("GITHUB_NOT_FOUND_OR_INACCESSIBLE"))
    }

    /** Error locations/codes are useful for repair; rejected values and free-form bodies are not diagnostic payloads. */
    fun validationDetails(body: String): String {
        val errors = runCatching { (Json.parseToJsonElement(body) as? JsonObject)?.get("errors") as? JsonArray }.getOrNull().orEmpty()
        return errors.take(8).mapNotNull { entry ->
            val fields = entry as? JsonObject ?: return@mapNotNull null
            listOf("resource", "field", "code").mapNotNull { key ->
                (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.takeIf { it.matches(Regex("[A-Za-z0-9_.\\[\\]-]{1,80}")) }?.let { "$key=$it" }
            }.takeIf { it.isNotEmpty() }?.joinToString(",")
        }.distinct().joinToString("; ")
    }

    fun recoveryHint(code: String): String = when (code) {
        "REPOSITORY_GRAPH_UNAVAILABLE" -> "Check the selected gateway's readiness and GitHub connection. Use authorized ordinary repository reads where supported; graph-only analysis remains unavailable. Do not switch AI profiles or invent graph evidence."
        "GATEWAY_CAPABILITY_UNAVAILABLE" -> "Refresh capabilities for the selected gateway and model after restoring readiness; do not substitute another profile. Preserve the saved job and reconnect to its identity."
        "GITHUB_PRIMARY_RATE_LIMITED", "GITHUB_SECONDARY_RATE_LIMITED" -> "Wait until the recorded Retry-After or rate-reset deadline before an authorized bounded read; do not replay an uncertain write. A rate limit does not revoke access."
        "GITHUB_TRANSIENT_SERVER", "GITHUB_CIRCUIT_OPEN", "CIRCUIT_BREAKER_OPEN" -> "Use the recorded cooldown and one bounded health probe. Resume confirmed checkpoints after recovery; reconcile any unknown mutation before another write."
        "RECONCILIATION_REQUIRED", "UNKNOWN_OUTCOME" -> "Read back the exact remote operation identity and retain its receipt before deciding whether another write is safe; do not blindly repeat the mutation."
        "FIXTURE_RECONCILE_REQUIRED", "APPROVAL_PUBLICATION_UNCONFIRMED" -> "Keep the existing approval and publication intent. Read the exact recorded branch and commit again after new evidence is available. No publication or cleanup was repeated."
        "GITHUB_REF_UNAVAILABLE" -> "Refresh the exact selected branch, tag or commit once after correcting it or restoring it. Do not silently switch to the default branch or fan out dependent requests."
        "REPOSITORY_TARGET_REQUIRED" -> "Select the exact repository or discover an authorized target before dependent operations. Do not invent a fixture repository."
        "REPOSITORY_TARGET_UNVERIFIED" -> "Use the selected repository or an exact target supplied by the user or returned by authorized repository discovery. A workflow preset is not a repository name."
        "GITHUB_REPOSITORY_UNAVAILABLE" -> "The exact repository lookup returned 404. Pause dependent calls, confirm the target/account, then explicitly resolve with refresh=true. A 404 does not prove deletion or a missing OAuth grant."
        "GITHUB_PAGINATION_STALLED", "GITHUB_PAGINATION_INCOMPLETE" -> "Retain the observed record IDs and incomplete traversal. Follow exact continuation arguments; if the remote inventory changed or repeated pages, refresh page 1 deliberately with verify_live=true. Do not infer complete coverage from partial or duplicate pages."
        "AUTH_REQUIRED" -> "Review the recorded dispatch stage and the selected GitHub connection. A local permission rejection does not prove credential expiry. Reconnect the same GitHub account only when GitHub actually rejected its credential. Do not repeat the unchanged request."
        "PERMISSION_DENIED" -> "Review the selected profile's GitHub tool and conversation grants. The request was blocked by local permissions; changing models or repeatedly reconnecting GitHub does not grant access."
        "PERMISSION_REVOKED" -> "The selected GitHub connection or grant was removed. Restore that connection or explicitly select an authorized one before resuming saved work."
        "GRAPH_CONFIGURATION_REQUIRED" -> "Configure repository graph support and persistent engineering memory for the selected private gateway profile. Keep the selected AI profile; native reads do not establish graph coverage."
        "INVALID_ARGUMENTS" -> "Use the current tool schema and observed resource identifiers to correct this request. Repeating identical invalid arguments will not help."
        "GITHUB_NOT_FOUND_OR_INACCESSIBLE" -> "GitHub 404 can mean absent or inaccessible. Verify the actual requested owner/repository, ref/resource ID and account access; do not treat it as proven deletion or retry unchanged. X-OAuth-Scopes describes reported token scopes; X-Accepted-OAuth-Scopes describes endpoint requirements, not missing grants. Neither header proves a permission deficit."
        "GITHUB_VALIDATION_FAILED" -> "Inspect the reported resource/field/code, discover current branches or IDs, and correct this action's required fields. A deterministic validation failure is not retried unchanged."
        "GITHUB_ENDPOINT_PERMISSION_REQUIRED" -> "Inspect the endpoint's accepted GitHub permissions and installation repository access. Repository push access does not establish this endpoint's read or write permission."
        "GITHUB_SSO_REQUIRED" -> "Authorize this GitHub App or token with the organization SSO before retrying."
        "GITHUB_BRANCH_RULES_BLOCKED" -> "Use the repository's permitted branch and PR workflow; token changes cannot override branch rules."
        "GITHUB_CONFLICT" -> "Refresh the affected resource and its expected revision before constructing a corrected request; do not repeat an uncertain write."
        "GITHUB_FORBIDDEN_UNKNOWN" -> "GitHub denied this operation without an established cause. Inspect the endpoint permissions, organization access and retained response; do not change credentials or retry unchanged without new evidence."
        else -> "Inspect the retained request and response evidence before retrying."
    }
}
