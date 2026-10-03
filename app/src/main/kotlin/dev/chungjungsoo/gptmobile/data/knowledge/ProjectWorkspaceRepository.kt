package dev.chungjungsoo.gptmobile.data.knowledge

import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatPlatformModelV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectWorkspaceRepository @Inject constructor(private val database: ChatDatabaseV2) {
    val projects = database.knowledgeDao().projects()
    val links = database.knowledgeDao().chatLinks()
    val profiles = database.platformDao().observePlatforms()
    suspend fun chats() = database.chatRoomDao().getChatRoomsWithFavorites().filterNot { it.isTemporary }

    suspend fun save(project: KnowledgeProject) {
        require(project.name.trim().length in 1..80) { "Use a project name of 1–80 characters." }
        require(project.instructions.length <= 12000) { "Project instructions are limited to 12,000 characters." }
        database.knowledgeDao().saveProject(project.copy(name = project.name.trim(), instructions = project.instructions.trim()))
    }

    suspend fun attach(chatId: Int, projectId: String?) {
        val chat = database.chatRoomDao().getChatRoomsByIds(listOf(chatId)).firstOrNull()
        require(chat != null && !chat.isTemporary) { "Temporary conversations cannot join projects." }
        if (projectId == null) database.knowledgeDao().detachChat(chatId) else database.knowledgeDao().attachChat(KnowledgeProjectChat(chatId, projectId))
    }

    suspend fun createChat(project: KnowledgeProject): Int = database.withTransaction {
        val profile = project.defaultProfileUid?.let { database.platformDao().getPlatformByUid(it) }
            ?: database.platformDao().getPlatforms().firstOrNull { it.enabled }
        require(profile != null && profile.enabled) { "Select an enabled default profile first." }
        val id = database.chatRoomDao().addChatRoom(ChatRoomV2(title = project.name, enabledPlatform = listOf(profile.uid))).toInt()
        database.chatPlatformModelDao().upsertChatPlatformModel(ChatPlatformModelV2(id, profile.uid, profile.model))
        database.knowledgeDao().attachChat(KnowledgeProjectChat(id, project.id))
        id
    }

    suspend fun delete(id: String) = database.knowledgeDao().deleteProject(id)

    companion object {
        fun draft() = KnowledgeProject(UUID.randomUUID().toString(), "")
    }
}
