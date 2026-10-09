package dev.chungjungsoo.gptmobile.data.chat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationFolderInstrumentedTest {
    @Test fun folderMembershipSurvivesReopenAndDeletionKeepsChat() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "folder-test-${UUID.randomUUID()}"
        fun open() = Room.databaseBuilder(context, ChatDatabaseV2::class.java, name).build()
        var database = open()
        try {
            val chatId = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "A conversation")).toInt()
            val repository = ConversationFolderRepository(database)
            val folderId = repository.create(chatId, "Research", ConversationFolderStyle.colors.first())
            database.close()
            database = open()
            assertEquals("Research", database.conversationFolderDao().folders().single().name)
            assertEquals(folderId, database.conversationFolderDao().members().single().folderId)
            ConversationFolderRepository(database).edit(folderId, "Ideas", ConversationFolderStyle.colors.last())
            assertEquals("Ideas", database.conversationFolderDao().folders().single().name)
            ConversationFolderRepository(database).delete(folderId)
            assertTrue(database.conversationFolderDao().members().isEmpty())
            assertEquals(chatId, database.chatRoomDao().getChatRooms().single().id)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
