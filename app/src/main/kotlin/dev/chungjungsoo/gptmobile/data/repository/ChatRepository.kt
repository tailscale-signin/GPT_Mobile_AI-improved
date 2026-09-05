package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.model.ModelRoutingRule
import dev.chungjungsoo.gptmobile.data.model.PlatformType
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun getChatRooms(): Flow<List<ChatRoomV2>>
    suspend fun getChatRoom(chatRoomId: Int): ChatRoomV2?
    suspend fun createChatRoom(title: String, platformType: PlatformType, model: String): Long
    suspend fun updateChatRoomTitle(chatRoomId: Int, title: String)
    suspend fun updateChatRoomModel(chatRoomId: Int, platformType: PlatformType, model: String)
    suspend fun deleteChatRoom(chatRoomId: Int)
    suspend fun pinChatRoom(chatRoomId: Int, isPinned: Boolean)
    suspend fun updateChatRoomCategory(chatRoomId: Int, category: String?)
    suspend fun getCategories(): List<String>
    suspend fun updateModelRoutingRules(chatRoomId: Int, rules: List<ModelRoutingRule>)
    suspend fun archiveChatRoom(chatRoomId: Int, isArchived: Boolean)
    fun getArchivedChatRooms(): Flow<List<ChatRoomV2>>
    suspend fun updateExportFormat(chatRoomId: Int, exportFormat: String?)
    suspend fun favoriteChatRoom(chatRoomId: Int, isFavorite: Boolean)
    fun getFavoriteChatRooms(): Flow<List<ChatRoomV2>>

    fun getMessages(chatRoomId: Int): Flow<List<MessageV2>>
    suspend fun getMessagesSync(chatRoomId: Int): List<MessageV2>
    suspend fun getMessage(messageId: Int): MessageV2?
    suspend fun addMessage(
        chatRoomId: Int,
        content: String,
        platformType: PlatformType?,
        model: String?,
        imagePaths: List<String> = emptyList(),
        audioPath: String? = null,
        fileAttachments: List<String> = emptyList(),
        revisions: List<String> = emptyList(),
        currentRevisionIndex: Int = 0
    ): Long
    suspend fun updateMessage(message: MessageV2)
    suspend fun updateMessageContent(messageId: Int, content: String)
    suspend fun addMessageRevision(messageId: Int, newContent: String)
    suspend fun selectMessageRevision(messageId: Int, revisionIndex: Int)
    suspend fun deleteMessage(messageId: Int)
    suspend fun searchMessages(query: String): List<MessageV2>
    suspend fun updateChatParameters(chatRoomId: Int, systemPrompt: String?, temperature: Float?, topP: Float?)

    suspend fun toggleFavoriteMessage(messageId: Int, isFavorite: Boolean)
    fun observeFavoriteMessages(): Flow<List<MessageV2>>
    suspend fun fetchFavoriteMessages(): List<MessageV2>
    suspend fun searchFavoriteMessages(query: String): List<MessageV2>
}
