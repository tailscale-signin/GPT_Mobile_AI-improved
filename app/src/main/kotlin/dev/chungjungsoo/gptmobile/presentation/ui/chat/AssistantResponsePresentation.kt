package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord

/** Rendering has a smaller work budget than durable inline storage. No answer bytes are discarded. */
internal const val INLINE_RESPONSE_RENDER_CHARACTERS = 4096

/** UI pages remain smaller than durable storage pages, preserving the complete answer. */
internal const val ASSISTANT_RENDER_PAGE_CHARACTERS = 2048

internal fun requiresPagedAssistantResponse(characters: Int): Boolean = characters > INLINE_RESPONSE_RENDER_CHARACTERS

/** The terminal record remains usable when the timeline notice was not restored yet. */
internal fun visibleResponseFailure(timelineFailure: String?, terminalFailure: String?, runStatus: String?): String? {
    if (runStatus == "COMPLETED") return null
    if (runStatus == "CANCELED" && terminalFailure.isNullOrBlank()) return null
    return timelineFailure?.takeIf(String::isNotBlank) ?: terminalFailure?.takeIf(String::isNotBlank)
        ?: "Generation was interrupted. Your request, partial response and completed tool operations are saved. Resume restores this task with its original profile.".takeIf { runStatus == "INTERRUPTED" }
}

/** Presentation only. Resume still reconciles saved requests and validates current permissions. */
internal fun showsInterruptedResponseControls(runStatus: String?, failure: ProfileFailureRecord?): Boolean =
    runStatus == "INTERRUPTED" ||
        runStatus in setOf("FAILED", "PARTIAL", "EVIDENCE_MISSING", "BLOCKED") &&
        failure?.category in setOf(
            "CONNECTION", "CONNECTION_ABORTED", "NETWORK_DISCONNECTED", "DNS_RESOLUTION_FAILED",
            "TIMEOUT", "NETWORK_TIMEOUT", "TRANSPORT_EOF", "UPSTREAM_UNAVAILABLE", "STREAM_DEGRADED",
            "APP_EXECUTION", "RATE_LIMIT", "GATEWAY_OUTCOME_UNKNOWN", "TOOL_OUTCOME_UNKNOWN"
        )
