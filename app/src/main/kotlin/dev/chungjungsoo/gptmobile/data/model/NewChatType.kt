package dev.chungjungsoo.gptmobile.data.model

import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode

/** Creation purpose is separate from the persisted response execution mode. */
enum class NewChatType(val title: String, val explanation: String, val requiredPluginId: String? = null) {
    GITHUB("GitHub Chat", "Work on a repository with one AI profile", ToolPluginId.GITHUB),
    RESEARCH("Research Chat", "Deep Research with one or more AI models", ToolPluginId.DEEP_RESEARCH),
    SINGLE("Single Chat", "One focused AI conversation"),
    MULTI_CHAT("Multi-chat", "One message to every AI, separate responses"),
    COMBINED("Combined Mode", "Multiple AIs, one comprehensive answer");

    fun isCountValid(count: Int): Boolean = when (this) {
        SINGLE, GITHUB -> count == 1
        RESEARCH -> count >= 1
        MULTI_CHAT, COMBINED -> count >= 2
    }

    fun executionMode(count: Int): String =
        if ((this == COMBINED || this == RESEARCH) && count >= 2) {
            ConversationMode.COMBINED
        } else {
            ConversationMode.STANDARD
        }
}

/** Built-in modes first; plugin modes only exist while their owning plugin is enabled. */
internal fun availableNewChatTypes(features: AppFeatureSettings): List<NewChatType> =
    NewChatType.entries.filter { it.requiredPluginId == null } +
        NewChatType.entries.filter { mode -> mode.requiredPluginId?.let(features::isToolPluginEnabled) == true }
