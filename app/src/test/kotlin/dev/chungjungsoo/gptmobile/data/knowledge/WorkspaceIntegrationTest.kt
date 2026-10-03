package dev.chungjungsoo.gptmobile.data.knowledge

import android.app.Application
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class WorkspaceIntegrationTest {
    private lateinit var database: ChatDatabaseV2

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
    }

    @After fun close() {
        database.close()
    }

    @Test fun projectDocumentsAreSearchableOnlyInsideTheWorkspaceAndDeletesUpdateFts() = runBlocking {
        val chats = database.chatRoomDao()
        val first = chats.addChatRoom(ChatRoomV2(title = "First")).toInt()
        val other = chats.addChatRoom(ChatRoomV2(title = "Other")).toInt()
        val project = KnowledgeProject("alpha", "Alpha")
        database.knowledgeDao().saveProject(project)
        database.knowledgeDao().attachChat(KnowledgeProjectChat(first, project.id))
        val scopes = MemoryScopeResolver(database)
        val repository = MemoryDocumentRepository(database, scopes, DocumentSearchIndex(database))
        val id = repository.index("Shared evidence", "Kotlin coroutines are used in this project.", projectId = project.id)
        assertTrue(repository.search("Kotlin", first).contains("Kotlin coroutines"))
        assertFalse(repository.search("Kotlin", other).contains("Kotlin coroutines"))
        repository.delete(id)
        assertFalse(repository.search("Kotlin", first).contains("Kotlin coroutines"))
        repository.index("Shared evidence", "Kotlin coroutines restored without permission", projectId = project.id)
        assertFalse(repository.search("Kotlin", first).contains("restored"))
    }

    @Test fun editingCreatesANewBranchWithoutRemovingLaterOriginalMessages() = runBlocking {
        val dao = database.agentPersistenceDao()
        val source = ChatRoomV2(title = "Original", enabledPlatform = listOf("p"))
        val chatId = dao.insertChatRoom(source).toInt()
        val first = MessageV2(chatId = chatId, content = "First", platformType = null, createdAt = 1)
        val firstId = dao.insertMessage(first).toInt()
        dao.insertMessage(MessageV2(chatId = chatId, content = "Answer", platformType = "p", linkedMessageId = firstId, createdAt = 2))
        dao.insertMessage(MessageV2(chatId = chatId, content = "Later original", platformType = null, createdAt = 3))
        val original = dao.getMessages(chatId)
        val branch = dao.duplicateChatWithHistory(chatId, "Branch", 4, first.copy(id = firstId, content = "Edited"))
        assertEquals(original, dao.getMessages(chatId))
        assertEquals(chatId, branch.parentChatId)
        assertEquals(firstId, branch.branchMessageId)
        val copied = dao.getMessages(branch.id)
        assertEquals(listOf("Edited", ""), copied.map { it.content })
        assertEquals(copied.first().id, copied.last().linkedMessageId)
        assertTrue(copied.all { it.id !in original.map { item -> item.id } })
    }

    @Test fun temporaryAndMissingConversationsNeverResolveToPersistentMemory() = runBlocking {
        val id = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Private", isTemporary = true)).toInt()
        val scopes = MemoryScopeResolver(database)
        assertTrue(scopes.resolve(id).isTemporary)
        assertTrue(scopes.resolve(999).isTemporary)
        val repository = MemoryDocumentRepository(database, scopes)
        assertTrue(runCatching { repository.index("Private", "Do not store", chatId = id) }.isFailure)
        assertEquals("", repository.context(id, "anything"))
    }

    @Test fun branchMemoryKeepsAlternativeLearningOutOfItsParentAndSibling() = runBlocking {
        val rooms = database.chatRoomDao()
        val parent = rooms.addChatRoom(ChatRoomV2(title = "Original")).toInt()
        val first = database.agentPersistenceDao().insertMessage(MessageV2(chatId = parent, content = "earlier", platformType = null)).toInt()
        val fork = database.agentPersistenceDao().insertMessage(MessageV2(chatId = parent, content = "different path", platformType = null)).toInt()
        val left = rooms.addChatRoom(ChatRoomV2(title = "Left", parentChatId = parent, branchMessageId = fork)).toInt()
        val right = rooms.addChatRoom(ChatRoomV2(title = "Right", parentChatId = parent, branchMessageId = fork)).toInt()
        val resolver = MemoryScopeResolver(database)
        val boundary = resolver.resolve(left)
        assertTrue(boundary.accepts("personal"))
        assertTrue(boundary.acceptsSource(dev.chungjungsoo.gptmobile.data.rag.VaultFact("earlier", dev.chungjungsoo.gptmobile.data.rag.MemoryLearning.observation("earlier"), sourceChatId = parent, sourceMessageId = first)))
        assertFalse(boundary.acceptsSource(dev.chungjungsoo.gptmobile.data.rag.VaultFact("later", dev.chungjungsoo.gptmobile.data.rag.MemoryLearning.observation("later"), sourceChatId = parent, sourceMessageId = fork)))
        assertFalse(resolver.resolve(parent).accepts(boundary.key))
        assertFalse(resolver.resolve(right).accepts(boundary.key))
    }
}
