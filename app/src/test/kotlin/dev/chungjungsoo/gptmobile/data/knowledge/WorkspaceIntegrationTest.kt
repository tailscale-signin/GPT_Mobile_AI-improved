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

    @Test fun privateMemoryCaptureEnrichmentAndEditsNeverBecomeOrdinaryRecall() = runBlocking {
        val privateChat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Private", privacyModeLocked = true)).toInt()
        val ordinaryChat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Ordinary")).toInt()
        val scopes = MemoryScopeResolver(database)
        val storage = PrivateMemoryVault()
        val repository = dev.chungjungsoo.gptmobile.data.rag.FactVaultRepository(storage, dev.chungjungsoo.gptmobile.data.rag.KnowledgeGraphEngine(), scopes = scopes)
        repository.setEnabled(true)
        repository.prepareTurn("I prefer Kotlin", privateChat, 1, isLocal = true)
        val message = MessageV2(chatId = privateChat, content = "I own a MacBook", platformType = null)
        repository.rememberUserText("MacBook", message)
        repository.rememberGraphFact("User", "PERSON", "OWNS", "MacBook", "DEVICE", message)
        val enrichment = MessageV2(chatId = privateChat, content = "I prefer Jasmine tea", platformType = null)
        val enrichmentId = database.agentPersistenceDao().insertMessage(enrichment).toInt()
        repository.enrichTurn(enrichment.copy(id = enrichmentId), completeSemantic = false) { dev.chungjungsoo.gptmobile.data.memory.MemoryEnrichmentContract.localFallback(it) }
        val captured = repository.state.value.facts
        assertTrue(captured.isNotEmpty())
        assertTrue(captured.all { it.scope == MemoryPrivacyPolicy.scope(privateChat) && it.localOnly })
        assertTrue(captured.any { it.source == "local_extractor_observation" })
        assertTrue(repository.visibleFacts(ordinaryChat, isLocal = true, includeLocalOnly = true).isEmpty())
        assertTrue(repository.visibleFacts(ordinaryChat, isLocal = false, includeLocalOnly = true).isEmpty())
        assertTrue(repository.visibleFacts(privateChat, isLocal = true, includeLocalOnly = true).isNotEmpty())
        assertTrue(repository.visibleFacts(privateChat, isLocal = false, includeLocalOnly = true).isEmpty())

        repository.saveManual("Edited private memory", captured.first().id, localOnly = false)
        val edited = repository.state.value.facts.first { it.id == captured.first().id }
        assertTrue(edited.localOnly)
        repository.restructure(setOf(edited.id), listOf("Retained private replacement"))
        val restored = dev.chungjungsoo.gptmobile.data.rag.FactVaultRepository(storage, dev.chungjungsoo.gptmobile.data.rag.KnowledgeGraphEngine(), scopes = scopes)
        restored.load()
        assertTrue(restored.state.value.facts.all { it.scope == MemoryPrivacyPolicy.scope(privateChat) && it.localOnly })
        assertTrue(restored.visibleFacts(ordinaryChat, isLocal = true, includeLocalOnly = true).isEmpty())
    }

    @Test fun privateDocumentsCannotBeSharedOrRecalledThroughRetainedProjectOwnership() = runBlocking {
        val privateChat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Private", privacyModeLocked = true)).toInt()
        val ordinaryChat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Ordinary")).toInt()
        val project = KnowledgeProject("shared", "Shared", includePersonalMemory = true)
        database.knowledgeDao().saveProject(project)
        database.knowledgeDao().attachChat(KnowledgeProjectChat(privateChat, project.id))
        database.knowledgeDao().attachChat(KnowledgeProjectChat(ordinaryChat, project.id))
        val scopes = MemoryScopeResolver(database)
        val repository = MemoryDocumentRepository(database, scopes)
        val id = repository.index("Private attachment", "Private project evidence", privateChat, project.id)
        val original = requireNotNull(repository.dao.document(id))
        assertEquals(null, original.projectId)
        assertTrue(runCatching { repository.shareWithProject(id, project.id) }.isFailure)
        repository.dao.replaceDocument(original.copy(projectId = project.id), repository.dao.chunks(id), explicitlyRestore = true)
        assertTrue(repository.recall(ordinaryChat, "evidence").sources.isEmpty())
        assertTrue(repository.recall(privateChat, "evidence").sources.isNotEmpty())
        val copied = database.agentPersistenceDao().duplicateChatWithHistory(privateChat, "Private copy", 5)
        val copiedScope = scopes.resolve(copied.id)
        assertTrue(copiedScope.privacyLocked)
        assertFalse(copiedScope.accepts(MemoryPrivacyPolicy.scope(privateChat)))
        assertFalse(copiedScope.accepts("personal"))
        assertFalse(copiedScope.accepts("project:${project.id}"))
    }

    @Test fun retainedPrivateProvenanceSurvivesForgettingItsSourceAndDeletingTheRoom() = runBlocking {
        val privateRoom = ChatRoomV2(title = "Private", privacyModeLocked = true)
        val privateChat = database.chatRoomDao().addChatRoom(privateRoom).toInt()
        val ordinaryChat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Ordinary")).toInt()
        val storage = PrivateMemoryVault()
        val old = dev.chungjungsoo.gptmobile.data.rag.VaultFact(
            "legacy-private",
            dev.chungjungsoo.gptmobile.data.rag.MemoryLearning.observation("Retained private source"),
            sourceChatId = privateChat,
            sourceMessageId = 1,
            source = "manual",
            evidenceSources = setOf("$privateChat:1", "$ordinaryChat:2")
        )
        storage.put(dev.chungjungsoo.gptmobile.data.rag.FactVaultRepository.VAULT_REFERENCE, kotlinx.serialization.json.Json.encodeToString(dev.chungjungsoo.gptmobile.data.rag.FactVaultSnapshot.serializer(), dev.chungjungsoo.gptmobile.data.rag.FactVaultSnapshot(enabled = true, facts = listOf(old))).encodeToByteArray())
        val scopes = MemoryScopeResolver(database)
        val repository = dev.chungjungsoo.gptmobile.data.rag.FactVaultRepository(storage, dev.chungjungsoo.gptmobile.data.rag.KnowledgeGraphEngine(), scopes = scopes)
        repository.load()
        assertTrue(repository.state.value.facts.single().localOnly)
        repository.forgetChat(privateChat, preventFutureCapture = true)
        database.chatRoomDao().deleteChatRooms(privateRoom.copy(id = privateChat))
        val reopened = dev.chungjungsoo.gptmobile.data.rag.FactVaultRepository(storage, dev.chungjungsoo.gptmobile.data.rag.KnowledgeGraphEngine(), scopes = scopes)
        reopened.load()
        assertTrue(reopened.state.value.facts.all { it.localOnly && MemoryPrivacyPolicy.isPrivate(it.scope) })
        assertTrue(reopened.visibleFacts(ordinaryChat, isLocal = true, includeLocalOnly = true).isEmpty())
        assertTrue(reopened.visibleFacts(ordinaryChat, isLocal = false, includeLocalOnly = true).isEmpty())
    }

    private class PrivateMemoryVault : dev.chungjungsoo.gptmobile.data.security.SecretVault {
        private val values = mutableMapOf<String, ByteArray>()
        override suspend fun put(secretRef: String, secret: ByteArray) {
            values[secretRef] = secret.copyOf()
        }
        override suspend fun read(secretRef: String): ByteArray? = values[secretRef]?.copyOf()
        override suspend fun delete(secretRef: String) {
            values.remove(secretRef)
        }
    }

    @Test fun removedDefaultCannotSilentlySelectAnotherProvider() = runBlocking {
        val profile = dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2(name = "Other", model = "other")
        database.platformDao().addPlatform(profile)
        val repository = ProjectWorkspaceRepository(database)
        val project = KnowledgeProject("isolated", "Isolated", defaultProfileUid = "removed")
        repository.save(project)
        assertTrue(runCatching { repository.createChat(project) }.isFailure)
        assertTrue(repository.chats().isEmpty())
        repository.save(project.copy(defaultProfileUid = profile.uid))
        val id = repository.createChat(project)
        assertEquals(project.id, database.knowledgeDao().projectForChat(id)?.id)
    }

    @Test fun preparedDocumentReceiptRejectsDeletedOrRevisedSource() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Recall")).toInt()
        val repository = MemoryDocumentRepository(database, MemoryScopeResolver(database))
        val id = repository.index("Source", "Original document evidence.", chatId = chat)
        val original = repository.recall(chat, "document")
        assertTrue(original.sources.isNotEmpty())
        repository.validateRecall(original)
        repository.index("Source", "Revised document evidence.", chatId = chat)
        assertTrue(runCatching { repository.validateRecall(original) }.isFailure)
        val revised = repository.recall(chat, "document")
        repository.validateRecall(revised)
        repository.delete(id)
        assertTrue(runCatching { repository.validateRecall(revised) }.isFailure)
        // Reimporting identical bytes with an old/equal clock cannot revive the receipt.
        val oldSource = revised.sources.single()
        database.knowledgeDao().replaceDocument(oldSource.document, listOf(oldSource.chunk), explicitlyRestore = true)
        assertTrue(requireNotNull(database.knowledgeDao().document(id)).updatedAt > oldSource.document.updatedAt)
        assertTrue(runCatching { repository.validateRecall(revised) }.isFailure)
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

    @Test fun copiesAndEditedBranchesKeepProjectMemoryAndItsPersonalOptOut() = runBlocking {
        val dao = database.agentPersistenceDao()
        val source = dao.insertChatRoom(ChatRoomV2(title = "Private project")).toInt()
        val prompt = MessageV2(chatId = source, content = "Question", platformType = null, createdAt = 1)
        val promptId = dao.insertMessage(prompt).toInt()
        val project = KnowledgeProject("private", "Research", instructions = "Use project sources only")
        database.knowledgeDao().saveProject(project)
        database.knowledgeDao().attachChat(KnowledgeProjectChat(source, project.id))
        val copies = listOf(
            dao.duplicateChatWithHistory(source, "Copy", 2),
            dao.duplicateChatWithHistory(source, "Edit", 3, prompt.copy(id = promptId, content = "Revised"))
        )
        val scopes = MemoryScopeResolver(database)
        for (copy in copies) {
            val scope = scopes.resolve(copy.id)
            assertEquals(project, scope.project)
            assertFalse(scope.accepts("personal"))
            assertTrue(scope.accepts("project:private"))
        }
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

    @Test fun evidenceFreeRetryCreatesAnUnlinkedScopeAndPreservesTheOriginal() = runBlocking {
        val dao = database.agentPersistenceDao()
        val source = dao.insertChatRoom(ChatRoomV2(title = "Original", enabledPlatform = listOf("p", "q"), isTemporary = true)).toInt()
        dao.insertMessage(MessageV2(chatId = source, content = "Original question", platformType = null))
        dao.insertMessage(MessageV2(chatId = source, content = "Protected evidence", platformType = "p"))
        val before = dao.getMessages(source)
        val fresh = dao.createEvidenceFreeRetry(source, "Original question", "p", "chosen-model", 123)
        assertTrue(fresh.id != source)
        assertTrue(fresh.isTemporary)
        assertEquals(null, fresh.parentChatId)
        assertEquals(listOf("p"), fresh.enabledPlatform)
        assertEquals(before, dao.getMessages(source))
        val messages = dao.getMessages(fresh.id)
        assertEquals(listOf("Original question", ""), messages.map { it.content })
        assertEquals(messages.first().id, messages.last().linkedMessageId)
        assertTrue(messages.all { it.attachments.isEmpty() && it.timeline.isEmpty() && it.currentRunId == null })
        assertEquals("chosen-model", dao.getModels(fresh.id).single().model)
    }
}
