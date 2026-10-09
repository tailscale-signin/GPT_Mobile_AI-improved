package dev.chungjungsoo.gptmobile.data.agent

/** Exhausted allowances cannot recover during a response; leave other providers available. */
internal fun terminalToolFailure(message: String): Boolean =
    listOf(
        "requires an api key",
        "api key required",
        "credential is missing",
        "authentication or access failed",
        "calls are used up",
        "rate limit exceeded",
        "github is cooling down",
        "daily quota exceeded",
        "quota exhausted",
        "quota exceeded",
        "insufficient credits",
        "payment required"
    ).any { message.contains(it, ignoreCase = true) }
