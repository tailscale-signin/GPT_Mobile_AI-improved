package dev.chungjungsoo.gptmobile.data.chat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class PinnedConversationOrderStore @Inject constructor(@param:ApplicationContext context: Context) {
    private val preferences by lazy { context.getSharedPreferences("pinned_conversation_order", Context.MODE_PRIVATE) }
    private val mutex = Mutex()
    private var loaded = false
    private val mutableOrder = MutableStateFlow<List<Int>>(emptyList())
    val order = mutableOrder.asStateFlow()

    suspend fun read(): List<Int> = withContext(Dispatchers.IO) { mutex.withLock { readLocked() } }

    suspend fun save(ids: List<Int>) {
        update { ids }
    }

    /** The persisted order is loaded before the first mutation, even if the UI is still hydrating. */
    suspend fun update(change: (List<Int>) -> List<Int>): List<Int> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = change(readLocked()).distinct()
            check(preferences.edit().putString("order", next.joinToString(",")).commit()) { "Could not save pinned conversation order." }
            mutableOrder.value = next
            next
        }
    }

    private fun readLocked(): List<Int> {
        if (!loaded) {
            mutableOrder.value = preferences.getString("order", "").orEmpty().split(',').mapNotNull(String::toIntOrNull).distinct()
            loaded = true
        }
        return mutableOrder.value
    }
}
