package dev.chungjungsoo.gptmobile.presentation.ui.chat

/** Rendering has a smaller work budget than durable inline storage. No answer bytes are discarded. */
internal const val INLINE_RESPONSE_RENDER_CHARACTERS = 8192

internal fun requiresPagedAssistantResponse(characters: Int): Boolean = characters > INLINE_RESPONSE_RENDER_CHARACTERS

/** The terminal record remains usable when the timeline notice was not restored yet. */
internal fun visibleResponseFailure(timelineFailure: String?, terminalFailure: String?, runStatus: String?): String? {
    if (runStatus == "COMPLETED") return null
    return timelineFailure?.takeIf(String::isNotBlank) ?: terminalFailure?.takeIf(String::isNotBlank)
}
