package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GitHubProfilePersistenceTest {
    @Test fun repairingMembershipPersistsSingleModeWithoutOverwritingDraftsOrHistory() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        try {
            val dao = db.chatRoomDao()
            dao.addChatRoom(ChatRoomV2(id = 6, title = "Repository", enabledPlatform = listOf("local", "remote"), conversationMode = ConversationMode.COMBINED, combinedPrimaryUid = "remote"))
            val user = MessageV2(id = 1, chatId = 6, platformType = null, content = "Earlier request")
            val remote = MessageV2(id = 2, chatId = 6, platformType = "remote", content = "Earlier answer", linkedMessageId = 1)
            db.messageDao().addMessages(user, remote)
            dao.updateDraft(6, "New draft typed during repair", 123)
            dao.updateGitHubProfile(6, listOf("local"), 456)

            val restored = dao.getChatRoomsByIds(listOf(6)).single()
            assertEquals(listOf("local"), restored.enabledPlatform)
            assertEquals(listOf("local"), restored.activePlatform)
            assertEquals(ConversationMode.STANDARD, restored.conversationMode)
            assertNull(restored.combinedPrimaryUid)
            assertEquals("New draft typed during repair", restored.draftText)
            assertEquals(123L, restored.draftUpdatedAt)
            assertEquals(456L, restored.updatedAt)
            val count = db.openHelper.readableDatabase.query("SELECT count(*) FROM messages_v2 WHERE chat_id = 6").use {
                it.moveToFirst()
                it.getInt(0)
            }
            assertEquals(2, count)
        } finally {
            db.close()
        }
    }
}
