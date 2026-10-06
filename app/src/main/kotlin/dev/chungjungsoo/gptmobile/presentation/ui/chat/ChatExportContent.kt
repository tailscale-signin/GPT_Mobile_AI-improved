package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveTimeline
import dev.chungjungsoo.gptmobile.presentation.ui.thinking.ThinkingParser

enum class ChatExportFormat { MARKDOWN, PLAIN_TEXT }

/** Only the selected AI response revision is exported. Activity and prompts are separate data. */
internal fun assistantExportText(message: MessageV2, format: ChatExportFormat): String {
    val text = message.effectiveContent().ifBlank {
        message.effectiveTimeline().filter { it.type == AssistantTimelineItemType.TEXT }.joinToString("") { it.content }
    }
    val response = ThinkingParser.extractThinking(text).response.trim()
    if (format == ChatExportFormat.MARKDOWN) return response
    return response
        .replace(Regex("(?m)^\\s*```[^\\n]*$"), "")
        .replace(Regex("(?m)^\\s*#{1,6}\\s+"), "")
        .replace(Regex("!?\\[([^\\]]+)]\\(([^)]+)\\)")) { "${it.groupValues[1]} (${it.groupValues[2]})" }
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("__(.+?)__"), "$1")
        .replace(Regex("(?<!\\w)\\*([^*\\n]+)\\*(?!\\w)"), "$1")
        .replace(Regex("(?<!\\w)_([^_\\n]+)_(?!\\w)"), "$1")
        .replace(Regex("~~(.+?)~~"), "$1")
        .replace(Regex("`([^`\\n]+)`"), "$1")
        .replace(Regex("(?m)^>\\s?"), "")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
