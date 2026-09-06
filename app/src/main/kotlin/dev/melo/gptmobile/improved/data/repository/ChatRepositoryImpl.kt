package dev.melo.gptmobile.improved.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.melo.gptmobile.improved.R
import dev.melo.gptmobile.improved.data.agent.AgentRunEvent
import dev.melo.gptmobile.improved.data.agent.AgentRunner
import dev.melo.gptmobile.improved.data.agent.AgentToolResult
import dev.melo.gptmobile.improved.data.agent.ProviderEvent
import dev.melo.gptmobile.improved.data.agent.ToolResultContent
import dev.melo.gptmobile.improved.data.agent.provider.AnthropicMessagesAdapter
import dev.melo.gptmobile.improved.data.agent.provider.GeminiAdapter
import dev.melo.gptmobile.improved.data.agent.provider.LiteRtLmAdapter
import dev.melo.gptmobile.improved.data.agent.provider.OpenAICompatibleAdapter
import dev.melo.gptmobile.improved.data.agent.provider.OpenAIResponsesAdapter
import dev.melo.gptmobile.improved.data.agent.provider.ProviderAttachmentEncoder
import dev.melo.gptmobile.improved.data.agent.tool.AgentToolResolver
import dev.melo.gptmobile.improved.data.agent.tool.ResolvedAgentTool
import dev.melo.gptmobile.improved.data.context.ContextBuilder
import dev.melo.gptmobile.improved.data.context.ConversationTurn
import dev.melo.gptmobile.improved.data.context.ProviderContextPolicy
import dev.melo.gptmobile.improved.data.database.dao.AgentPersistenceDao
import dev.melo.gptmobile.improved.data.database.dao.AgentRunDao
import dev.melo.gptmobile.improved.data.database.dao.ChatPlatformModelV2Dao
import dev.melo.gptmobile.improved.data.database.dao.ChatRoomV2Dao
import dev.melo.gptmobile.improved.data.database.dao.MessageV2Dao
import dev.melo.gptmobile.improved.data.database.entity.ChatPlatformModelV2
import dev.melo.gptmobile.improved.data.database.entity.ChatRoomV2
import dev.melo.gptmobile.improved.data.database.entity.MessageV2
import dev.melo.gptmobile.improved.data.database.entity.PersistAgentRetryRequest
import dev.melo.gptmobile.improved.data.database.entity.PersistAgentRetryResult
import dev.melo.gptmobile.improved.data.database.entity.PersistAgentTurnRequest
import dev.melo.gptmobile.improved.data.database.entity.PersistAgentTurnResult
import dev.melo.gptmobile.improved.data.database.entity.PlatformV2
import dev.melo.gptmobile.improved.data.database.entity.ToolEvent
import dev.melo.gptmobile.improved.data.database.entity.effectiveContent
import dev.melo.gptmobile.improved.data.dto.ApiState
import dev.melo.gptmobile.improved.data.localruntime.LocalRuntime
import dev.melo.gptmobile.improved.data.model.ChatMcpToolConfig
import dev.melo.gptmobile.improved.data.model.ClientType
import dev.melo.gptmobile.improved.data.network.AnthropicAPI
import dev.melo.gptmobile.improved.data.network.GoogleAPI
import dev.melo.gptmobile.improved.data.network.GroqAPI
import dev.melo.gptmobile.improved.data.network.OpenAIAPI
import dev.melo.gptmobile.improved.di.DeviceSocModel
import dev.melo.gptmobile.improved.util.FileUtils
import dev.melo.gptmobile.improved.util.stripAssistantErrorNote
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext

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
        ),
        modelCatalogRepository = modelCatalogRepository,
        deviceSocModel = deviceSocModel,
        loadImageBytes = { attachment ->
            val filePath = attachment.preparedFilePath.ifBlank { attachment.localFilePath }
            FileUtils.readImageBytesForLocalInference(context, filePath)
        }
    )
    private val agentRunner = AgentRunner()

    override suspend fun completeChat(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2,
        runId: String,
        chatToolConfig: ChatMcpToolConfig?
    ): Flow<ApiState> = flow {
        emit(ApiState.Loading)
        try {
            val contextTurns = withContext(Dispatchers.Default) {
                buildContextTurns(userMessages, assistantMessages, platform).also { turns ->
                    validateInlineBudgetIfNeeded(turns, platform)
                }
            }
            val resolvedTools = agentToolResolver.resolve(platform.uid, chatToolConfig)
            val session = when (platform.compatibleType) {
                ClientType.OPENAI -> openAIResponsesAdapter.openSession(contextTurns, platform)

                ClientType.GROQ, ClientType.OLLAMA, ClientType.OPENROUTER, ClientType.CUSTOM ->
                    openAICompatibleAdapter.openSession(contextTurns, platform)

                ClientType.ANTHROPIC -> anthropicMessagesAdapter.openSession(contextTurns, platform)

                ClientType.GOOGLE -> geminiAdapter.openSession(contextTurns, platform)

                ClientType.LITERT_LM -> liteRtLmAdapter.openSession(
                    contextTurns,
                    platform,
                    resolvedTools.map { it.tool }
                )
            }
            val runnerTools = if (session.handlesToolsInternally) {
                emptyList()
            } else {
                resolvedTools.map { it.tool }
            }
            val trace = ToolTraceSession(runId, resolvedTools, toolEventRecorder)

            agentRunner.run(session, runnerTools).collect { runEvent ->
                when (runEvent) {
                    is AgentRunEvent.Provider -> when (val providerEvent = runEvent.event) {
                        is ProviderEvent.ThinkingDelta -> emit(ApiState.Thinking(providerEvent.text))

                        is ProviderEvent.TextDelta -> emit(ApiState.Success(providerEvent.text))

                        is ProviderEvent.Failed -> emit(ApiState.Error(providerEvent.message))

                        is ProviderEvent.Notice -> emit(ApiState.Notice(providerEvent.message, providerEvent.persistent))

                        is ProviderEvent.ToolCall -> {
                            val toolEvent = trace.start(providerEvent)
                            emit(ApiState.ToolCall(toolEvent.sequence))
                        }

                        is ProviderEvent.ToolResult -> Unit

                        ProviderEvent.Completed -> Unit
                    }

                    is AgentRunEvent.ToolStarted -> Unit

                    is AgentRunEvent.ToolFinished -> trace.finish(runEvent.call, runEvent.result)

                    is AgentRunEvent.Notice -> emit(ApiState.Notice(runEvent.message, runEvent.persistent))
                }
            }
        } finally {
            withContext(NonCancellable) {
                toolEventRecorder.cancelRun(runId, currentEpochSeconds())
            }
        }
    }.catch { error ->
        emit(ApiState.Error(error.message ?: "Failed to complete chat"))
    }.onCompletion {
        emit(ApiState.Done)
    }

    private suspend fun buildContextTurns(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2
    ): List<ConversationTurn> {
        val policy = ProviderContextPolicy.forClientType(platform.compatibleType)
        val contextTurns = contextBuilder.build(userMessages, assistantMessages, platform, policy)
        if (!policy.preferProviderFileRefs || contextTurns.isEmpty()) {
            return contextTurns
        }

        return ensureProviderReferencesForTurns(contextTurns, platform)
    }

    private suspend fun ensureProviderReferencesForTurns(
        turns: List<ConversationTurn>,
        platform: PlatformV2
    ): List<ConversationTurn> {
        val preparedUserMessages = prepareMessagesForPlatform(turns.map { it.userMessage }, platform)
        return turns.mapIndexed { index, turn ->
            turn.copy(userMessage = preparedUserMessages[index])
        }
    }

    private suspend fun validateInlineBudgetIfNeeded(
        contextTurns: List<ConversationTurn>,
        platform: PlatformV2
    ) {
        val maxInlineBytes = ProviderContextPolicy.forClientType(platform.compatibleType).maxInlineAttachmentBytes ?: return
        attachmentUploadCoordinator.validateInlineAttachmentBudget(contextTurns, maxInlineBytes)
    }

    private suspend fun prepareMessagesForPlatform(
        messages: List<MessageV2>,
        platform: PlatformV2
    ): List<MessageV2> {
        if (messages.none { it.attachments.isNotEmpty() }) {
            return messages
        }

        val updatedMessages = coroutineScope {
            messages.map { message ->
                async { attachmentUploadCoordinator.ensureMessageAttachmentsForPlatform(message, platform) }
            }.awaitAll()
        }

        val changedMessages = updatedMessages
            .zip(messages)
            .mapNotNull { (updated, original) -> updated.takeIf { it != original } }

        if (changedMessages.isNotEmpty()) {
            messageV2Dao.editMessages(*changedMessages.toTypedArray())
        }

        return updatedMessages
    }

    override suspend fun fetchChatListV2(): List<ChatRoomV2> = chatRoomV2Dao.getChatRooms()

    override suspend fun searchChatsV2(query: String): List<ChatRoomV2> {
        if (query.isBlank()) {
            return chatRoomV2Dao.getChatRooms()
        }

        // Search by title and message content concurrently on I/O dispatcher
        val (titleMatches, messageMatchChatIds) = withContext(Dispatchers.IO) {
            coroutineScope {
                val titleJob = async { chatRoomV2Dao.searchChatRoomsByTitle(query) }
                val contentJob = async { messageV2Dao.searchMessagesByContent(query) }
                Pair(titleJob.await(), contentJob.await())
            }
        }

        // Query only the matched chat rooms directly from DB by ID instead of fetching all chat rooms into memory
        val messageMatches = if (messageMatchChatIds.isEmpty()) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                chatRoomV2Dao.getChatRoomsByIds(messageMatchChatIds)
            }
        }

        // Combine results and remove duplicates, maintaining order by updatedAt
        val titleMatchIds = HashSet<Int>(titleMatches.size)
        val combined = ArrayList<ChatRoomV2>(titleMatches.size + messageMatches.size)
        for (room in titleMatches) {
            titleMatchIds.add(room.id)
            combined.add(room)
        }
        for (room in messageMatches) {
            if (titleMatchIds.add(room.id)) {
                combined.add(room)
            }
        }
        combined.sortByDescending { it.updatedAt }
        return combined
    }

    override suspend fun fetchMessagesV2(chatId: Int): List<MessageV2> = messageV2Dao.loadMessages(chatId)

    override fun observeMessagesV2(chatId: Int): Flow<List<MessageV2>> = messageV2Dao.observeMessages(chatId)

    override fun observeFavoriteAssistantMessages(): Flow<List<MessageV2>> = messageV2Dao.observeFavoriteAssistantMessages()

    override fun searchFavoriteAssistantMessages(query: String): Flow<List<MessageV2>> =
        if (query.isBlank()) {
            messageV2Dao.observeFavoriteAssistantMessages()
        } else {
            messageV2Dao.searchFavoriteAssistantMessages(query)
        }

    override suspend fun setMessageFavorite(messageId: Int, isFavorite: Boolean) {
        messageV2Dao.updateFavorite(messageId, isFavorite)
    }

    override fun observeAgentRuns(chatId: Int) = agentRunDao.observeByChatId(chatId)

    override fun observeToolEvents(chatId: Int): Flow<List<ToolEvent>> = toolEventRecorder.observeChat(chatId)

    override suspend fun fetchChatPlatformModels(chatId: Int): Map<String, String> = chatPlatformModelV2Dao.getByChatId(chatId).associate {
        it.platformUid to it.model
    }

    override suspend fun saveChatPlatformModels(chatId: Int, models: Map<String, String>) {
        val rows = models
            .filterKeys { it.isNotBlank() }
            .map { (platformUid, model) ->
                ChatPlatformModelV2(
                    chatId = chatId,
                    platformUid = platformUid,
                    model = model.trim()
                )
            }

        if (rows.isNotEmpty()) {
            chatPlatformModelV2Dao.upsertAll(*rows.toTypedArray())
        }
    }

    override suspend fun persistAgentTurn(request: PersistAgentTurnRequest): PersistAgentTurnResult = agentPersistenceDao.persistAgentTurn(request)

    override suspend fun persistAgentRetry(request: PersistAgentRetryRequest): PersistAgentRetryResult = agentPersistenceDao.persistAgentRetry(request)

    override suspend fun markAgentRunRunning(runId: String, startedAt: Long): Boolean = agentRunDao.markRunning(runId, startedAt) == 1

    override suspend fun finishAgentRun(
        runId: String,
        status: String,
        completedAt: Long,
        terminalError: String?
    ): Boolean = agentRunDao.finishRunning(runId, status, completedAt, terminalError) == 1

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
