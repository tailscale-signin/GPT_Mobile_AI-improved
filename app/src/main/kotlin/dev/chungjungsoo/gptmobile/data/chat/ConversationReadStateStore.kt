package dev.chungjungsoo.gptmobile.data.chat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class ConversationReadStateStore @Inject constructor(
    @param:ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences("conversation_read_state_v1", Context.MODE_PRIVATE)
    private val _unreadChatIds = MutableStateFlow(
        preferences.getStringSet(KEY_UNREAD_IDS, emptySet())
            .orEmpty()
            .mapNotNull(String::toIntOrNull)
            .toSet()
    )
    val unreadChatIds = _unreadChatIds.asStateFlow()
    private val mutationLock = Any()

    fun markUnread(chatId: Int) {
        if (chatId <= 0) return
        synchronized(mutationLock) {
            val current = _unreadChatIds.value
            if (chatId in current) return
            _unreadChatIds.value = current + chatId
            persist()
        }
    }

    fun markViewed(chatId: Int) {
        if (chatId <= 0) return
        synchronized(mutationLock) {
            val current = _unreadChatIds.value
            if (chatId !in current) return
            _unreadChatIds.value = current - chatId
            persist()
        }
    }

    fun remove(chatId: Int) {
        markViewed(chatId)
    }

    private fun persist() {
        preferences.edit()
            .putStringSet(KEY_UNREAD_IDS, _unreadChatIds.value.mapTo(mutableSetOf(), Int::toString))
            .apply()
    }

    private companion object {
        const val KEY_UNREAD_IDS = "unread_chat_ids"
    }
}
