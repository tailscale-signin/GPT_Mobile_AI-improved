package dev.chungjungsoo.gptmobile.data.database.dao

import android.app.Application
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
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
@Config(application = Application::class, sdk = [34])
class GitHubOutcomePersistenceTest {
    @Test fun partialBlockedAndMissingEvidencePublishDurablyAndRemainRecoverable() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        try {
            db.chatRoomDao().addChatRoom(ChatRoomV2(id = 1, title = "GitHub"))
            val user = MessageV2(id = 1, chatId = 1, content = "Implement and test", platformType = null)
            db.messageDao().addMessages(user)
            val dao = db.agentPersistenceDao()
            for ((index, status) in listOf(AgentRunStatus.PARTIAL, AgentRunStatus.EVIDENCE_MISSING, AgentRunStatus.BLOCKED).withIndex()) {
                val id = "run-$status"
                val message = MessageV2(id = index + 2, chatId = 1, content = "Verified work; validation remains.", platformType = "p", linkedMessageId = 1, currentRunId = id, ownershipFence = index + 3L)
                db.messageDao().addMessages(message)
                db.agentRunDao().upsert(AgentRun(id, 1, 1, message.id, "p", "API", "model", status = "RUNNING", startedAt = 1, ownershipFence = message.ownershipFence, displayRole = dev.chungjungsoo.gptmobile.data.chat.ReplyDisplayRole.DIRECT))
                assertTrue(dao.finishRunningWithMessage(message, id, status, 2, "GITHUB_TASK_INCOMPLETE"))
                assertEquals(status, db.agentRunDao().getById(id)?.status)
                assertTrue(dao.rawGetIncompleteRuns(1, listOf(1)).any { it.runId == id })
                assertEquals("PARTIAL", dao.publication("1:1:p")?.deliveryKind)
                assertFalse(dao.finishRunningWithMessage(message.copy(content = "Late stale write"), id, "COMPLETED", 3, null))
                assertEquals(message.content, dao.recoveryMessage(message.id)?.content)
            }
        } finally {
            db.close()
        }
    }
}
