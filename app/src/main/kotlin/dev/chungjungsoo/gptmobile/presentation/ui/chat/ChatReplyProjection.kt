package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.chat.CompletedGeneration

/** An unread update in a different conversation/turn must not invalidate a settled answer. */
internal class ChatReplyProjection {
    private val values = ChatValueProjection<List<CompletedGeneration>>()

    fun select(chatId: Int, messageIds: Set<Int>, source: List<CompletedGeneration>): List<CompletedGeneration> =
        values.retain(source.filter { it.chatId == chatId && it.assistantMessageId in messageIds })
}
