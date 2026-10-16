package dev.chungjungsoo.gptmobile.data.agent.tool

/** Provider-specific completion-limit messages that should end an identical retry loop. */
internal fun isProviderOutputLimitFailure(message: String): Boolean {
    val normalized = message.lowercase()
    return listOf(
        "reached its output limit",
        "output_limit:",
        "maximum output tokens",
        "maximum completion tokens",
        "finish_reason=length",
        "finish reason: length",
        "finish reason length"
    ).any(normalized::contains)
}

/** These pre-dispatch failures cannot improve by resending identical configuration. */
internal fun isDeterministicDelegateConfigurationFailure(message: String): Boolean = listOf(
    "REASONING_OFF_UNSUPPORTED",
    "REASONING_ON_UNSUPPORTED",
    "MODEL_CONFIGURATION",
    "LOCAL_CONTEXT_EXHAUSTED",
    "LOCAL_CONTEXT_UNVERIFIED",
    "NATIVE_SESSION_QUARANTINED",
    "NATIVE_ARTIFACT_QUARANTINED",
    "interrupted inside native code"
).any { message.contains(it, ignoreCase = true) }
