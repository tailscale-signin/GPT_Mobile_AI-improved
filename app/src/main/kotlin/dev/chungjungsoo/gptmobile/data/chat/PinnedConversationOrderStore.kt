package dev.chungjungsoo.gptmobile.data.chat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PinnedConversationOrderStore @Inject constructor(@param:ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("pinned_conversation_order", Context.MODE_PRIVATE)
    fun read(): List<Int> = preferences.getString("order", "").orEmpty().split(',').mapNotNull(String::toIntOrNull).distinct()
    fun save(ids: List<Int>) {
        preferences.edit().putString("order", ids.distinct().joinToString(",")).apply()
    }
}
