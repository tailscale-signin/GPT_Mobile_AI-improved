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
    suspend fun deleteUnconsumed(id: String)

    @Query("DELETE FROM pending_prompts WHERE id LIKE :prefix AND userMessageId IS NULL")
    suspend fun deleteRecipePrompts(prefix: String)

    @Query("UPDATE pending_prompts SET text = :text WHERE id = :id AND userMessageId IS NULL")
    suspend fun editUnconsumed(id: String, text: String)

    @Query("UPDATE pending_prompts SET paused = :paused WHERE id = :id AND userMessageId IS NULL")
    suspend fun pauseUnconsumed(id: String, paused: Boolean)

    @Transaction
    suspend fun delete(id: String) {
        if (FollowUpProgressStore.canChange(id)) deleteUnconsumed(id)
    }

    @Transaction
    suspend fun edit(id: String, text: String) {
        if (FollowUpProgressStore.canChange(id)) editUnconsumed(id, text)
    }

    @Transaction
    suspend fun pause(id: String, paused: Boolean) {
        if (FollowUpProgressStore.canChange(id)) pauseUnconsumed(id, paused)
    }

    @Query("UPDATE pending_prompts SET position = :position WHERE id = :id AND userMessageId IS NULL")
    suspend fun reposition(id: String, position: Long)

    @Query("SELECT * FROM pending_prompts WHERE chatId = :chatId AND userMessageId IS NULL ORDER BY position, id LIMIT 1")
    suspend fun firstPending(chatId: Int): PendingPrompt?

    @Query("SELECT COUNT(*) FROM agent_runs WHERE chat_id = :chatId AND status IN ('RUNNING', 'QUEUED')")
    suspend fun activeRunCount(chatId: Int): Int

    @Query("SELECT COUNT(*) FROM agent_runs WHERE run_id = :runId AND user_message_id = :messageId AND status = 'RUNNING'")
    suspend fun isRunningTurn(runId: String, messageId: Int): Int

    @Query("UPDATE messages_v2 SET content = content || :suffix WHERE message_id = :messageId AND chat_id = :chatId AND platform_type IS NULL")
    suspend fun appendFollowUp(messageId: Int, chatId: Int, suffix: String): Int

    @Query("UPDATE pending_prompts SET userMessageId = :messageId WHERE id = :id AND userMessageId IS NULL")
    suspend fun markFollowUpConsumed(id: String, messageId: Int): Int

    /** Atomic transfer: an accepted queued prompt is always retained in conversation history. */
    @Transaction
    suspend fun consumeFollowUp(chatId: Int, messageId: Int, runId: String, profileUid: String, model: String, maxCharacters: Int, tools: dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig = dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig()): String? {
        val pending = firstPending(chatId) ?: return null
        if (!canAcceptFollowUp(pending, messageId, runId, profileUid, model, maxCharacters, tools)) return null
        val suffix = "\n\nFollow-up from user:\n${pending.text}"
        check(appendFollowUp(messageId, chatId, suffix) == 1)
        check(markFollowUpConsumed(pending.id, messageId) == 1)
        return suffix
    }

    suspend fun canAcceptFollowUp(pending: PendingPrompt, messageId: Int, runId: String, profileUid: String, model: String, maxCharacters: Int, tools: dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig): Boolean {
        if (activeRunCount(pending.chatId) != 1 || isRunningTurn(runId, messageId) != 1) return false
        if (pending.paused || pending.text.isBlank() || pending.text.length > maxCharacters.coerceIn(0, 8000)) return false
        val payload = pending.details()
        return payload.tools == tools &&
            payload.attachments.isEmpty() &&
            !payload.localOnly &&
            !payload.requiresSpendAllowance &&
            payload.profileUids == listOf(profileUid) &&
            (payload.models[profileUid] == null || payload.models[profileUid] == model)
    }

    /** Compare the prepared snapshot inside the same transaction that transfers ownership. */
    @Transaction
    suspend fun acceptPreparedFollowUp(expected: PendingPrompt, messageId: Int, runId: String, profileUid: String, model: String, maxCharacters: Int, tools: dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig): Boolean {
        val current = firstPending(expected.chatId) ?: return false
        if (current != expected || !canAcceptFollowUp(current, messageId, runId, profileUid, model, maxCharacters, tools)) return false
        check(appendFollowUp(messageId, expected.chatId, "\n\nFollow-up from user:\n${expected.text}") == 1)
        check(markFollowUpConsumed(expected.id, messageId) == 1)
        return true
    }

    @Transaction
    suspend fun swap(first: String, second: String) {
        val a = get(first) ?: return
        val b = get(second) ?: return
        require(a.chatId == b.chatId)
        reposition(first, b.position)
        reposition(second, a.position)
    }
}
