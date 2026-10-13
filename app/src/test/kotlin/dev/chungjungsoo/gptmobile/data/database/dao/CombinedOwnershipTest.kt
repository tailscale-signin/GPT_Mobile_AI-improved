package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunDraft
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentRetryRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CombinedOwnershipTest {
    @Test
    fun ix08NewCorpusCannotBeOverwrittenByOldWriterAndDuplicateStartAttaches() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        try {
            val user = MessageV2(id = 2, chatId = 1, content = "Full task", platformType = null)
            val answer = MessageV2(id = 3, chatId = 1, content = "Original source", platformType = "primary", currentRunId = "source")
            db.chatRoomDao().addChatRoom(ChatRoomV2(id = 1, title = "Combined"))
            db.messageDao().addMessages(user, answer)
            db.agentRunDao().upsert(AgentRun("source", 1, 2, 3, "primary", "provider", "model", "COMPLETED"))
            val dao = db.agentPersistenceDao()
            val first = dao.persistAgentRetry(PersistAgentRetryRequest(user, answer, AgentRunDraft("combined-synthesis:a", "primary", "provider", "model", synthesisKey = "corpus-a", synthesisSnapshot = "full input")))
            assertEquals(1L, first.assistantMessage.ownershipFence)
            assertEquals(1, db.agentRunDao().markRunning(first.run.runId, 1))
            assertTrue(dao.checkpointRunningMessage(first.assistantMessage.copy(content = "Committed chapter")))
            val duplicate = dao.persistAgentRetry(PersistAgentRetryRequest(user, first.assistantMessage, AgentRunDraft("duplicate", "primary", "provider", "model", synthesisKey = "corpus-a")))
            assertEquals(first.run.runId, duplicate.run.runId)
            assertEquals("Committed chapter", duplicate.assistantMessage.content)
            assertEquals(1, db.agentRunDao().finishRunning(first.run.runId, "CANCELED", 2, null))
            val second = dao.persistAgentRetry(PersistAgentRetryRequest(user, duplicate.assistantMessage, AgentRunDraft("combined-synthesis:b", "primary", "provider", "model", synthesisKey = "corpus-b")))
            assertEquals(2L, second.assistantMessage.ownershipFence)
            assertEquals(1, db.agentRunDao().markRunning(second.run.runId, 3))
            assertFalse(dao.checkpointRunningMessage(first.assistantMessage.copy(content = "Late writer")))
            assertFalse(dao.finishRunningWithMessage(first.assistantMessage.copy(content = "Late terminal"), first.run.runId, "COMPLETED", 4, null))
            assertTrue(dao.checkpointRunningMessage(second.assistantMessage.copy(content = "New corpus chapter")))
            assertEquals("Committed chapter", dao.answerSegments(first.run.runId).joinToString("") { it.text })
            assertEquals("New corpus chapter", dao.answerSegments(second.run.runId).joinToString("") { it.text })
        } finally {
            db.close()
        }
    }
}
