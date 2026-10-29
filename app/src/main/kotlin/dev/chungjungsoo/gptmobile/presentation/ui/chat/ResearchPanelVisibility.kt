package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig

/** The destination veto applies before asynchronous chat/tool configuration has loaded. */
internal fun researchPanelsVisible(allowedByDestination: Boolean, tools: ChatMcpToolConfig): Boolean =
    allowedByDestination && tools.github == null && tools.research?.let { it.researchToggle && it.depth > 0 } == true
