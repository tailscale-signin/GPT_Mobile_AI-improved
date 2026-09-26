package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RunOutputLengthTest {
    @Test
    fun retriesOnlyEstimateTheCurrentAnswerAndReportedUsageSupersedesEstimates() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        try {
            db.chatRoomDao().addChatRoom(ChatRoomV2(id = 1, title = "Test"))
            val answer = MessageV2(id = 3, chatId = 1, content = "New reply", platformType = "profile", currentRunId = "new")
            db.messageDao().addMessages(MessageV2(id = 2, chatId = 1, content = "Question", platformType = null), answer)
            val old = AgentRun("old", 1, 2, 3, "profile", "provider", "model", AgentRunStatus.COMPLETED, createdAt = 1)
            db.agentRunDao().upsert(old)
            db.agentRunDao().upsert(old.copy(runId = "new", createdAt = 2))
            assertEquals(listOf(RunOutputLength("new", 9)), db.agentRunDao().observeUnreportedOutputLengths().first())
            db.agentRunDao().updateUsage("new", null, 0, null)
            assertEquals(emptyList<RunOutputLength>(), db.agentRunDao().observeUnreportedOutputLengths().first())
            db.agentRunDao().upsert(old.copy(runId = "new", status = AgentRunStatus.RUNNING))
            assertEquals(emptyList<RunOutputLength>(), db.agentRunDao().observeUnreportedOutputLengths().first())
        } finally {
            db.close()
        }
    }
}
