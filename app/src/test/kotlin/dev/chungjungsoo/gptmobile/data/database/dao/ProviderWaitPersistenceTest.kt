package dev.chungjungsoo.gptmobile.data.database.dao

import android.app.Application
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.agent.ProviderWait
import dev.chungjungsoo.gptmobile.data.agent.ProviderWaitStore
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.mockk
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
class ProviderWaitPersistenceTest {
    @Test fun onlyNeverDispatchedOwnersCanWaitAndCancellationPreventsClaim() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        try {
            db.chatRoomDao().addChatRoom(ChatRoomV2(id = 1, title = "Waiting"))
            db.messageDao().addMessages(MessageV2(id = 1, chatId = 1, content = "Question", platformType = null), MessageV2(id = 2, chatId = 1, content = "", platformType = "p", linkedMessageId = 1, currentRunId = "run", ownershipFence = 3))
            db.agentRunDao().upsert(AgentRun("run", 1, 1, 2, "p", "FREE", "model", ownershipFence = 3))
            val dao = db.agentPersistenceDao()
            val row = ProviderWait("run", 3, "scope", System.currentTimeMillis() + 60_000, "checkpoint")
            assertFalse(dao.deferProvider(row.copy(fence = 2)))
            assertTrue(dao.deferProvider(row))
            assertEquals("WAITING_PROVIDER_LIMIT", db.agentRunDao().getById("run")?.status)
            assertEquals(0, dao.claimProviderWait("run", 2))
            assertEquals(1, db.agentRunDao().finishActive("run", "CANCELED", 10, null))
            assertEquals(0, dao.claimProviderWait("run", 3))
            assertTrue(dao.providerWaits().isEmpty())
            db.agentRunDao().upsert(AgentRun("run", 1, 1, 2, "p", "FREE", "model", startedAt = 5, ownershipFence = 3))
            assertFalse(dao.deferProvider(row))
            db.agentRunDao().upsert(AgentRun("run", 1, 1, 2, "p", "FREE", "model", ownershipFence = 3))
            assertTrue(dao.deferProvider(row))
            assertEquals(1, dao.claimProviderWait("run", 3))
            // A Stop racing between worker claim and coordinator registration still
            // discovers the durable owner even though it is no longer WAITING.
            assertTrue(ProviderWaitStore(RuntimeEnvironment.getApplication(), db, mockk<SettingRepository>()).pendingIds(1).contains("run"))
            assertEquals(1, db.agentRunDao().finishActive("run", "CANCELED", 11, null))
        } finally {
            db.close()
        }
    }

    @Test fun malformedRestoredCheckpointStopsWithoutDispatchOrRepeatedWake() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, ChatDatabaseV2::class.java).build()
        try {
            db.chatRoomDao().addChatRoom(ChatRoomV2(id = 1, title = "Waiting"))
            db.messageDao().addMessages(MessageV2(id = 1, chatId = 1, content = "Question", platformType = null), MessageV2(id = 2, chatId = 1, content = "", platformType = "p", currentRunId = "run", ownershipFence = 3))
            db.agentRunDao().upsert(AgentRun("run", 1, 1, 2, "p", "FREE", "model", ownershipFence = 3))
            assertTrue(db.agentPersistenceDao().deferProvider(ProviderWait("run", 3, "scope", 1, "invalid-json")))
            assertTrue(ProviderWaitStore(context, db, mockk<SettingRepository>()).ready("scope").isEmpty())
            assertEquals("INTERRUPTED", db.agentRunDao().getById("run")?.status)
            assertEquals("PROVIDER_WAIT_CHECKPOINT_INVALID", db.agentRunDao().getById("run")?.terminalError)
            assertTrue(db.agentPersistenceDao().providerWaits().isEmpty())
        } finally {
            db.close()
        }
    }
}
