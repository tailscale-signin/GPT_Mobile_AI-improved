package dev.chungjungsoo.gptmobile.data.agent.tool

/** Provider-specific completion-limit messages that should end an identical retry loop. */
internal fun isProviderOutputLimitFailure(message: String): Boolean {
    val normalized = message.lowercase()
    return listOf(
        "reached its output limit",
        "maximum output tokens",
        "maximum completion tokens",
        "finish_reason=length",
        "finish reason: length",
        "finish reason length"
    ).any(normalized::contains)
}
