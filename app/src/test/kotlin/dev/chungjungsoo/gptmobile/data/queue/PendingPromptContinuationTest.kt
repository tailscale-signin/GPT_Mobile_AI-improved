package dev.chungjungsoo.gptmobile.data.queue

import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingPromptContinuationTest {
    @Test fun approvedContinuationIsEnqueuedOnceAndItsConsumedFenceSurvivesRecreation() = runBlocking {
        val saved = linkedMapOf<String, PendingPrompt>()
        val prompt = PendingPrompt("approved-commit", 7, "Continue saved work", "original profile and model", 0)
        val first = MemoryDao(saved)
        assertTrue(first.enqueueOnce(prompt))
        assertFalse(first.enqueueOnce(prompt))
        saved[prompt.id] = saved.getValue(prompt.id).copy(userMessageId = 99)
        val restored = MemoryDao(saved)
        assertFalse(restored.enqueueOnce(prompt))
        assertEquals(1, saved.size)
        assertEquals(99, saved.getValue(prompt.id).userMessageId)
    }

    @Test fun repeatedCallbackPreservesTheFirstAcceptedContextAndRejectsAnotherIntent() = runBlocking {
        val saved = linkedMapOf<String, PendingPrompt>()
        val dao = MemoryDao(saved)
        val original = PendingPrompt("approved-commit", 7, "Continue saved work", "original profile and model", 0)
        assertTrue(dao.enqueueOnce(original))
        assertFalse(dao.enqueueOnce(original.copy(payload = "new UI observation")))
        assertEquals(original.payload, saved.getValue(original.id).payload)
        assertTrue(runCatching { dao.enqueueOnce(original.copy(text = "Another task")) }.isFailure)
        assertTrue(runCatching { dao.enqueueOnce(original.copy(chatId = 8)) }.isFailure)
    }

    @Test fun historicalWorkspaceRequiresOneAcceptedPayloadForTheExactMessage() = runBlocking {
        val rows = linkedMapOf<String, PendingPrompt>()
        val dao = MemoryDao(rows)
        val scope = GitHubConversationContext("c", "owner", "repo", "feature", "a".repeat(40), "profile")
        val message = MessageV2(id = 99, chatId = 7, content = "Show PR #41", platformType = null)
        val payload = PendingPromptPayload(profileUids = listOf("profile"), tools = ChatMcpToolConfig(github = scope))
        val row = PendingPrompt("read", 7, message.content, Json.encodeToString(payload), 1, userMessageId = 99)
        rows[row.id] = row
        assertEquals(scope, dao.savedGitHubWorkspace(message))
        assertEquals(null, dao.savedGitHubWorkspace(message.copy(content = "Show PR #42")))
        assertEquals(null, dao.savedGitHubWorkspace(message.copy(chatId = 8)))
        rows["steering"] = row.copy(id = "steering")
        assertEquals(null, dao.savedGitHubWorkspace(message))
        rows.remove("steering")
        rows[row.id] = row.copy(payload = Json.encodeToString(payload.copy(profileUids = listOf("other"))))
        assertEquals(null, dao.savedGitHubWorkspace(message))
    }

    private class MemoryDao(private val rows: MutableMap<String, PendingPrompt>) : PendingPromptDao {
        override suspend fun insert(prompt: PendingPrompt) {
            check(rows.putIfAbsent(prompt.id, prompt) == null)
        }
        override suspend fun get(id: String) = rows[id]
        override suspend fun nextPosition() = (rows.values.maxOfOrNull { it.position } ?: 0L) + 1
        override suspend fun acceptedForMessage(chatId: Int, messageId: Int) = rows.values.filter { it.chatId == chatId && it.userMessageId == messageId }.take(2)
        override suspend fun forChat(chatId: Int) = rows.values.filter { it.chatId == chatId }
        override fun observePending(): Flow<List<PendingPrompt>> = flowOf(rows.values.filter { it.userMessageId == null })
        override suspend fun deleteUnconsumed(id: String) {
            error("Not used")
        }
        override suspend fun deleteRecipePrompts(prefix: String) {
            error("Not used")
        }
        override suspend fun editUnconsumed(id: String, text: String) {
            error("Not used")
        }
        override suspend fun replaceUnconsumed(id: String, expectedText: String, expectedPayload: String, text: String, payload: String): Int = error("Not used")
        override suspend fun pauseUnconsumed(id: String, paused: Boolean) {
            error("Not used")
        }
        override suspend fun reposition(id: String, position: Long) {
            error("Not used")
        }
        override suspend fun firstPending(chatId: Int): PendingPrompt? = error("Not used")
        override suspend fun activeRunCount(chatId: Int): Int = error("Not used")
        override suspend fun isRunningTurn(runId: String, messageId: Int): Int = error("Not used")
        override suspend fun appendFollowUp(messageId: Int, chatId: Int, suffix: String): Int = error("Not used")
        override suspend fun markFollowUpConsumed(id: String, messageId: Int): Int = error("Not used")
    }
}
