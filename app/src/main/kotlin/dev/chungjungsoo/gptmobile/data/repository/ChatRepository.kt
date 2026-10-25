package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentRetryRequest
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentRetryResult
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnRequest
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnResult
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import kotlinx.coroutines.flow.Flow

interface ChatRepository {

    /** Resolve live limits once; accepted preferences, roster and grants remain frozen. */
    suspend fun prepareCombinedTurn(
        execution: dev.chungjungsoo.gptmobile.data.agent.CombinedExecutionContext,
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        profiles: List<PlatformV2>,
        tools: ChatMcpToolConfig
    ): dev.chungjungsoo.gptmobile.data.agent.CombinedExecutionContext = execution

    /** Capture authorized attachments/history before freezing the final model-facing envelope. */
    suspend fun prepareCombinedWriterContext(
        history: List<MessageV2>,
        replies: List<List<MessageV2>>,
        user: MessageV2,
        writer: PlatformV2
    ): dev.chungjungsoo.gptmobile.data.agent.CombinedWriterContext = dev.chungjungsoo.gptmobile.data.agent.CombinedWriterContext.capture(history, replies, user, writer)

    suspend fun cancelCombinedTurn(rootBudgetId: String) = Unit
    suspend fun isCombinedTurnCancelled(rootBudgetId: String): Boolean = false
    suspend fun markCombinedMergeUnavailable(chatId: Int, userMessageId: Int, rootBudgetId: String, reason: String? = null) = Unit
    suspend fun canRetryCombined(rootBudgetId: String): Boolean = !isCombinedTurnCancelled(rootBudgetId)
    suspend fun publishCombinedEvidenceFallback(runId: String): Boolean = false
    suspend fun useCombinedReply(runId: String, expectedFence: Long, sourceProfileUid: String): MessageV2? = null
    suspend fun reconcileCombinedLifecycle() = Unit
    suspend fun regenerateCombinedTurn(
        userMessage: MessageV2,
        expectedOwner: MessageV2,
        runs: List<dev.chungjungsoo.gptmobile.data.database.entity.AgentRunDraft>,
        accepted: dev.chungjungsoo.gptmobile.data.agent.CombinedAcceptedTurn
    ): PersistAgentTurnResult = error("Atomic Combined regeneration is unavailable")
    suspend fun replaceResearchDraft(runId: String, expectedFence: Long, previousDigest: String, text: String): MessageV2? = null
    suspend fun replaceCombinedAnswer(
        runId: String,
        expectedFence: Long,
        candidate: dev.chungjungsoo.gptmobile.data.agent.CombinedReplacementCandidate
    ): MessageV2? = null

    /** Isolated benchmark requests use only supplied fixtures, without chat memory or connected tools. */
    suspend fun openBenchmarkSession(
        platform: PlatformV2,
        turns: List<dev.chungjungsoo.gptmobile.data.context.ConversationTurn>,
        tools: List<dev.chungjungsoo.gptmobile.data.agent.AgentTool>,
        runId: String,
        benchmarkCategory: String = "task",
        onInvocation: (String) -> Unit = {},
        onPhysicalRequest: (String) -> Unit = {}
    ): dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession = error("Benchmark sessions are unavailable")

    /** Tool-free article examination uses the same provider adapters and physical invocation ledger. */
    suspend fun openResearchSession(
        platform: PlatformV2,
        prompt: String,
        rootWorkId: String,
        role: String,
        outputTokens: Int,
        parentRunId: String = rootWorkId,
        onInvocation: (String) -> Unit = {}
    ): dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession = error("Research sessions are unavailable")

    suspend fun runDelegationBenchmark(
        platform: PlatformV2,
        test: dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkCase,
        runId: String,
        settings: dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings,
        frozenProfiles: Map<String, PlatformV2> = emptyMap()
    ): dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkSample = error("Delegation benchmarks are unavailable")

    suspend fun supportsBenchmarkTools(platform: PlatformV2): Boolean = false

    /** Null means the remote standard fixture; local fixtures use the actual export limit. */
    suspend fun benchmarkContextTokens(platform: PlatformV2): Int? = null

    /** Validate setup before recording or sending any benchmark requests. */
    suspend fun testLocalAssist(): String = error("Local Assist tests are unavailable")

    suspend fun validateBenchmarkProfile(platform: PlatformV2) = Unit

    /** Preparation evidence is independent of the counted benchmark case population. */
    suspend fun benchmarkReadiness(
        profile: PlatformV2,
        requiresTools: Boolean,
        responseSchemaJson: String?
    ): dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkReadinessReceipt {
        val required = setOf("configuration", "model_header", "initialization", "tokenizer", "memory") +
            (if (responseSchemaJson != null) setOf("json") else emptySet()) +
            (if (requiresTools) setOf("tools") else emptySet())
        val now = System.currentTimeMillis()
        return dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkReadinessReceipt(
            now,
            now,
            required.map { dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkReadinessCheck(it, dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkReadinessStatus.UNKNOWN, "This provider does not report readiness evidence.") },
            required,
            executionPolicy = kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRequestContext]?.executionPolicy
        )
    }

    suspend fun completeChat(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2,
        runId: String,
        chatToolConfig: ChatMcpToolConfig? = null,
        stage: dev.chungjungsoo.gptmobile.data.agent.AgentStageRequest? = null
    ): Flow<ApiState>
    fun observeMessagesV2(chatId: Int): Flow<List<MessageV2>>
    fun observeMessageWindow(chatId: Int, turns: Int): Flow<List<MessageV2>> = observeMessagesV2(chatId)
    fun observeTurnCount(chatId: Int): Flow<Int> = kotlinx.coroutines.flow.flowOf(0)
    fun observeFavoriteAssistantMessages(): Flow<List<MessageV2>>
    fun searchFavoriteAssistantMessages(query: String): Flow<List<MessageV2>>
    suspend fun setMessageFavorite(messageId: Int, isFavorite: Boolean)
    fun observeAgentRuns(chatId: Int): Flow<List<AgentRun>>
    fun observeToolEvents(chatId: Int): Flow<List<ToolEvent>>
    suspend fun fetchChatListV2(): List<ChatRoomV2>
    suspend fun fetchArchivedChatListV2(): List<ChatRoomV2>
    suspend fun setChatArchived(chatId: Int, isArchived: Boolean)
    suspend fun setChatFavorite(chatId: Int, isFavorite: Boolean)
    suspend fun searchChatsV2(query: String): List<ChatRoomV2>
    suspend fun updateDraft(chatId: Int, draftText: String?, timestamp: Long?)
    suspend fun saveComposerDraft(chatId: Int, text: String?, attachments: String, timestamp: Long?) = updateDraft(chatId, text, timestamp)
    suspend fun checkCombinedQueuedJob(runId: String): String

    suspend fun prepareCombinedRetry(runId: String, platform: PlatformV2): dev.chungjungsoo.gptmobile.data.agent.CombinedInputSnapshot? = null

    suspend fun prepareCombinedAppendResume(runId: String, platform: PlatformV2, decision: dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision): dev.chungjungsoo.gptmobile.data.agent.CombinedInputSnapshot? = null

    suspend fun fetchDebugRecords(chatId: Int): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap())
    suspend fun combinedActiveMembers(chatId: Int): Set<String>? = null
    suspend fun reopenCombinedContributor(runId: String): Boolean = false
    suspend fun reconcileCombinedQueuedContributor(runId: String): String? = null
    suspend fun fetchMessagesV2(chatId: Int): List<MessageV2>
    suspend fun fetchMessageReferencesV2(chatId: Int): List<MessageV2> = fetchMessagesV2(chatId)
    suspend fun resolveCombinedMessage(message: MessageV2): MessageV2 = message
    suspend fun combinedDocumentBlocks(reference: dev.chungjungsoo.gptmobile.data.database.entity.CombinedManifestReference, first: Int, count: Int): List<dev.chungjungsoo.gptmobile.data.agent.CombinedDocumentBlock> = emptyList()
    suspend fun combinedSourceBlocks(reference: dev.chungjungsoo.gptmobile.data.database.entity.CombinedManifestReference, source: dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse, first: Int, count: Int): List<dev.chungjungsoo.gptmobile.data.agent.CombinedDocumentBlock> = emptyList()

    /** Missing/deleted rooms and temporary chats never authorize durable Research storage. */
    suspend fun researchPersistenceAllowed(chatId: Int): Boolean = false
    suspend fun newestAssistantMessageId(chatId: Int): Int? = fetchMessagesV2(chatId)
        .filter { it.platformType != null && it.content.isNotBlank() }
        .maxWithOrNull(compareBy<MessageV2> { it.createdAt }.thenBy { it.combinedSources.isNotEmpty() }.thenBy { it.id })?.id
    suspend fun fetchChatPlatformModels(chatId: Int): Map<String, String>
    suspend fun saveChatPlatformModels(chatId: Int, models: Map<String, String>)
    suspend fun applyCombinedChatSettings(
        previous: dev.chungjungsoo.gptmobile.data.model.CombinedChatSettingsDraft,
        next: dev.chungjungsoo.gptmobile.data.model.CombinedChatSettingsDraft
    ): ChatRoomV2 = error("Atomic Combined settings are unavailable")
    suspend fun persistAgentTurn(request: PersistAgentTurnRequest): PersistAgentTurnResult
    suspend fun persistAgentRetry(request: PersistAgentRetryRequest): PersistAgentRetryResult
    suspend fun markAgentRunRunning(runId: String, startedAt: Long): Boolean
    suspend fun finishAgentRun(runId: String, status: String, completedAt: Long, terminalError: String?): Boolean
    suspend fun finishQueuedAgentRun(runId: String, status: String, completedAt: Long, terminalError: String?): Boolean
    suspend fun finishActiveAgentRun(runId: String, status: String, completedAt: Long, terminalError: String?): Boolean
    suspend fun finishInterruptedAgentRun(runId: String, status: String, completedAt: Long, terminalError: String?): Boolean = false
    suspend fun commitAgentRun(message: MessageV2, runId: String, status: String, completedAt: Long, terminalError: String?): Boolean
    suspend fun updateAgentMessage(message: MessageV2)

    /** A final Combined body and report must be durable before the reducer publishes them. */
    suspend fun checkpointCombinedAnswer(message: MessageV2): Boolean {
        updateAgentMessage(message)
        return true
    }
    suspend fun waitingRemoteAgentRuns(): List<AgentRun> = emptyList()
    suspend fun interruptActiveAgentRuns(completedAt: Long): Int
    suspend fun bindGatewayJob(runId: String, jobId: String, baseUrl: String): Boolean
    suspend fun advanceGatewaySequence(runId: String, sequence: Int): Boolean
    suspend fun getRecoverableGatewayRuns(): List<AgentRun>
    suspend fun restoreGatewayAnswer(runId: String, jobId: String, content: String, completedAt: Long): Boolean = false
    fun generateDefaultChatTitle(messages: List<MessageV2>): String?
    suspend fun updateChatTitle(chatRoom: ChatRoomV2, title: String, isCustomized: Boolean = false)
    suspend fun updateChatPlatforms(chatRoom: ChatRoomV2, platformUids: List<String>): ChatRoomV2
    suspend fun generateAiTitle(userMessage: String, assistantMessage: String, platform: PlatformV2): String?

    /** Persist a configured empty conversation before its first request. */
    suspend fun createDraftChat(profileUids: List<String>, conversationMode: String, title: String, primaryProfileUid: String? = null): ChatRoomV2 =
        error("Creating empty chats is not available in this repository implementation")
    suspend fun saveChat(chatRoom: ChatRoomV2, messages: List<MessageV2>, chatPlatformModels: Map<String, String>): ChatRoomV2
    suspend fun createEvidenceFreeRetry(sourceChatId: Int, question: String, profile: PlatformV2): ChatRoomV2 = error("Fresh evidence recovery is unavailable")
    suspend fun duplicateChatV2(chatRoom: ChatRoomV2): ChatRoomV2
    suspend fun branchChat(chatRoom: ChatRoomV2, editedUser: MessageV2): ChatRoomV2 = error("Conversation branching is unavailable")
    suspend fun updateTemporary(chatRoom: ChatRoomV2, enabled: Boolean): ChatRoomV2 = chatRoom
    suspend fun forgetChatMemories(chatId: Int) = Unit
    suspend fun deleteChatsV2(chatRooms: List<ChatRoomV2>)
}
