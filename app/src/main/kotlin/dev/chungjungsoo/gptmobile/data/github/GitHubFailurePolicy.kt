package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Classification describes evidence; it never grants permission to replay a mutation. */
internal object GitHubFailurePolicy {
    val codes = setOf(
        "AUTH_REQUIRED", "GITHUB_PRIMARY_RATE_LIMITED", "GITHUB_SECONDARY_RATE_LIMITED", "GITHUB_SSO_REQUIRED",
        "GITHUB_BRANCH_RULES_BLOCKED", "GITHUB_ENDPOINT_PERMISSION_REQUIRED", "GITHUB_FORBIDDEN_UNKNOWN",
        "GITHUB_NOT_FOUND_OR_INACCESSIBLE", "GITHUB_CONFLICT", "GITHUB_VALIDATION_FAILED", "GITHUB_TRANSIENT_SERVER", "GITHUB_HTTP_ERROR"
    )

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
        "AUTH_REQUIRED" -> "Reconnect the same GitHub account and verify credential expiry; no repeated request can repair authentication."
        "GITHUB_NOT_FOUND_OR_INACCESSIBLE" -> "GitHub 404 can mean absent or inaccessible. Verify owner/repository, exact ref/resource ID and the account's repository access; do not treat it as proven deletion or retry unchanged."
        "GITHUB_VALIDATION_FAILED" -> "Inspect the reported resource/field/code, discover current branches or IDs, and correct this action's required fields. A deterministic validation failure is not retried unchanged."
        "GITHUB_ENDPOINT_PERMISSION_REQUIRED" -> "Inspect the endpoint's accepted GitHub permissions and installation repository access. Repository push access does not establish this endpoint's read or write permission."
        "GITHUB_SSO_REQUIRED" -> "Authorize this GitHub App or token with the organization SSO before retrying."
        "GITHUB_BRANCH_RULES_BLOCKED" -> "Use the repository's permitted branch and PR workflow; token changes cannot override branch rules."
        "GITHUB_CONFLICT" -> "Refresh the affected resource and its expected revision before constructing a corrected request; do not repeat an uncertain write."
        else -> "Inspect the retained request and response evidence before retrying."
    }
}
