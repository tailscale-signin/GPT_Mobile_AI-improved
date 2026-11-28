package dev.chungjungsoo.gptmobile.data.voice

import dev.chungjungsoo.gptmobile.data.agent.resolveTaskRecovery
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2

/** Typed metadata in the existing timeline JSON survives queues, retries, exports and process death. */
internal object VoiceResponsePresentation {
    fun attach(message: MessageV2, policy: VoiceResponsePolicy?): MessageV2 {
        if (policy == null || message.platformType != null) return message
        return message.copy(
            timeline = message.timeline.filterNot { it.voiceResponsePolicy != null } +
                AssistantTimelineItem(AssistantTimelineItemType.NOTICE, voiceResponsePolicy = policy)
        )
    }

    fun resolve(messages: List<MessageV2>, requested: VoiceResponsePolicy? = null): VoiceResponsePolicy? {
        val latest = messages.lastOrNull()?.takeIf { it.platformType == null } ?: return requested
        latest.timeline.lastOrNull { it.voiceResponsePolicy != null }?.voiceResponsePolicy?.let { return it }
        if (requested != null) return requested
        // A continuation inherits only the original task's persisted presentation. An unrelated
        // new text request must never acquire voice mode from an earlier conversation turn.
        val recovery = resolveTaskRecovery(messages) ?: return null
        return messages.lastOrNull { it.id == recovery.sourceMessageId && it.chatId == latest.chatId && it.platformType == null }
            ?.timeline?.lastOrNull { it.voiceResponsePolicy != null }?.voiceResponsePolicy?.takeUnless { it.translationOnly }
    }

    fun compose(systemPrompt: String?, policy: VoiceResponsePolicy?): String? {
        if (policy == null) return systemPrompt
        if (policy.translationOnly) return policy.instruction()
        return listOfNotNull(systemPrompt?.takeIf(String::isNotBlank), policy.instruction()).joinToString("\n\n")
    }
}
