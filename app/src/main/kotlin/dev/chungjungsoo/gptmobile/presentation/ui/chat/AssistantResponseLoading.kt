package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus

/** A saved terminal result takes precedence over a delayed loading/active-run observation. */
internal fun shouldShowAssistantResponseProgress(platformLoading: Boolean, hasActiveRun: Boolean, status: String?): Boolean =
    status !in AgentRunStatus.terminal && (platformLoading || hasActiveRun)
