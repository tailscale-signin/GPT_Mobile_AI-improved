package dev.chungjungsoo.gptmobile.data.github

/** Opaque action identity; no question, answer, credential or write approval is queued. */
data class GitHubDecisionNotificationAction(val requestId: String, val binding: String, val option: Int, val label: String)

internal fun GitHubChatDecision.notificationBinding(): String? {
    if (!persistent || conversationIdentity.isBlank() || runId.isNullOrBlank() || offeredAtMillis == null) return null
    return GitHubRepositoryStateStore.digest(
        listOf(id, chatId, sourceMessageId, profileUid, workspace, conversationIdentity, runId, offeredAtMillis).joinToString("\u0000")
    )
}

internal fun GitHubChatDecision.notificationActions(): List<GitHubDecisionNotificationAction> {
    if (answer != null || delivered || cancelled) return emptyList()
    val binding = notificationBinding() ?: return emptyList()
    // Reserve the third Android action for the complete choice/custom-answer UI.
    val selectable = options.indices.filter { !options[it].enterValue }
    val selected = (listOfNotNull(recommended?.takeIf { it in selectable }) + selectable).distinct().take(2)
    return selected.map { GitHubDecisionNotificationAction(id, binding, it, options[it].label) }
}
