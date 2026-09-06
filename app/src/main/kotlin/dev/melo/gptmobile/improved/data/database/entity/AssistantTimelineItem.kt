package dev.melo.gptmobile.improved.data.database.entity

import kotlinx.serialization.Serializable

@Serializable
enum class AssistantTimelineItemType {
    THINKING,
    TOOL_CALL,
    TEXT_CHUNK
}

@Serializable
data class AssistantTimelineItem(
    val id: String,
    val type: AssistantTimelineItemType,
    val content: String = "",
    val toolName: String? = null,
    val callId: String? = null,
    val timestamp: Long = System.currentTimeMillis() / 1000
)

fun List<AssistantTimelineItem>.extractThought(): String? {
    val thinkingItems = filter { it.type == AssistantTimelineItemType.THINKING }
    if (thinkingItems.isEmpty()) return null
    return thinkingItems.joinToString("\n") { it.content }.trim().ifEmpty { null }
}

fun List<AssistantTimelineItem>.extractText(): String {
    return filter { it.type == AssistantTimelineItemType.TEXT_CHUNK }
        .joinToString("") { it.content }
}
