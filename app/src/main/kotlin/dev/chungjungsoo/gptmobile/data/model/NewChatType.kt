package dev.chungjungsoo.gptmobile.data.model

import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode

/** Creation purpose is separate from the persisted response execution mode. */
enum class NewChatType(val title: String, val explanation: String) {
    RESEARCH("Research Chat", "Deep Research with one or more AI models"),
    SINGLE("Single Chat", "One focused AI conversation"),
    MULTI_CHAT("Multi-chat", "Independent conversations, one workspace"),
    COMBINED("Combined Mode", "Multiple AIs, one comprehensive answer");

    fun isCountValid(count: Int): Boolean = when (this) {
        SINGLE -> count == 1
        RESEARCH -> count >= 1
        MULTI_CHAT, COMBINED -> count >= 2
    }

    fun executionMode(count: Int): String =
        if ((this == COMBINED || this == RESEARCH) && count >= 2) ConversationMode.COMBINED
        else ConversationMode.STANDARD
}
