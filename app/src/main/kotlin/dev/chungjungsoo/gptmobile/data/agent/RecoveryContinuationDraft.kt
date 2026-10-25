package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.research.researchHash
import dev.chungjungsoo.gptmobile.util.stripAssistantErrorNote

/** Count earlier answer slots once; the active slot is already included in the restored draft. */
internal fun previousRecoveryAnswerWords(
    messages: List<List<MessageV2>>,
    profile: String,
    sourceMessageId: Int,
    activeRunId: String
): Int {
    val answers = messages.flatten()
    val activeMessageId = answers.firstOrNull { it.currentRunId == activeRunId }?.id
    return answers.filter { it.platformType == profile && it.linkedMessageId >= sourceMessageId && it.id != activeMessageId }
        .distinctBy { it.id }
        .sumOf { LongResponsePolicy.countWords(stripAssistantErrorNote(it.content)) }
}

/** A resumed quota wait may extend exactly its retained answer, never silently restart it. */
internal suspend fun recoveryContinuationDraft(
    recovery: ResponseRecoveryContext?,
    descriptor: TaskResumeDescriptor?,
    request: String
): String {
    if (descriptor == null) return ""
    val draft = recovery?.authorizedAnswer(descriptor.generationId).orEmpty()
    descriptor.continuationPrefixDigest?.let { expected ->
        require(draft.isNotBlank() && researchHash(draft) == expected) {
            "PROVIDER_WAIT_PREFIX_CHANGED: The saved answer changed while waiting. It is retained; review it before continuing."
        }
        return draft
    }
    return draft.takeIf { recovery?.answerCompleted(descriptor.generationId) == false || LongResponsePolicy.needsMore(it, LongResponsePolicy.requestedWords(request)) }.orEmpty()
}
