package dev.melo.gptmobile.improved.data.repository

import dev.melo.gptmobile.improved.data.database.entity.AgentRun
import dev.melo.gptmobile.improved.data.database.entity.ChatRoomV2
import dev.melo.gptmobile.improved.data.database.entity.MessageV2
import dev.melo.gptmobile.improved.data.database.entity.PersistAgentRetryRequest
import dev.melo.gptmobile.improved.data.database.entity.PersistAgentRetryResult
import dev.melo.gptmobile.improved.data.database.entity.PersistAgentTurnRequest
import dev.melo.gptmobile.improved.data.database.entity.PersistAgentTurnResult
import dev.melo.gptmobile.improved.data.database.entity.PlatformV2
import dev.melo.gptmobile.improved.data.database.entity.ToolEvent
import dev.melo.gptmobile.improved.data.dto.ApiState
import dev.melo.gptmobile.improved.data.model.ChatMcpToolConfig
import kotlinx.coroutines.flow.Flow

interface ChatRepository {

    suspend fun completeChat(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2,
        runId: String,
        chatToolConfig: ChatMcpToolConfig? = null
    ): Flow<ApiState>
    fun observeMessagesV2(chatId: Int): Flow<List<MessageV2>>
    fun observeFavoriteAssistantMessages(): Flow<List<MessageV2>>
    fun searchFavoriteAssistantMessages(query: String): Flow<List<MessageV2>>
    suspend fun setMessageFavorite(messageId: Int, isFavorite: Boolean)
    fun observeAgentRuns(chatId: Int): Flow<List<AgentRun>>
    fun observeToolEvents(chatId: Int): Flow<List<ToolEvent>>
    suspend fun fetchChatListV2(): List<ChatRoomV2>
    suspend fun searchChatsV2(query: String): List<ChatRoomV2>
    suspend fun fetchMessagesV2(chatId: Int): List<MessageV2>
    suspend fun fetchChatPlatformModels(chatId: Int): Map<String, String>
    suspend fun saveChatPlatformModels(chatId: Int, models: Map<String, String>)
    suspend fun persistAgentTurn(request: PersistAgentTurnRequest): PersistAgentTurnResult
    suspend fun persistAgentRetry(request: PersistAgentRetryRequest): PersistAgentRetryResult
    suspend fun markAgentRunRunning(runId: String, startedAt: Long): Boolean
    suspend fun finishAgentRun(runId: String, status: String, completedAt: Long, terminalError: String?): Boolean
    suspend fun finishQueuedAgentRun(runId: String, status: String, completedAt: Long, terminalError: String?): Boolean
    suspend fun finishActiveAgentRun(runId: String, status: String, completedAt: Long, terminalError: String?): Boolean
    suspend fun updateAgentMessage(message: MessageV2)
    suspend fun interruptActiveAgentRuns(completedAt: Long): Int
    fun generateDefaultChatTitle(messages: List<MessageV2>): String?
    suspend fun updateChatTitle(chatRoom: ChatRoomV2, title: String)
    suspend fun saveChat(chatRoom: ChatRoomV2, messages: List<MessageV2>, chatPlatformModels: Map<String, String>): ChatRoomV2
    suspend fun duplicateChatV2(chatRoom: ChatRoomV2): ChatRoomV2
    suspend fun deleteChatsV2(chatRooms: List<ChatRoomV2>)
}
