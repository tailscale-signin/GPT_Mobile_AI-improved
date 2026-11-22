package dev.chungjungsoo.gptmobile.data.knowledge

import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MemoryScope(
    val key: String = "personal",
    val includePersonal: Boolean = true,
    val project: KnowledgeProject? = null,
    val isTemporary: Boolean = false,
    val excludedSources: Set<String> = emptySet(),
    val inheritedScopes: Set<String> = emptySet(),
    val privateChatIds: Set<Int> = emptySet()
) {
    val privacyLocked: Boolean get() = MemoryPrivacyPolicy.isPrivate(key)

    fun acceptsSource(fact: dev.chungjungsoo.gptmobile.data.rag.VaultFact): Boolean {
        val privateSources = MemoryPrivacyPolicy.sourceChats(fact).intersect(privateChatIds)
        if (privateSources.isNotEmpty() || MemoryPrivacyPolicy.isPrivate(fact.scope)) {
            val owner = MemoryPrivacyPolicy.owner(key) ?: return false
            if (fact.scope != key || !fact.localOnly || privateSources.any { it != owner }) return false
            if (MemoryPrivacyPolicy.sourceChats(fact).any { it != owner }) return false
        }
        return fact.source == "manual" || ("${fact.sourceChatId}:${fact.sourceMessageId}" !in excludedSources && fact.evidenceSources.none { it in excludedSources })
    }

    fun accepts(scope: String): Boolean = !isTemporary &&
        if (privacyLocked || MemoryPrivacyPolicy.isPrivate(scope)) scope == key else (scope == key || scope in inheritedScopes || includePersonal && scope == "personal")

    fun accepts(document: KnowledgeDocument, chatId: Int): Boolean = !isTemporary &&
        !document.deleted &&
        (document.chatId !in privateChatIds || document.chatId == chatId) &&
        (document.chatId == chatId || !privacyLocked && project != null && document.projectId == project.id)
}

/** One boundary shared by automatic recall, tools, documents and background learning. */
@Singleton
class MemoryScopeResolver @Inject constructor(private val database: ChatDatabaseV2) {
    suspend fun sourceUnchanged(message: dev.chungjungsoo.gptmobile.data.database.entity.MessageV2): Boolean {
        val current = database.messageDao().message(message.id)
        return current?.chatId == message.chatId && current.content == message.content
    }

    suspend fun messageKeys(chatId: Int) = database.messageDao().loadMessages(chatId).map { "$chatId:${it.id}" }

    suspend fun privacyLockedChatIds(): Set<Int> = database.chatRoomDao().privacyLockedChatIds().toSet()

    suspend fun resolve(chatId: Int): MemoryScope = withContext(Dispatchers.IO) {
        val chat = database.chatRoomDao().getChatRoomsByIds(listOf(chatId)).firstOrNull()
        val privateChatIds = privacyLockedChatIds()
        if (chat?.privacyModeLocked == true) {
            return@withContext MemoryScope(
                key = MemoryPrivacyPolicy.scope(chatId),
                includePersonal = false,
                isTemporary = chat.isTemporary,
                privateChatIds = privateChatIds
            )
        }
        val project = database.knowledgeDao().projectForChat(chatId)
        val excluded = mutableSetOf<String>()
        val base = project?.let { "project:${it.id}" } ?: "personal"
        val inherited = mutableSetOf<String>()
        if (chat?.parentChatId != null) inherited += base
        var ancestor = chat
        val visited = mutableSetOf<Int>()
        repeat(32) {
            val parent = ancestor?.parentChatId ?: return@repeat
            if (!visited.add(parent)) return@repeat
            val point = ancestor?.branchMessageId ?: return@repeat
            // SQL returns only evidence IDs, never whole conversation bodies.
            database.openHelper.readableDatabase.query("SELECT message_id FROM messages_v2 WHERE chat_id = ? AND message_id >= ?", arrayOf(parent, point)).use { rows -> while (rows.moveToNext()) excluded += "$parent:${rows.getInt(0)}" }
            ancestor = database.chatRoomDao().getChatRoomsByIds(listOf(parent)).firstOrNull()
            if (ancestor?.parentChatId != null) inherited += "$base:branch:$parent"
        }
        MemoryScope(
            key = if (chat?.parentChatId != null) "$base:branch:$chatId" else base,
            includePersonal = project?.includePersonalMemory ?: true,
            project = project,
            isTemporary = chat == null || chat.isTemporary,
            excludedSources = excluded,
            inheritedScopes = inherited,
            privateChatIds = privateChatIds
        )
    }
}
