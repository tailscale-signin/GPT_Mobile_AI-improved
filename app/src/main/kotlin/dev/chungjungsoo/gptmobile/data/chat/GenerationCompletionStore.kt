package dev.chungjungsoo.gptmobile.data.chat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CompletedGeneration(
    val runId: String,
    val chatId: Int,
    val assistantMessageId: Int,
    val completedAt: Long
)

/**
 * Small persisted inbox for assistant responses that completed away from the conversation.
 * One item is retained per run so multiple AIs finishing in the same chat keep separate targets.
 */
@Singleton
class GenerationCompletionStore @Inject constructor(
    @param:ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    val items = _items.asStateFlow()

    fun record(runId: String, chatId: Int, assistantMessageId: Int, completedAt: Long) {
        if (runId.isBlank() || chatId <= 0 || assistantMessageId <= 0) return
        _items.update { current ->
            listOf(CompletedGeneration(runId, chatId, assistantMessageId, completedAt))
                .plus(current.filterNot { it.runId == runId })
                .sortedByDescending { it.completedAt }
                .take(MAX_ITEMS)
        }
        persist()
    }

    fun consume(runId: String) {
        _items.update { current -> current.filterNot { it.runId == runId } }
        persist()
    }

    fun clearChat(chatId: Int) {
        if (chatId <= 0) return
        _items.update { current -> current.filterNot { it.chatId == chatId } }
        persist()
    }

    fun removeMissingChats(validChatIds: Set<Int>) {
        _items.update { current -> current.filter { it.chatId in validChatIds } }
        persist()
    }

    private fun load(): List<CompletedGeneration> =
        preferences.getStringSet(KEY_ITEMS, emptySet()).orEmpty()
            .mapNotNull(::decode)
            .sortedByDescending { it.completedAt }
            .take(MAX_ITEMS)

    private fun persist() {
        check(
            preferences.edit()
                .putStringSet(KEY_ITEMS, _items.value.mapTo(mutableSetOf(), ::encode))
                .commit()
        ) { "Could not persist completed-generation inbox." }
    }

    private fun encode(item: CompletedGeneration): String =
        listOf(item.runId, item.chatId, item.assistantMessageId, item.completedAt).joinToString("|")

    private fun decode(raw: String): CompletedGeneration? {
        val parts = raw.split('|')
        if (parts.size != 4) return null
        val runId = parts[0].takeIf(String::isNotBlank) ?: return null
        return CompletedGeneration(
            runId = runId,
            chatId = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null,
            assistantMessageId = parts[2].toIntOrNull()?.takeIf { it > 0 } ?: return null,
            completedAt = parts[3].toLongOrNull() ?: return null
        )
    }

    private companion object {
        const val PREFS = "generation_completion_inbox_v1"
        const val KEY_ITEMS = "items"
        const val MAX_ITEMS = 32
    }
}
