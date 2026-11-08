package dev.chungjungsoo.gptmobile.data.database

import android.app.Application
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayConversationRecord
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayEngineeringBindingRecord
import dev.chungjungsoo.gptmobile.data.database.entity.GatewaySubmissionRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GatewayChatNavigationDaoTest {
    private lateinit var database: ChatDatabaseV2

    @Before fun open() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
    }

    @After fun close() {
        database.close()
    }

    @Test fun explicitAndRetainedGatewayChatsRemainVisibleBehindMoreThanTwoHundredOrdinaryChats() = runBlocking {
        val dao = database.chatRoomDao()
        for (number in 1..250) dao.addChatRoom(ChatRoomV2(title = "Ordinary $number", enabledPlatform = listOf("llama-candidate"), updatedAt = number.toLong() + 100))
        val explicit = dao.addChatRoom(ChatRoomV2(title = "Offline gateway", enabledPlatform = listOf("removed-profile"), updatedAt = 1, conversationMode = ConversationMode.GATEWAY)).toInt()
        val mirrored = dao.addChatRoom(ChatRoomV2(title = "Retained mirror", updatedAt = 2)).toInt()
        val engineering = dao.addChatRoom(ChatRoomV2(title = "Retained engineering", updatedAt = 3)).toInt()
        val submitted = dao.addChatRoom(ChatRoomV2(title = "Unconfirmed command", updatedAt = 4)).toInt()
        database.gatewayJobDao().saveConversation(record("mirror", mirrored))
        database.gatewayJobDao().saveBinding(GatewayEngineeringBindingRecord("request", engineering, "job", "{}", 1))
        database.gatewayJobDao().saveSubmission(GatewaySubmissionRecord("command", submitted, null, null, null, "original", "http://192.168.1.4:8090", "original", "fingerprint", "{}", createdAtMillis = 1))

        assertEquals(listOf(submitted, engineering, mirrored, explicit), dao.observeGatewayChats().first().map { it.id })
    }

    @Test fun archivedChatsStayOutOfTheListButTheirAttachedTranscriptCanStillBeOpened() = runBlocking {
        val id = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Archived gateway", conversationMode = ConversationMode.GATEWAY, isArchived = true)).toInt()
        database.gatewayJobDao().saveConversation(record("archived", id))
        database.gatewayJobDao().saveConversation(record("remote-only", null))
        database.gatewayJobDao().saveConversation(record("deleted-room", id + 100))
        assertTrue(database.chatRoomDao().observeGatewayChats().first().isEmpty())
        assertEquals(listOf("archived"), database.gatewayJobDao().observeAttachedConversations().first().map { it.key })
    }

    @Test fun workspaceProfileNormalizationCannotRemoveAnAdoptedGatewayMarker() = runBlocking {
        val id = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Original", enabledPlatform = listOf("profile"), draftText = "retained draft")).toInt()
        assertEquals(1, database.chatRoomDao().markGatewayChat(id))
        database.chatRoomDao().updateGitHubProfile(id, listOf("profile"), 123)
        val room = database.chatRoomDao().getChatRoomsByIds(listOf(id)).single()
        assertEquals(ConversationMode.GATEWAY, room.conversationMode)
        assertEquals("retained draft", room.draftText)
        assertEquals(listOf("profile"), room.activePlatform)
        assertEquals(listOf(id), database.chatRoomDao().observeGatewayChats().first().map { it.id })
    }

    private fun record(key: String, chatId: Int?) = GatewayConversationRecord(key, "gateway", "account", key, "original", "http://192.168.1.4:8090", "original", chatId, "{}")
}
