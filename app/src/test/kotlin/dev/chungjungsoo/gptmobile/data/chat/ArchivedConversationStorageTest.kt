package dev.chungjungsoo.gptmobile.data.chat

import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArchivedConversationStorageTest {
    @Test fun archiveCompressionIsTransparentToReadsSearchBackupAndUnarchive() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        try {
            val chatId = db.chatRoomDao().addChatRoom(ChatRoomV2(title = "Archive test")).toInt()
            val content = "Distinctive research finding 😀\n".repeat(2000)
            val revision = "Previous evidence\n".repeat(2000)
            db.messageDao().addMessages(MessageV2(chatId = chatId, content = content, platformType = "profile", revisions = listOf(AssistantRevision(revision, createdAt = 1))))
            db.agentPersistenceDao().setArchivedWithCompression(chatId, true)
            val stored = db.messageDao().rawLoadMessages(chatId).single()
            assertTrue(stored.content.startsWith(ArchivedTextCodec.PREFIX))
            assertTrue(stored.content.length < content.length / 20)
            assertEquals(content, db.messageDao().loadMessages(chatId).single().content)
            assertEquals(revision, db.messageDao().getMessageList().single().revisions.single().content)
            assertEquals(listOf(chatId), db.messageDao().searchMessagesByContent("Distinctive research"))
            db.agentPersistenceDao().setArchivedWithCompression(chatId, false)
            assertEquals(content, db.messageDao().rawLoadMessages(chatId).single().content)
            assertTrue(db.chatRoomDao().getArchivedChatRooms().isEmpty())
            assertEquals(listOf(chatId), db.messageDao().searchMessagesByContent("Distinctive research"))
            assertEquals(listOf(chatId), db.messageDao().searchMessagesByContent("Distinctiv resear"))
        } finally {
            db.close()
        }
    }
}
