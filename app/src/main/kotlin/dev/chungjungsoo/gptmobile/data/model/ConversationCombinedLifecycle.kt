package dev.chungjungsoo.gptmobile.data.model

import java.util.UUID

/** Pure transitions shared by repository writes, migration, and lifecycle tests. */
object ConversationCombinedLifecycle {
    fun ensureIdentity(current: AppFeatureSettings, chatId: Int, preferredIdentity: String? = null): AppFeatureSettings {
        require(chatId > 0) { "Persist the conversation before saving its settings." }
        if (!current.conversationIdentities[chatId].isNullOrBlank()) return current
        val identity = preferredIdentity?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString()
        check(identity !in current.conversationIdentities.values) { "Another conversation owns this identity." }
        return current.copy(conversationIdentities = current.conversationIdentities + (chatId to identity))
    }

    fun save(current: AppFeatureSettings, chatId: Int, settings: ConversationCombinedSettings, expectedRevision: Long): AppFeatureSettings {
        require(chatId > 0 && settings.isSupported)
        val previous = current.conversationCombined[chatId] ?: ConversationCombinedSettings()
        check(previous.isSupported) { "These settings require a newer app version." }
        check(previous.revision == expectedRevision) { "Conversation settings changed. Reopen settings before saving." }
        check(current.combinedSettingsJournal[chatId]?.let { !it.roomSettled && it.phase != CombinedSettingsSavePhase.COMMITTED } != true) { "A conversation settings save needs recovery." }
        return current.copy(conversationCombined = current.conversationCombined + (chatId to settings.normalized().copy(revision = Math.addExact(previous.revision, 1))))
    }

    fun copy(current: AppFeatureSettings, sourceId: Int, targetId: Int): AppFeatureSettings {
        require(sourceId != targetId && targetId > 0)
        val source = current.conversationCombined[sourceId]?.normalized()
        if (targetId in current.conversationCombined) {
            check(current.conversationCombined[targetId] == source && current.conversationIdentities[targetId] != current.conversationIdentities[sourceId]) { "Destination already owns different Combined settings." }
            return current
        }
        return current.copy(
            conversationCombined = if (source != null) current.conversationCombined + (targetId to source) else current.conversationCombined,
            conversationIdentities = current.conversationIdentities + (targetId to (current.conversationIdentities[targetId] ?: UUID.randomUUID().toString()))
        )
    }

    fun adopt(current: AppFeatureSettings, sourceId: Int, targetId: Int, expectedIdentity: String): AppFeatureSettings {
        require(targetId > 0)
        if (sourceId == targetId || current.conversationIdentities[targetId] == expectedIdentity) return current
        check(current.conversationIdentities[sourceId] == expectedIdentity) { "The draft belongs to another conversation." }
        check(targetId !in current.conversationCombined && targetId !in current.conversationIdentities) { "Destination conversation already has settings." }
        check(current.combinedSettingsJournal[sourceId]?.roomSettled != false) { "Finish the settings save before adopting this identity." }
        val source = current.conversationCombined[sourceId]
        return current.copy(
            conversationCombined = (current.conversationCombined - sourceId).let { if (source == null) it else it + (targetId to source) },
            conversationIdentities = (current.conversationIdentities - sourceId) + (targetId to expectedIdentity),
            combinedSettingsJournal = current.combinedSettingsJournal - sourceId
        )
    }

    fun prepareDeletion(current: AppFeatureSettings, chatId: Int): AppFeatureSettings =
        current.copy(combinedSettingsDeletions = current.combinedSettingsDeletions + (chatId to current.conversationIdentities[chatId].orEmpty()))

    fun delete(current: AppFeatureSettings, chatId: Int): AppFeatureSettings {
        val expected = current.combinedSettingsDeletions[chatId]
        if (expected != null && current.conversationIdentities[chatId].orEmpty() != expected) return current.copy(combinedSettingsDeletions = current.combinedSettingsDeletions - chatId)
        val identity = current.conversationIdentities[chatId]
        return current.copy(
            conversationCombined = current.conversationCombined - chatId,
            conversationIdentities = current.conversationIdentities - chatId,
            deferredConversationCombined = if (identity == null) current.deferredConversationCombined else current.deferredConversationCombined - identity,
            combinedSettingsJournal = current.combinedSettingsJournal - chatId,
            combinedSettingsDeletions = current.combinedSettingsDeletions - chatId
        )
    }

    fun recoverDeletions(current: AppFeatureSettings, existingChatIds: Set<Int>): AppFeatureSettings =
        current.combinedSettingsDeletions.keys.fold(current) { state, id ->
            if (id in existingChatIds) state.copy(combinedSettingsDeletions = state.combinedSettingsDeletions - id) else delete(state, id)
        }
}
