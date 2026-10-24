package dev.chungjungsoo.gptmobile.presentation.ui.home

import dev.chungjungsoo.gptmobile.data.conversation.AnswerProjection
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.research.researchHash
import dev.chungjungsoo.gptmobile.util.isAssistantErrorMessage
import dev.chungjungsoo.gptmobile.util.stripAssistantErrorNote

internal data class ConversationDeliveryEvidence(val textDelivered: Boolean = false, val reportAccepted: Boolean = false)

/** Completion belongs to the current owned answer revision, never a contributor or old turn. */
internal fun conversationDeliveryEvidence(runs: List<AgentRun>, messages: List<MessageV2>, combined: Boolean): ConversationDeliveryEvidence {
    val latestTurnId = messages.filter { it.platformType == null }.maxOfOrNull { it.id } ?: return ConversationDeliveryEvidence()
    val current = runs.filter { run ->
        run.userMessageId == latestTurnId &&
            messages.any { message ->
                message.id == run.assistantMessageId &&
                    message.chatId == run.chatId &&
                    message.linkedMessageId == latestTurnId &&
                    message.currentRunId == run.runId &&
                    message.ownershipFence == run.ownershipFence
            }
    }
    val expected = if (combined) {
        listOfNotNull(current.filter { it.isCombinedFinal() }.maxWithOrNull(conversationAttemptOrder))
    } else {
        current.filterNot { it.isCombinedFinal() }.groupBy { it.profileUid }.values.map { it.maxWith(conversationAttemptOrder) }
    }
    if (expected.isEmpty() || expected.any { it.status != AgentRunStatus.COMPLETED }) return ConversationDeliveryEvidence()
    val answers = expected.map { run -> messages.first { it.id == run.assistantMessageId } }
    if (answers.any { isAssistantErrorMessage(it.content) || AnswerProjection.text(stripAssistantErrorNote(it.content)).isBlank() }) return ConversationDeliveryEvidence()
    val accepted = answers.all { message ->
        message.timeline.lastOrNull { it.researchDeliveryReceipt != null }?.researchDeliveryReceipt?.let { it.accepted && it.matchesRevision(message.content) } == true ||
            message.timeline.lastOrNull { it.combinedQuality != null }?.combinedQuality?.let {
                it.complete && !it.correctionRequired && it.answerDigest == researchHash(message.content) && it.deliveredScopeStatus in setOf("COMPLETED", "COMPLETED_WITH_LIMITS")
            } == true
    }
    return ConversationDeliveryEvidence(textDelivered = true, reportAccepted = accepted)
}
