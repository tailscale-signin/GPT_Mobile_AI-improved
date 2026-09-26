package dev.chungjungsoo.gptmobile.data.queue

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingPromptDao {
    @Insert
    suspend fun insert(prompt: PendingPrompt)

    @Query("SELECT COALESCE(MAX(position), 0) + 1 FROM pending_prompts")
    suspend fun nextPosition(): Long

    @Transaction
    suspend fun enqueue(prompt: PendingPrompt) = insert(prompt.copy(position = nextPosition()))

    @Query("SELECT * FROM pending_prompts WHERE userMessageId IS NULL ORDER BY position, id")
    fun observePending(): Flow<List<PendingPrompt>>

    @Query("SELECT * FROM pending_prompts WHERE id = :id")
    suspend fun get(id: String): PendingPrompt?

    @Query("DELETE FROM pending_prompts WHERE id = :id AND userMessageId IS NULL")
    suspend fun delete(id: String)

    @Query("UPDATE pending_prompts SET text = :text WHERE id = :id AND userMessageId IS NULL")
    suspend fun edit(id: String, text: String)

    @Query("UPDATE pending_prompts SET paused = :paused WHERE id = :id AND userMessageId IS NULL")
    suspend fun pause(id: String, paused: Boolean)

    @Query("UPDATE pending_prompts SET position = :position WHERE id = :id AND userMessageId IS NULL")
    suspend fun reposition(id: String, position: Long)

    @Transaction
    suspend fun swap(first: String, second: String) {
        val a = get(first) ?: return
        val b = get(second) ?: return
        require(a.chatId == b.chatId)
        reposition(first, b.position)
        reposition(second, a.position)
    }
}
