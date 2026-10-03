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
    val inheritedScopes: Set<String> = emptySet()
) {
    fun acceptsSource(fact: dev.chungjungsoo.gptmobile.data.rag.VaultFact): Boolean = fact.source == "manual" || ("${fact.sourceChatId}:${fact.sourceMessageId}" !in excludedSources && fact.evidenceSources.none { it in excludedSources })
    fun accepts(scope: String): Boolean = !isTemporary && (scope == key || scope in inheritedScopes || includePersonal && scope == "personal")
    fun accepts(document: KnowledgeDocument, chatId: Int): Boolean = !isTemporary &&
        !document.deleted &&
        (document.chatId == chatId || project != null && document.projectId == project.id)
}

/** One boundary shared by automatic recall, tools, documents and background learning. */
@Singleton
class MemoryScopeResolver @Inject constructor(private val database: ChatDatabaseV2) {
    suspend fun messageKeys(chatId: Int) = database.messageDao().loadMessages(chatId).map { "$chatId:${it.id}" }

    suspend fun resolve(chatId: Int): MemoryScope = withContext(Dispatchers.IO) {
        val chat = database.chatRoomDao().getChatRoomsByIds(listOf(chatId)).firstOrNull()
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
            inheritedScopes = inherited
        )
    }
}
