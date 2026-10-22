package dev.chungjungsoo.gptmobile.data.agent.tool

/** Execution, content and verification are independent dimensions. Estimates never prove cap violations. */
internal enum class DelegateFailureKind { OUTPUT_LIMIT, REASONING_ONLY, EMPTY_OUTPUT, AUTH_FAILURE, TRANSPORT_FAILURE }
internal enum class DelegateContentState { USABLE, PARTIAL_OUTPUT, REASONING_ONLY, EMPTY_OUTPUT }
internal enum class DelegateReviewState { NOT_REQUESTED, PASSED, REJECTED, UNAVAILABLE, TIMED_OUT }

internal fun delegateContentState(usable: Boolean, reasoningCharacters: Int, outputLimit: Boolean): DelegateContentState = when {
    usable && outputLimit -> DelegateContentState.PARTIAL_OUTPUT
    usable -> DelegateContentState.USABLE
    reasoningCharacters > 0 -> DelegateContentState.REASONING_ONLY
    else -> DelegateContentState.EMPTY_OUTPUT
}

internal fun delegateReviewState(handoff: String): DelegateReviewState = when {
    handoff.startsWith("[REVIEW_REJECTED]") -> DelegateReviewState.REJECTED
    handoff.startsWith("[REVIEW_TIMEOUT]") || handoff.startsWith("[PREPARATION_TIMEOUT]") -> DelegateReviewState.TIMED_OUT
    handoff.startsWith("[REVIEW_UNAVAILABLE]") -> DelegateReviewState.UNAVAILABLE
    handoff.startsWith("[Reviewer Score:") -> DelegateReviewState.PASSED
    else -> DelegateReviewState.NOT_REQUESTED
}

internal class DelegateGenerationException(
    val kind: DelegateFailureKind,
    message: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
    val usageEstimated: Boolean,
    partialText: String = "",
    val transportComplete: Boolean = false,
    val schemaValid: Boolean = false,
    val stageAccepted: Boolean = false
) : IllegalStateException("${kind.name}: $message") {
    /** Private checkpoint material; never part of the exception message or evidence. */
    val partialText: String = boundedDelegatePartial(partialText)
}

internal fun boundedDelegatePartial(text: String, maximumBytes: Int = 128 * 1024): String {
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maximumBytes) return text
    var end = maximumBytes.coerceAtLeast(0)
    while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
    return bytes.copyOf(end).toString(Charsets.UTF_8)
}
