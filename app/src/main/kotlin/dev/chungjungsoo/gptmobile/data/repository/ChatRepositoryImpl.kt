package dev.chungjungsoo.gptmobile.data.repository

import android.content.Context
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao
import dev.chungjungsoo.gptmobile.data.database.dao.AgentRunDao
import dev.chungjungsoo.gptmobile.data.database.dao.ChatPlatformModelV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.ChatRoomV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.MessageV2Dao
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentRetryRequest
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentRetryResult
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnRequest
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnResult
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.di.DeviceSocModel
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.AnthropicAPI
import dev.chungjungsoo.gptmobile.data.network.AttachmentUploadCoordinator
import dev.chungjungsoo.gptmobile.data.network.GoogleAPI
import dev.chungjungsoo.gptmobile.data.network.GroqAPI
import dev.chungjungsoo.gptmobile.data.network.OpenAIAPI
import dev.chungjungsoo.gptmobile.data.network.agent.AgentToolResolver
import dev.chungjungsoo.gptmobile.data.network.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.network.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.network.agent.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.network.agent.ToolEventRecorder
import dev.chungjungsoo.gptmobile.data.network.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.network.anthropic.AnthropicMessagesAdapter
import dev.chungjungsoo.gptmobile.data.network.gemini.GeminiAdapter
import dev.chungjungsoo.gptmobile.data.network.litert.LiteRtLmAdapter
import dev.chungjungsoo.gptmobile.data.network.litert.LocalRuntime
import dev.chungjungsoo.gptmobile.data.network.openai.OpenAICompatibleAdapter
import dev.chungjungsoo.gptmobile.data.network.openai.OpenAIResponsesAdapter
import dev.chungjungsoo.gptmobile.data.network.util.ContextBuilder
import dev.chungjungsoo.gptmobile.data.network.util.ProviderAttachmentEncoder
import dev.chungjungsoo.gptmobile.data.network.util.stripAssistantErrorNote
import dev.chungjungsoo.gptmobile.domain.repository.LocalModelRepository
import dev.chungjungsoo.gptmobile.domain.repository.ModelCatalogRepository
import dev.chungjungsoo.gptmobile.domain.repository.SettingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ChatRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val chatRoomV2Dao: ChatRoomV2Dao,
    private val messageV2Dao: MessageV2Dao,
    private val chatPlatformModelV2Dao: ChatPlatformModelV2Dao,
    private val agentPersistenceDao: AgentPersistenceDao,
    private val agentRunDao: AgentRunDao,
    private val settingRepository: SettingRepository,
    private val openAIAPI: OpenAIAPI,
    private val groqAPI: GroqAPI,
    private val anthropicAPI: AnthropicAPI,
    private val googleAPI: GoogleAPI,
    private val attachmentUploadCoordinator: AttachmentUploadCoordinator,
    private val contextBuilder: ContextBuilder,
    private val agentToolResolver: AgentToolResolver,
    private val toolEventRecorder: ToolEventRecorder,
    private val localRuntime: LocalRuntime,
    private val localModelRepository: LocalModelRepository,
    private val modelCatalogRepository: ModelCatalogRepository,
    @param:DeviceSocModel private val deviceSocModel: String
) : ChatRepository {
    private val providerAttachmentEncoder = ProviderAttachmentEncoder(context)
    private val openAIResponsesAdapter = OpenAIResponsesAdapter(openAIAPI, providerAttachmentEncoder)
    private val openAICompatibleAdapter = OpenAICompatibleAdapter(openAIAPI, groqAPI, providerAttachmentEncoder)
    private val anthropicMessagesAdapter = AnthropicMessagesAdapter(anthropicAPI, providerAttachmentEncoder)
    private val geminiAdapter = GeminiAdapter(googleAPI, providerAttachmentEncoder)
    private val liteRtLmAdapter = LiteRtLmAdapter(
        localRuntime = localRuntime,
        localModelRepository = localModelRepository,
        ignoredAttachmentsNotice = contextString(
            R.string.local_platform_ignored_attachments,
            LiteRtLmAdapter.DEFAULT_IGNORED_ATTACHMENTS
        ),
        modelNotDownloadedError = contextString(
            R.string.local_platform_model_not_downloaded,
            LiteRtLmAdapter.DEFAULT_MODEL_NOT_DOWNLOADED
        ),
        waitingForEngineNotice = contextString(
            R.string.local_platform_waiting_for_engine,
            LiteRtLmAdapter.DEFAULT_WAITING_FOR_ENGINE
        ),
        tooManyImagesNotice = contextString(
            R.string.local_platform_too_many_images,
            LiteRtLmAdapter.DEFAULT_TOO_MANY_IMAGES
        ),
        loadingModelNotice = contextString(
            R.string.local_platform_loading_model,
            LiteRtLmAdapter.DEFAULT_LOADING_MODEL
        ),
        gpuUnavailableNotice = contextString(
            R.string.local_platform_gpu_unavailable_cpu,
            LiteRtLmAdapter.DEFAULT_GPU_UNAVAILABLE
        ),
        npuUnavailableNotice = contextString(
            R.string.local_platform_npu_unavailable_cpu,
            LiteRtLmAdapter.DEFAULT_NPU_UNAVAILABLE
        ),
        engineLoadFailedError = contextString(
            R.string.local_platform_engine_load_failed,
            LiteRtLmAdapter.DEFAULT_ENGINE_LOAD_FAILED
        )
    )

    override suspend fun completeChat(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2,
        runId: String,
        chatToolConfig: ChatMcpToolConfig?
    ): Flow<ApiState> = flow {
        val resolvedTools = agentToolResolver.resolveTools(platform, chatToolConfig)
        val toolTraceSession = ToolTraceSession(
            runId = runId,
            tools = resolvedTools,
            recorder = toolEventRecorder
        )

        when (platform.compatibleType) {
            ClientType.OPENAI -> openAIResponsesAdapter.sendStreamChat(
                userMessages = userMessages,
                assistantMessages = assistantMessages,
                platform = platform,
                tools = resolvedTools,
                onToolCallStarted = toolTraceSession::start,
                onToolCallFinished = toolTraceSession::finish
            )
            ClientType.OPENROUTER,
            ClientType.OPENAI_COMPATIBLE,
            ClientType.GROQ,
            ClientType.OLLAMA -> openAICompatibleAdapter.sendStreamChat(
                userMessages = userMessages,
                assistantMessages = assistantMessages,
                platform = platform,
                tools = resolvedTools,
                onToolCallStarted = toolTraceSession::start,
                onToolCallFinished = toolTraceSession::finish
            )
            ClientType.ANTHROPIC -> anthropicMessagesAdapter.sendStreamChat(
                userMessages = userMessages,
                assistantMessages = assistantMessages,
                platform = platform,
                tools = resolvedTools,
                onToolCallStarted = toolTraceSession::start,
                onToolCallFinished = toolTraceSession::finish
            )
            ClientType.GOOGLE -> geminiAdapter.sendStreamChat(
                userMessages = userMessages,
                assistantMessages = assistantMessages,
                platform = platform,
                tools = resolvedTools,
                onToolCallStarted = toolTraceSession::start,
                onToolCallFinished = toolTraceSession::finish
            )
            ClientType.LITERT_LM -> liteRtLmAdapter.sendStreamChat(
                userMessages = userMessages,
                assistantMessages = assistantMessages,
                platform = platform
            )
        }.collect { state ->
            emit(state)
        }
    }.flowOn(Dispatchers.IO)

    override fun observeMessagesV2(chatId: Int): Flow<List<MessageV2>> = messageV2Dao.getMessagesV2Flow(chatId)

    override fun observeFavoriteAssistantMessages(): Flow<List<MessageV2>> = messageV2Dao.getFavoriteAssistantMessagesFlow()

    override fun searchFavoriteAssistantMessages(query: String): Flow<List<MessageV2>> = messageV2Dao.searchFavoriteAssistantMessagesFlow(query)

    override suspend fun setMessageFavorite(messageId: Int, isFavorite: Boolean) {
        messageV2Dao.updateFavorite(messageId, isFavorite)
    }

    override fun observeAgentRuns(chatId: Int): Flow<List<AgentRun>> = agentRunDao.observeRunsForChat(chatId)

    override fun observeToolEvents(chatId: Int): Flow<List<ToolEvent>> = toolEventRecorder.observeEventsForChat(chatId)

    override suspend fun fetchChatListV2(): List<ChatRoomV2> = chatRoomV2Dao.getChatRooms()

    override suspend fun fetchArchivedChatListV2(): List<ChatRoomV2> = chatRoomV2Dao.getArchivedChatRooms()

    override suspend fun setChatArchived(chatId: Int, isArchived: Boolean) {
        chatRoomV2Dao.updateArchived(chatId, isArchived)
    }

    override suspend fun searchChatsV2(query: String): List<ChatRoomV2> = chatRoomV2Dao.searchChatRoomsByTitle(query)

    override suspend fun fetchMessagesV2(chatId: Int): List<MessageV2> = messageV2Dao.getMessagesV2(chatId)

    override suspend fun fetchChatPlatformModels(chatId: Int): Map<String, String> = chatPlatformModelV2Dao.getChatPlatformModels(chatId).associate { it.platformUid to it.model }

    override suspend fun saveChatPlatformModels(chatId: Int, models: Map<String, String>) {
        chatPlatformModelV2Dao.saveChatPlatformModels(chatId, models)
    }

    override suspend fun persistAgentTurn(request: PersistAgentTurnRequest): PersistAgentTurnResult = agentPersistenceDao.persistAgentTurn(request)

    override suspend fun persistAgentRetry(request: PersistAgentRetryRequest): PersistAgentRetryResult = agentPersistenceDao.persistAgentRetry(request)

    override suspend fun markAgentRunRunning(runId: String, startedAt: Long): Boolean = agentRunDao.markRunning(runId, startedAt) == 1

    override suspend fun finishAgentRun(
        runId: String,
        status: String,
        completedAt: Long,
        terminalError: String?
    ): Boolean = agentRunDao.finish(runId, status, completedAt, terminalError) == 1

    override suspend fun finishQueuedAgentRun(
        runId: String,
        status: String,
        completedAt: Long,
        terminalError: String?
    ): Boolean = agentRunDao.finishQueued(runId, status, completedAt, terminalError) == 1

    override suspend fun finishActiveAgentRun(
        runId: String,
        status: String,
        completedAt: Long,
        terminalError: String?
    ): Boolean = agentRunDao.finishActive(runId, status, completedAt, terminalError) == 1

    override suspend fun updateAgentMessage(message: MessageV2) {
        messageV2Dao.editMessages(message)
    }

    override suspend fun interruptActiveAgentRuns(completedAt: Long): Int = agentRunDao.interruptActiveRuns(completedAt)

    override fun generateDefaultChatTitle(messages: List<MessageV2>): String? = messages.sortedBy { it.createdAt }.firstOrNull { it.platformType == null }?.content?.replace('\n', ' ')?.take(50)

    override suspend fun updateChatTitle(chatRoom: ChatRoomV2, title: String) {
        chatRoomV2Dao.editChatRoom(chatRoom.copy(title = title.replace('\n', ' ').take(50)))
    }

    override suspend fun saveChat(chatRoom: ChatRoomV2, messages: List<MessageV2>, chatPlatformModels: Map<String, String>): ChatRoomV2 {
        if (chatRoom.id == 0) {
            // New Chat
            val chatId = chatRoomV2Dao.addChatRoom(chatRoom)
            val updatedMessages = messages.map { it.copy(chatId = chatId.toInt()) }
            messageV2Dao.addMessages(*updatedMessages.toTypedArray())
            saveChatPlatformModels(
                chatId = chatId.toInt(),
                models = chatPlatformModels.filterKeys { it in chatRoom.enabledPlatform }
            )

            val savedChatRoom = chatRoom.copy(id = chatId.toInt())
            updateChatTitle(savedChatRoom, updatedMessages[0].content)

            return savedChatRoom.copy(title = updatedMessages[0].content.replace('\n', ' ').take(50))
        }

        agentPersistenceDao.saveChatSnapshot(
            chatRoom = chatRoom,
            messages = messages,
            chatPlatformModels = chatPlatformModels.filterKeys { it in chatRoom.enabledPlatform }
        )

        return chatRoom
    }

    override suspend fun duplicateChatV2(chatRoom: ChatRoomV2): ChatRoomV2 {
        val duplicatedTitle = "${chatRoom.title} (copy)".take(50)
        return agentPersistenceDao.duplicateChatWithHistory(
            sourceChatId = chatRoom.id,
            title = duplicatedTitle,
            timestamp = System.currentTimeMillis() / 1000
        )
    }

    override suspend fun deleteChatsV2(chatRooms: List<ChatRoomV2>) {
        chatRoomV2Dao.deleteChatRooms(*chatRooms.toTypedArray())
    }

    private fun contextString(resId: Int, fallback: String): String = runCatching { context.getString(resId) }.getOrDefault(fallback)
}

internal fun MessageV2.sendableAssistantContent(): String {
    val strippedContent = stripAssistantErrorNote(effectiveContent()).trim()
    return if (strippedContent.startsWith("Error: ")) "" else strippedContent
}

internal fun MessageV2.hasSendableAssistantPayload(): Boolean = sendableAssistantContent().isNotBlank() || attachments.isNotEmpty()

internal fun validateResponseInputPartsOrThrow(messageContent: String, partCount: Int, messageId: Int) {
    if (messageContent.isBlank() && partCount == 0) {
        throw IllegalStateException("No encodable message content for messageId=$messageId")
    }
}

private class ToolTraceSession(
    private val runId: String,
    tools: List<ResolvedAgentTool>,
    private val recorder: ToolEventRecorder
) {
    private val toolsByName = tools.associateBy { it.modelToolName }
    private val pendingEventIds = mutableMapOf<String, ArrayDeque<String>>()
    private var sequence = 0

    suspend fun start(call: ProviderEvent.ToolCall): ToolEvent {
        val resolved = toolsByName[call.name]
        val event = recorder.startTool(
            runId = runId,
            sequence = sequence++,
            callId = call.callId,
            toolName = resolved?.realToolName ?: call.name,
            modelToolName = call.name,
            arguments = call.arguments,
            connectionUid = resolved?.connectionUid,
            connectionName = resolved?.connectionName,
            startedAt = currentEpochSeconds()
        )
        pendingEventIds.getOrPut(call.callId, ::ArrayDeque).addLast(event.eventId)
        return event
    }

    suspend fun finish(call: ProviderEvent.ToolCall, result: AgentToolResult) {
        val eventId = pendingEventIds[call.callId]?.removeFirstOrNull() ?: return
        recorder.finishTool(
            eventId = eventId,
            result = result,
            completedAt = currentEpochSeconds(),
            error = result.errorMessage()
        )
    }
}

private fun AgentToolResult.errorMessage(): String? {
    if (!isError) return null
    return when (val value = content) {
        is ToolResultContent.Text -> value.text
        is ToolResultContent.Json -> value.value.toString()
        is ToolResultContent.ResourceLinks -> "Tool call failed."
    }
}

private fun currentEpochSeconds(): Long = System.currentTimeMillis() / 1000
