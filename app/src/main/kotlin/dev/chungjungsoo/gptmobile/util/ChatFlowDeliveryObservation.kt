package dev.chungjungsoo.gptmobile.util

import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidentCapsule
import dev.chungjungsoo.gptmobile.data.dto.ApiState

/** Observations from one collector only; no restored timeline or provider token accounting. */
internal class ChatFlowDeliveryObservation {
    private var successEvents = 0
    private var toolEvents = 0
    private var progressEvents = 0
    private var githubRounds = 0
    private var countsCapped = false

    fun observe(event: ApiState) {
        when (event) {
            is ApiState.Success -> successEvents = increment(successEvents)
            is ApiState.ToolCall -> toolEvents = increment(toolEvents)
            ApiState.GitHubRoundStarted -> githubRounds = increment(githubRounds)
            // These are delivered collector events, not completed tools or remote transport attempts.
            is ApiState.ProgressCheckpoint,
            is ApiState.ActivitySummary,
            is ApiState.Notice,
            is ApiState.PhaseChanged,
            is ApiState.GatewayProgressChanged,
            is ApiState.DelegationText -> progressEvents = increment(progressEvents)
            else -> Unit
        }
    }

    fun emptyAnswerFields(
        content: CharSequence,
        thinking: CharSequence,
        githubAnswerPending: Boolean,
        runId: String?,
        provider: String?
    ): Map<String, Any?> {
        val contentBytes = boundedUtf8Bytes(content)
        val thinkingBytes = boundedUtf8Bytes(thinking)
        return buildMap {
            putAll(IssueReportIncidentCapsule.boundaryFields(IssueReportIncidentCapsule.ExceptionCaptureSite.CHAT_FLOW_EMPTY_ANSWER))
            put("diagnostic_area", "INCIDENT")
            put("failed_stage", "delivery")
            put("failure_origin", "APPLICATION")
            put("chat_delivery_terminal", "DONE")
            put(
                "chat_delivery_answer_state",
                when {
                    githubAnswerPending -> "GITHUB_ANSWER_PENDING"
                    content.isEmpty() -> "EMPTY_CONTENT"
                    else -> "NO_ANSWER_CONTENT"
                }
            )
            put("chat_delivery_content_bytes", contentBytes)
            put("chat_delivery_thinking_bytes", thinkingBytes)
            put("chat_delivery_success_events", successEvents)
            put("chat_delivery_tool_events", toolEvents)
            put("chat_delivery_progress_events", progressEvents)
            put("chat_delivery_github_rounds", githubRounds)
            put("chat_delivery_counts_capped", countsCapped)
            runId?.let { put("run_id", it) }
            provider?.let { put("provider_kind", it) }
        }
    }

    private fun increment(value: Int): Int = if (value < MAX_COUNT) {
        value + 1
    } else {
        countsCapped = true
        MAX_COUNT
    }

    /** Count UTF-8 without copying private text or allocating an unbounded encoded buffer. */
    private fun boundedUtf8Bytes(text: CharSequence): Int {
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            val width = when {
                char.code <= 0x7f -> 1
                char.code <= 0x7ff -> 2
                Character.isHighSurrogate(char) && index < text.length && Character.isLowSurrogate(text[index]) -> {
                    index++
                    4
                }
                // The JVM UTF-8 encoder replaces an isolated surrogate with one question-mark byte.
                Character.isSurrogate(char) -> 1
                else -> 3
            }
            if (width > MAX_COUNT - bytes) {
                countsCapped = true
                return MAX_COUNT
            }
            bytes += width
        }
        return bytes
    }

    companion object {
        const val MAX_COUNT = 16_000_000
    }
}
