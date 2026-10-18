package dev.chungjungsoo.gptmobile.data.chat

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Singleton
class ConversationReadStateStore @Inject constructor(completions: GenerationCompletionStore) {
    val unreadChatIds = completions.items.map { rows -> rows.mapTo(mutableSetOf()) { it.chatId } }
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.WhileSubscribed(5000), emptySet())
    fun markUnread(chatId: Int) = Unit
    fun markViewed(chatId: Int) = Unit
    fun remove(chatId: Int) = Unit
}
