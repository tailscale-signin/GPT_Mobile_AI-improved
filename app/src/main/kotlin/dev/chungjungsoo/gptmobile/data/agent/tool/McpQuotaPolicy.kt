package dev.chungjungsoo.gptmobile.data.agent.tool

/** Only inspect failed calls; successful retrieved content must never open a circuit. */
internal fun isMcpQuotaFailure(message: String): Boolean = listOf(
    "calls are used up",
    "daily quota exceeded",
    "quota exhausted",
    "quota exceeded",
    "rate limit exceeded",
    "too many requests",
    "HTTP 429"
).any { message.contains(it, ignoreCase = true) }

internal fun isMcpCredentialFailure(message: String): Boolean {
    if (Regex("\"errorOrigin\"\\s*:\\s*\"(?:upstream_source|source_host|target_site)\"", RegexOption.IGNORE_CASE).containsMatchIn(message)) return false
    return Regex("\\bHTTP[ _]*401\\b|\\bunauthorized\\b|\\binvalid (?:api )?key\\b|\\bauthentication required\\b", RegexOption.IGNORE_CASE).containsMatchIn(message)
}
