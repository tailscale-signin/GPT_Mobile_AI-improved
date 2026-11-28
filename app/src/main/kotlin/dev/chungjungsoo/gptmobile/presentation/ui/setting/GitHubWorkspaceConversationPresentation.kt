package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryContext

/** A saved route can label the workspace, but only a validated room can mount the composer. */
internal fun GitHubProgrammerState.forWorkspaceConversation(
    connectionUid: String,
    repository: GitHubRepositoryContext?,
    savedContext: GitHubConversationContext? = null,
    privateRoute: Boolean = false
): GitHubProgrammerState {
    val resolvedContext = context?.takeIf {
        it.connectionUid == connectionUid && it.repository == repository && it.mainProfileUid == profileUid
    }
    val displayContext = resolvedContext ?: savedContext?.takeIf {
        it.connectionUid == connectionUid && (repository == null || it.repository == repository)
    }
    val acceptedId = chatId?.takeIf {
        it > 0 &&
            resolvedContext != null &&
            conversationResolved &&
            !conversationRestoreFailed &&
            privacyModeLocked != null &&
            (!privateRoute || privacyModeLocked == true)
    }
    return copy(
        context = displayContext,
        chatId = acceptedId,
        messages = messages.takeIf { acceptedId != null }.orEmpty(),
        runs = runs.takeIf { acceptedId != null }.orEmpty(),
        events = events.takeIf { acceptedId != null }.orEmpty(),
        eventCount = eventCount.takeIf { acceptedId != null } ?: 0,
        eventCountsByRun = eventCountsByRun.takeIf { acceptedId != null }.orEmpty(),
        approvals = approvals.takeIf { acceptedId != null }.orEmpty(),
        pending = pending.takeIf { acceptedId != null }.orEmpty(),
        canLoadEarlier = canLoadEarlier && acceptedId != null,
        draftLoaded = draftLoaded && resolvedContext != null
    )
}
