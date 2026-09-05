package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.database.dao.ChatRoomV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.MessageV2Dao
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.model.ModelRoutingRule
import dev.chungjungsoo.gptmobile.data.model.PlatformType
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

class ChatRepositoryImpl @Inject constructor(
    private val chatRoomV2Dao: ChatRoomV2Dao,
    private val messageV2Dao: MessageV2Dao
) : ChatRepository {

    override fun getChatRooms(): Flow<List<ChatRoomV2>> = chatRoomV2Dao.getChatRooms()

    override suspend fun getChatRoom(chatRoomId: Int): ChatRoomV2? = chatRoomV2Dao.getChatRoom(chatRoomId)

    override suspend fun createChatRoom(title: String, platformType: PlatformType, model: String): Long {
        val chatRoom = ChatRoomV2(
            title = title,
            platformType = platformType,
            model = model,
            createdAt = Date()
        )
        return chatRoomV2Dao.insertChatRoom(chatRoom)
    }

    override suspend fun updateChatRoomTitle(chatRoomId: Int, title: String) {
        val room = chatRoomV2Dao.getChatRoom(chatRoomId) ?: return
        chatRoomV2Dao.updateChatRoom(room.copy(title = title))
    }

    override suspend fun updateChatRoomModel(chatRoomId: Int, platformType: PlatformType, model: String) {
        val room = chatRoomV2Dao.getChatRoom(chatRoomId) ?: return
        chatRoomV2Dao.updateChatRoom(room.copy(platformType = platformType, model = model))
    }

    override suspend fun deleteChatRoom(chatRoomId: Int) {
        chatRoomV2Dao.deleteChatRoom(chatRoomId)
    }

    override suspend fun pinChatRoom(chatRoomId: Int, isPinned: Boolean) {
        chatRoomV2Dao.updatePin(chatRoomId, isPinned)
    }

    override suspend fun updateChatRoomCategory(chatRoomId: Int, category: String?) {
        chatRoomV2Dao.updateCategory(chatRoomId, category)
    }

    override suspend fun getCategories(): List<String> {
        return chatRoomV2Dao.getCategories()
    }

    override suspend fun updateModelRoutingRules(chatRoomId: Int, rules: List<ModelRoutingRule>) {
        chatRoomV2Dao.updateModelRoutingRules(chatRoomId, rules)
    }

    override suspend fun archiveChatRoom(chatRoomId: Int, isArchived: Boolean) {
        chatRoomV2Dao.updateArchived(chatRoomId, isArchived)
    }

    override fun getArchivedChatRooms(): Flow<List<ChatRoomV2>> {
        return chatRoomV2Dao.getArchivedChatRooms()
    }

    override suspend fun updateExportFormat(chatRoomId: Int, exportFormat: String?) {
        chatRoomV2Dao.updateExportFormat(chatRoomId, exportFormat)
    }

    override suspend fun favoriteChatRoom(chatRoomId: Int, isFavorite: Boolean) {
        chatRoomV2Dao.updateFavorite(chatRoomId, isFavorite)
    }

    override fun getFavoriteChatRooms(): Flow<List<ChatRoomV2>> {
        return chatRoomV2Dao.getFavoriteChatRooms()
    }

    override fun getMessages(chatRoomId: Int): Flow<List<MessageV2>> = messageV2Dao.getMessages(chatRoomId)

    override suspend fun getMessagesSync(chatRoomId: Int): List<MessageV2> = messageV2Dao.getMessagesSync(chatRoomId)

    override suspend fun getMessage(messageId: Int): MessageV2? = messageV2Dao.getMessage(messageId)

    override suspend fun addMessage(
        chatRoomId: Int,
        content: String,
        platformType: PlatformType?,
        model: String?,
        imagePaths: List<String>,
        audioPath: String?,
        fileAttachments: List<String>,
        revisions: List<String>,
        currentRevisionIndex: Int
    ): Long {
        val initialRevisions = revisions.ifEmpty { listOf(content) }
        val message = MessageV2(
            chatRoomId = chatRoomId,
            platformType = platformType,
            model = model,
            content = content,
            imagePaths = imagePaths,
            audioPath = audioPath,
            fileAttachments = fileAttachments,
            revisions = initialRevisions,
            currentRevisionIndex = currentRevisionIndex,
            createdAt = Date()
        )
        return messageV2Dao.insertMessage(message)
    }

    override suspend fun updateMessage(message: MessageV2) {
        messageV2Dao.updateMessage(message)
    }

    override suspend fun updateMessageContent(messageId: Int, content: String) {
        val msg = messageV2Dao.getMessage(messageId) ?: return
        val updatedRevisions = if (msg.revisions.isEmpty()) listOf(msg.content, content) else msg.revisions + content
        val updated = msg.copy(
            content = content,
            revisions = updatedRevisions,
            currentRevisionIndex = updatedRevisions.size - 1
        )
        messageV2Dao.updateMessage(updated)
    }

    override suspend fun addMessageRevision(messageId: Int, newContent: String) {
        val msg = messageV2Dao.getMessage(messageId) ?: return
        val currentRevs = if (msg.revisions.isEmpty()) listOf(msg.content) else msg.revisions
        val updatedRevisions = currentRevs + newContent
        val updated = msg.copy(
            content = newContent,
            revisions = updatedRevisions,
            currentRevisionIndex = updatedRevisions.size - 1
        )
        messageV2Dao.updateMessage(updated)
    }

    override suspend fun selectMessageRevision(messageId: Int, revisionIndex: Int) {
        val msg = messageV2Dao.getMessage(messageId) ?: return
        if (revisionIndex in msg.revisions.indices) {
            val updated = msg.copy(
                content = msg.revisions[revisionIndex],
                currentRevisionIndex = revisionIndex
            )
            messageV2Dao.updateMessage(updated)
        }
    }

    override suspend fun deleteMessage(messageId: Int) {
        messageV2Dao.deleteMessage(messageId)
    }

    override suspend fun searchMessages(query: String): List<MessageV2> = messageV2Dao.searchMessages(query)

    override suspend fun updateChatParameters(
        chatRoomId: Int,
        systemPrompt: String?,
        temperature: Float?,
        topP: Float?
    ) {
        chatRoomV2Dao.updateChatParameters(chatRoomId, systemPrompt, temperature, topP)
    }

    override suspend fun toggleFavoriteMessage(messageId: Int, isFavorite: Boolean) {
        messageV2Dao.updateFavorite(messageId, isFavorite)
    }

    override fun observeFavoriteMessages(): Flow<List<MessageV2>> {
        return messageV2Dao.observeFavoriteMessages()
    }

    override suspend fun fetchFavoriteMessages(): List<MessageV2> {
        return messageV2Dao.getFavoriteMessages()
    }

    override suspend fun searchFavoriteMessages(query: String): List<MessageV2> {
        return messageV2Dao.searchFavoriteMessages(query)
    }
}
