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
