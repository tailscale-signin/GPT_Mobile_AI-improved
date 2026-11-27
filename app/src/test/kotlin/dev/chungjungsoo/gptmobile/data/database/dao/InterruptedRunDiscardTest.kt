package dev.chungjungsoo.gptmobile.data.database.dao

import android.app.Application
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class InterruptedRunDiscardTest {
    @Test fun discardingStoppedGenerationPreservesItsResponseAndMutationReceipt() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        try {
            db.chatRoomDao().addChatRoom(ChatRoomV2(id = 1, title = "Saved task"))
            db.messageDao().addMessages(MessageV2(id = 1, chatId = 1, content = "Continue my work", platformType = null))
            val reply = MessageV2(id = 2, chatId = 1, content = "Saved partial response", platformType = "profile", linkedMessageId = 1, currentRunId = "run", ownershipFence = 4)
            db.messageDao().addMessages(reply)
            db.agentRunDao().upsert(AgentRun("run", 1, 1, 2, "profile", "OPENAI", "original-model", status = "FAILED", terminalError = "NETWORK_TIMEOUT", ownershipFence = 4))
            val receipt = ToolEvent("effect", "run", 0, "call", null, null, "github_commit", "github_commit", "{}", "verified-receipt", "TEXT", "COMPLETED")
            db.agentPersistenceDao().insertToolEvent(receipt)
            assertEquals(0, db.agentRunDao().discardInterrupted("run", 3, 100))
            assertEquals(1, db.agentRunDao().discardInterrupted("run", 4, 100))
            assertEquals(0, db.agentRunDao().discardInterrupted("run", 4, 101))
            assertEquals("CANCELED", db.agentRunDao().getById("run")?.status)
            assertNull(db.agentRunDao().getById("run")?.terminalError)
            assertEquals(reply.content, db.agentPersistenceDao().recoveryMessage(2)?.content)
            assertEquals(receipt, db.agentPersistenceDao().getToolEvents(listOf("run")).single())
        } finally {
            db.close()
        }
    }

    @Test fun discardCannotCancelLiveRemoteCompletedOrSupersededOwners() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        try {
            db.chatRoomDao().addChatRoom(ChatRoomV2(id = 1, title = "Saved task"))
            db.messageDao().addMessages(MessageV2(id = 1, chatId = 1, content = "Continue my work", platformType = null))
            val scenarios = listOf("RUNNING" to null, "QUEUED" to null, "COMPLETED" to null, "INTERRUPTED" to "remote-job", "FAILED" to null)
            scenarios.forEachIndexed { index, (status, remoteJob) ->
                val id = "run-$index"
                val message = MessageV2(id = index + 2, chatId = 1, content = "Saved", platformType = "profile", linkedMessageId = 1, currentRunId = if (index == 4) "new-owner" else id, ownershipFence = 1)
                db.messageDao().addMessages(message)
                db.agentRunDao().upsert(AgentRun(id, 1, 1, message.id, "profile", "OPENAI", "model", status = status, gatewayJobId = remoteJob, ownershipFence = 1))
                assertEquals("$status $remoteJob", 0, db.agentRunDao().discardInterrupted(id, 1, 100))
                assertEquals(status, db.agentRunDao().getById(id)?.status)
            }
        } finally {
            db.close()
        }
    }
}
