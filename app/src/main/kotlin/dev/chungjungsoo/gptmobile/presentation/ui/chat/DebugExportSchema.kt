package dev.chungjungsoo.gptmobile.presentation.ui.chat

/** Conversation and archive versions evolve independently. Keep old readers explicit. */
internal object DebugExportSchema {
    const val CONVERSATION_VERSION = 4
    const val CONVERSATION = "gptmobile.conversation.debug.v4"
    const val BUNDLE = "gptmobile.conversation.debug.bundle.v3"
    val supportedConversations = setOf("gptmobile.conversation.debug.v2", "gptmobile.conversation.debug.v3", CONVERSATION)
}
