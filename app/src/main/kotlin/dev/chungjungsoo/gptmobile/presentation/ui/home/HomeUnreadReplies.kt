package dev.chungjungsoo.gptmobile.presentation.ui.home

import dev.chungjungsoo.gptmobile.data.chat.CompletedGeneration
import dev.chungjungsoo.gptmobile.data.chat.ReplyDisplayRole

/** Input is the publication store's unacknowledged revisions, never run activity or chat dates. */
internal fun homeUnreadReplies(
    publications: List<CompletedGeneration>,
    workspaces: Map<Int, List<Int>>
): List<CompletedGeneration> {
    val roots = workspaces.flatMap { (root, children) -> children.map { it to root } }.toMap()
    return publications.filter {
        ReplyDisplayRole.visible(it.displayRole) && it.revision > 0 && it.assistantMessageId > 0 && it.contentDigest.isNotBlank()
    }.map { it.copy(visibleChatId = roots[it.chatId] ?: it.chatId) }
}
