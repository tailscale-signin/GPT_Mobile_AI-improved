package dev.chungjungsoo.gptmobile.data.database.dao

import android.app.Application
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.voice.SpeakingReplyRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceTerminalReplyPersistenceTest {
    @Test fun committedTerminalReadUsesItsDurableTextInsteadOfTheOlderObserverPlaceholder() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        try {
            val run = seed(db)
            val dao = db.agentPersistenceDao()
            val speaking = dao.observeSpeakingReply(1, 3, run.runId, run.ownershipFence)
            assertEquals(SpeakingReplyRow("", AgentRunStatus.RUNNING), speaking.first())
            val oldMessage = requireNotNull(dao.rawRecoveryMessage(3))
            assertEquals("", oldMessage.content)
            assertNull(dao.terminalVoiceReply(1, 3, run.runId, 7))
            val final = oldMessage.copy(content = "The durable answer is complete.")
            assertTrue(dao.finishRunningWithMessage(final, run.runId, AgentRunStatus.COMPLETED, 10, null))
            val snapshot = requireNotNull(dao.terminalVoiceReply(1, 3, run.runId, 7))
            assertEquals(AgentRunStatus.COMPLETED, snapshot.run.status)
            assertEquals(final.content, snapshot.message.content)
            assertEquals(SpeakingReplyRow(final.content, AgentRunStatus.COMPLETED), speaking.first())
            assertEquals(snapshot.run.runId, snapshot.message.currentRunId)
            assertEquals(snapshot.run.ownershipFence, snapshot.message.ownershipFence)
            assertEquals("", oldMessage.content)
        } finally {
            db.close()
        }
    }

    @Test fun terminalReadRejectsForeignMissingAndSupersededOwnersWhilePreservingTrueEmptyFailure() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        try {
            val run = seed(db)
            val dao = db.agentPersistenceDao()
            val message = requireNotNull(dao.rawRecoveryMessage(3))
            assertTrue(dao.finishRunningWithMessage(message, run.runId, AgentRunStatus.FAILED, 10, "NETWORK_TIMEOUT"))
            val snapshot = requireNotNull(dao.terminalVoiceReply(1, 3, run.runId, 7))
            assertEquals("", snapshot.message.content)
            assertEquals(AgentRunStatus.FAILED, snapshot.run.status)
            assertEquals("NETWORK_TIMEOUT", snapshot.run.terminalError)
            assertNull(dao.terminalVoiceReply(2, 3, run.runId, 7))
            assertNull(dao.terminalVoiceReply(1, 2, run.runId, 7))
            assertNull(dao.terminalVoiceReply(1, 3, "missing", 7))
            assertNull(dao.terminalVoiceReply(1, 3, run.runId, 6))
            assertNull(dao.observeSpeakingReply(2, 3, run.runId, 7).first())
            assertNull(dao.observeSpeakingReply(1, 3, run.runId, 6).first())
            // Combined retains its original display slot when a different writer
            // owns the current run. Run/message/fence identity remains authoritative.
            db.agentRunDao().upsert(snapshot.run.copy(profileUid = "replacement-writer"))
            assertEquals("replacement-writer", requireNotNull(dao.terminalVoiceReply(1, 3, run.runId, 7)).run.profileUid)
            db.messageDao().editMessages(message.copy(currentRunId = "new-run", ownershipFence = 8))
            assertNull(dao.observeSpeakingReply(1, 3, run.runId, 7).first())
            assertNull(dao.terminalVoiceReply(1, 3, run.runId, 7))
            db.messageDao().editMessages(message.copy(ownershipFence = 8))
            assertNull(dao.terminalVoiceReply(1, 3, run.runId, 7))
        } finally {
            db.close()
        }
    }

    private suspend fun seed(db: ChatDatabaseV2): AgentRun {
        db.chatRoomDao().addChatRoom(ChatRoomV2(id = 1, title = "Voice", enabledPlatform = listOf("p"), activePlatform = listOf("p")))
        db.messageDao().addMessages(
            MessageV2(id = 2, chatId = 1, content = "Prompt", platformType = null),
            MessageV2(id = 3, chatId = 1, content = "", platformType = "p", linkedMessageId = 2, currentRunId = "voice-run", ownershipFence = 7)
        )
        return AgentRun("voice-run", 1, 2, 3, "p", "CUSTOM", "model", AgentRunStatus.RUNNING, ownershipFence = 7).also { db.agentRunDao().upsert(it) }
    }
}
