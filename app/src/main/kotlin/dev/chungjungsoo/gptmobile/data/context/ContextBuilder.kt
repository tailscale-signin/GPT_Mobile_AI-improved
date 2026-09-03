package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.database.entity.ACTIVE_REVISION_LATEST
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.util.isAssistantErrorMessage
import dev.chungjungsoo.gptmobile.util.stripAssistantErrorNote
import javax.inject.Inject

class ContextBuilder @Inject constructor() {
    fun build(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2,
        policy: ProviderContextPolicy = ProviderContextPolicy.forClientType(platform.compatibleType)
    ): List<ConversationTurn> {
        if (userMessages.isEmpty()) return emptyList()

        val lastUserIndex = userMessages.lastIndex
        val rawTurns = ArrayList<RawConversationTurn>(userMessages.size)

        for (index in userMessages.indices) {
            val userMessage = userMessages[index]
            val candidates = assistantMessages.getOrNull(index)
            val assistantCandidates = if (candidates.isNullOrEmpty()) {
                emptyList()
            } else {
                candidates.filter { it.platformType == platform.uid }
            }
            val assistantMessage = assistantCandidates.firstValidAssistantCandidate(platform.uid)

            val turn = RawConversationTurn(
                userMessage = userMessage,
                assistantMessage = assistantMessage,
                hasAssistantError = assistantMessage == null &&
                    assistantCandidates.any { candidate ->
                        isAssistantErrorMessage(sanitizeAssistantMessageForContext(candidate).content)
                    },
                isCurrentTurn = index == lastUserIndex
            )

            // Filter out history turns that have assistant errors directly
            if (turn.isCurrentTurn || !turn.hasAssistantError) {
                rawTurns.add(turn)
            }
        }

        if (rawTurns.isEmpty()) return emptyList()

        val currentTurn = rawTurns.lastOrNull { it.isCurrentTurn }
        val historyTurns = if (currentTurn != null) {
            val historyCount = rawTurns.size - 1
            val startIndex = maxOf(0, historyCount - policy.recentTurnWindow)
            rawTurns.subList(startIndex, historyCount)
        } else {
            val startIndex = maxOf(0, rawTurns.size - policy.recentTurnWindow)
            rawTurns.subList(startIndex, rawTurns.size)
        }

        val selectedTurns = ArrayList<RawConversationTurn>(historyTurns.size + (if (currentTurn != null) 1 else 0))
        selectedTurns.addAll(historyTurns)
        if (currentTurn != null) {
            selectedTurns.add(currentTurn)
        }

        return applyAttachmentWindow(selectedTurns, policy)
    }

    private fun List<MessageV2>.firstValidAssistantCandidate(platformUid: String): MessageV2? {
        for (i in indices) {
            val message = this[i]
            if (message.platformType != platformUid) continue

            val sanitizedMessage = sanitizeAssistantMessageForContext(message)
            val isValid = when {
                sanitizedMessage.effectiveContent().isBlank() && sanitizedMessage.attachments.isEmpty() -> false
                isAssistantErrorMessage(sanitizedMessage.content) -> false
                else -> true
            }
            if (isValid) return sanitizedMessage
        }
        return null
    }

    private fun applyAttachmentWindow(
        turns: List<RawConversationTurn>,
        policy: ProviderContextPolicy
    ): List<ConversationTurn> {
        if (turns.isEmpty()) return emptyList()

        val lastIndex = turns.lastIndex
        val result = ArrayList<ConversationTurn>(turns.size)

        for (index in turns.indices) {
            val turn = turns[index]
            val shouldKeepAttachments = (lastIndex - index) <= policy.historicalImageTurnWindow

            val userMessage = when {
                shouldKeepAttachments || turn.userMessage.attachments.isEmpty() -> turn.userMessage
                else -> turn.userMessage.copy(attachments = emptyList())
            }

            val assistantMessage = turn.assistantMessage?.let { message ->
                when {
                    shouldKeepAttachments || message.attachments.isEmpty() -> message
                    else -> message.copy(attachments = emptyList())
                }
            }

            result.add(
                ConversationTurn(
                    userMessage = userMessage,
                    assistantMessage = assistantMessage,
                    isCurrentTurn = turn.isCurrentTurn
                )
            )
        }

        return result
    }

    private fun sanitizeAssistantMessageForContext(message: MessageV2): MessageV2 {
        val sanitizedContent = stripAssistantErrorNote(message.effectiveContent()).trimEnd()
        return if (sanitizedContent == message.content && message.activeRevisionIndex == ACTIVE_REVISION_LATEST) {
            message
        } else {
            message.copy(
                content = sanitizedContent,
                activeRevisionIndex = ACTIVE_REVISION_LATEST
            )
        }
    }
}

private data class RawConversationTurn(
    val userMessage: MessageV2,
    val assistantMessage: MessageV2?,
    val hasAssistantError: Boolean,
    val isCurrentTurn: Boolean
)
