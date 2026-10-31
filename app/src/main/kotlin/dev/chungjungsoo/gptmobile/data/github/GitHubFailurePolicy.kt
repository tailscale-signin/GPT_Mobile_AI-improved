package dev.chungjungsoo.gptmobile.data.github

/** Classification describes evidence; it never grants permission to replay a mutation. */
internal object GitHubFailurePolicy {
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
}
