package dev.melo.gptmobile.improved.data.context

import dev.melo.gptmobile.improved.data.database.entity.MessageV2

data class ConversationTurn(
    val userMessage: MessageV2,
    val assistantMessage: MessageV2?,
    val isCurrentTurn: Boolean
)
