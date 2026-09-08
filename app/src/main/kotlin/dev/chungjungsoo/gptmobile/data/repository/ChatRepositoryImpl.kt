package dev.chungjungsoo.gptmobile.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.agent.AgentRunEvent
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.agentRunnerForPlatform
import dev.chungjungsoo.gptmobile.data.agent.provider.AnthropicMessagesAdapter
import dev.chungjungsoo.gptmobile.data.agent.provider.GeminiAdapter
import dev.chungjungsoo.gptmobile.data.agent.provider.LiteRtLmAdapter
import dev.chungjungsoo.gptmobile.data.agent.provider.OpenAICompatibleAdapter
import dev.chungjungsoo.gptmobile.data.agent.provider.OpenAIResponsesAdapter
import dev.chungjungsoo.gptmobile.data.agent.provider.ProviderAttachmentEncoder
import dev.chungjungsoo.gptmobile.data.agent.tool.AgentToolResolver
import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.context.ContextBuilder
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.ProviderContextPolicy
import dev.chungjungsoo.gptmobile.data.database.dao.*
import dev.chungjungsoo.gptmobile.data.database.entity.*
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntime
import dev.chungjungsoo.gptmobile.data.model.ApiType
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.*
import dev.chungjungsoo.gptmobile.di.DeviceSocModel
import dev.chungjungsoo.gptmobile.util.FileUtils
import dev.chungjungsoo.gptmobile.util.stripAssistantErrorNote
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ChatRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val chatRoomDao: ChatRoomDao, private val messageDao: MessageDao,
    private val chatRoomV2Dao: ChatRoomV2Dao, private val messageV2Dao: MessageV2Dao,
    private val chatPlatformModelV2Dao: ChatPlatformModelV2Dao,
    private val agentPersistenceDao: AgentPersistenceDao, private val agentRunDao: AgentRunDao,
    private val settingRepository: SettingRepository, private val openAIAPI: OpenAIAPI,
    private val groqAPI: GroqAPI, private val anthropicAPI: AnthropicAPI, private val googleAPI: GoogleAPI,
    private val attachmentUploadCoordinator: AttachmentUploadCoordinator, private val contextBuilder: ContextBuilder,
    private val agentToolResolver: AgentToolResolver, private val toolEventRecorder: ToolEventRecorder,
    private val localRuntime: LocalRuntime, private val localModelRepository: LocalModelRepository,
    private val modelCatalogRepository: ModelCatalogRepository,
    @param:DeviceSocModel private val deviceSocModel: String
) : ChatRepository {
    private val providerAttachmentEncoder = ProviderAttachmentEncoder(context)
    private val openAIResponsesAdapter = OpenAIResponsesAdapter(openAIAPI, providerAttachmentEncoder)
    private val openAICompatibleAdapter = OpenAICompatibleAdapter(openAIAPI, groqAPI, providerAttachmentEncoder)
    private val anthropicMessagesAdapter = AnthropicMessagesAdapter(anthropicAPI, providerAttachmentEncoder)
    private val geminiAdapter = GeminiAdapter(googleAPI, providerAttachmentEncoder)
    private val liteRtLmAdapter = LiteRtLmAdapter(localRuntime, localModelRepository,
        contextString(R.string.local_platform_ignored_attachments, LiteRtLmAdapter.DEFAULT_IGNORED_ATTACHMENTS),
        contextString(R.string.local_platform_model_not_downloaded, LiteRtLmAdapter.DEFAULT_MODEL_NOT_DOWNLOADED),
        contextString(R.string.local_platform_waiting_for_engine, LiteRtLmAdapter.DEFAULT_WAITING_FOR_ENGINE),
        contextString(R.string.local_platform_too_many_images, LiteRtLmAdapter.DEFAULT_TOO_MANY_IMAGES),
        contextString(R.string.local_platform_loading_model, LiteRtLmAdapter.DEFAULT_LOADING_MODEL),
        contextString(R.string.local_platform_gpu_unavailable_cpu, LiteRtLmAdapter.DEFAULT_GPU_UNAVAILABLE),
        contextString(R.string.local_platform_npu_unavailable_cpu, LiteRtLmAdapter.DEFAULT_NPU_UNAVAILABLE),
        contextString(R.string.local_platform_engine_load_failed, LiteRtLmAdapter.DEFAULT_ENGINE_LOAD_FAILED),
        modelCatalogRepository, deviceSocModel,
        loadImageBytes = { a -> FileUtils.readImageBytesForLocalInference(context, a.preparedFilePath.ifBlank { a.localFilePath }) })

    override suspend fun completeChat(userMessages: List<MessageV2>, assistantMessages: List<List<MessageV2>>, platform: PlatformV2, runId: String): Flow<ApiState> = flow {
        emit(ApiState.Loading)
        try {
            val contextTurns = withContext(Dispatchers.Default) { buildContextTurns(userMessages, assistantMessages, platform).also { validateInlineBudgetIfNeeded(it, platform) } }
            val resolvedTools = agentToolResolver.resolve(platform.uid)
            val session = when (platform.compatibleType) {
                ClientType.OPENAI -> openAIResponsesAdapter.openSession(contextTurns, platform)
                ClientType.GROQ, ClientType.OLLAMA, ClientType.OPENROUTER, ClientType.CUSTOM -> openAICompatibleAdapter.openSession(contextTurns, platform)
                ClientType.ANTHROPIC -> anthropicMessagesAdapter.openSession(contextTurns, platform)
                ClientType.GOOGLE -> geminiAdapter.openSession(contextTurns, platform)
                ClientType.LITERT_LM -> liteRtLmAdapter.openSession(contextTurns, platform, resolvedTools.map { it.tool })
            }
            val runnerTools = if (session.handlesToolsInternally) emptyList() else resolvedTools.map { it.tool }
            val trace = ToolTraceSession(runId, resolvedTools, toolEventRecorder)
            agentRunnerForPlatform(platform).run(session, runnerTools).collect { event ->
                when (event) {
                    is AgentRunEvent.Provider -> when (val p = event.event) {
                        is ProviderEvent.ThinkingDelta -> emit(ApiState.Thinking(p.text)); is ProviderEvent.TextDelta -> emit(ApiState.Success(p.text))
                        is ProviderEvent.Failed -> emit(ApiState.Error(p.message)); is ProviderEvent.Notice -> emit(ApiState.Notice(p.message, p.persistent))
                        is ProviderEvent.ToolCall -> emit(ApiState.ToolCall(trace.start(p).sequence)); else -> Unit
                    }
                    is AgentRunEvent.ToolFinished -> trace.finish(event.call, event.result)
                    is AgentRunEvent.Notice -> emit(ApiState.Notice(event.message, event.persistent)); else -> Unit
                }
            }
        } finally { withContext(NonCancellable) { toolEventRecorder.cancelRun(runId, currentEpochSeconds()) } }
    }.catch { emit(ApiState.Error(it.message ?: "Failed to complete chat")) }.onCompletion { emit(ApiState.Done) }

    private suspend fun buildContextTurns(u: List<MessageV2>, a: List<List<MessageV2>>, p: PlatformV2): List<ConversationTurn> {
        val policy = ProviderContextPolicy.forClientType(p.compatibleType); val turns = contextBuilder.build(u, a, p, policy)
        return if (!policy.preferProviderFileRefs || turns.isEmpty()) turns else { val prepared = prepareMessagesForPlatform(turns.map { it.userMessage }, p); turns.mapIndexed { i, t -> t.copy(userMessage = prepared[i]) } }
    }
    private suspend fun validateInlineBudgetIfNeeded(t: List<ConversationTurn>, p: PlatformV2) { ProviderContextPolicy.forClientType(p.compatibleType).maxInlineAttachmentBytes?.let { attachmentUploadCoordinator.validateInlineAttachmentBudget(t, it) } }
    private suspend fun prepareMessagesForPlatform(m: List<MessageV2>, p: PlatformV2): List<MessageV2> { val u=m.map{attachmentUploadCoordinator.ensureMessageAttachmentsForPlatform(it,p)}; u.zip(m).mapNotNull{(x,o)->x.takeIf{it!=o}}.also{if(it.isNotEmpty())messageV2Dao.editMessages(*it.toTypedArray())}; return u }
    override suspend fun fetchChatList()=chatRoomDao.getChatRooms(); override suspend fun fetchChatListV2()=chatRoomV2Dao.getChatRooms()
    override suspend fun searchChatsV2(q:String):List<ChatRoomV2>{if(q.isBlank())return chatRoomV2Dao.getChatRooms();return(chatRoomV2Dao.searchChatRoomsByTitle(q)+chatRoomV2Dao.getChatRooms().filter{it.id in messageV2Dao.searchMessagesByContent(q)}).distinctBy{it.id}.sortedWith(compareByDescending<ChatRoomV2>{it.isFavorite}.thenByDescending{it.updatedAt})}
    override suspend fun fetchMessages(chatId:Int)=messageDao.loadMessages(chatId); override suspend fun fetchMessagesV2(chatId:Int)=messageV2Dao.loadMessages(chatId); override fun observeMessagesV2(chatId:Int)=messageV2Dao.observeMessages(chatId); override fun observeAgentRuns(chatId:Int)=agentRunDao.observeByChatId(chatId); override fun observeToolEvents(chatId:Int)=toolEventRecorder.observeChat(chatId)
    override suspend fun fetchChatPlatformModels(chatId:Int)=chatPlatformModelV2Dao.getByChatId(chatId).associate{it.platformUid to it.model}
    override suspend fun saveChatPlatformModels(chatId:Int,models:Map<String,String>){models.filterKeys{it.isNotBlank()}.map{ChatPlatformModelV2(chatId,it.key,it.value.trim())}.also{if(it.isNotEmpty())chatPlatformModelV2Dao.upsertAll(*it.toTypedArray())}}
    override suspend fun persistAgentTurn(request:PersistAgentTurnRequest)=agentPersistenceDao.persistAgentTurn(request); override suspend fun persistAgentRetry(request:PersistAgentRetryRequest)=agentPersistenceDao.persistAgentRetry(request)
    override suspend fun markAgentRunRunning(runId:String,startedAt:Long)=agentRunDao.markRunning(runId,startedAt)==1
    override suspend fun finishAgentRun(runId:String,status:String,completedAt:Long,terminalError:String?)=agentRunDao.finishRunning(runId,status,completedAt,terminalError)==1
    override suspend fun finishQueuedAgentRun(runId:String,status:String,completedAt:Long,terminalError:String?)=agentRunDao.finishQueued(runId,status,completedAt,terminalError)==1
    override suspend fun finishActiveAgentRun(runId:String,status:String,completedAt:Long,terminalError:String?)=agentRunDao.finishActive(runId,status,completedAt,terminalError)==1
    override suspend fun updateAgentMessage(message:MessageV2){messageV2Dao.editMessages(message)}; override suspend fun interruptActiveAgentRuns(completedAt:Long)=agentRunDao.interruptActiveRuns(completedAt)
    override suspend fun migrateToChatRoomV2MessageV2(){val old=chatRoomV2Dao.getChatRooms();old.forEach{chatPlatformModelV2Dao.deleteByChatId(it.id)};chatRoomV2Dao.deleteChatRooms(*old.toTypedArray());val ps=settingRepository.fetchPlatformV2s();val map=mutableMapOf<ApiType,String>();val models=mutableMapOf<String,String>();ps.forEach{p->models[p.uid]=p.model;when(p.name){"OpenAI"->map[ApiType.OPENAI]=p.uid;"Anthropic"->map[ApiType.ANTHROPIC]=p.uid;"Google"->map[ApiType.GOOGLE]=p.uid;"Groq"->map[ApiType.GROQ]=p.uid;"Ollama"->map[ApiType.OLLAMA]=p.uid}};fetchChatList().forEach{c->val ms=messageDao.loadMessages(c.id).map{MessageV2(it.id,it.chatId,it.content,listOf(),listOf(),it.linkedMessageId,it.platformType?.let(map::get),it.createdAt)};val ids=c.enabledPlatform.mapNotNull(map::get).filter{it.isNotBlank()};chatRoomV2Dao.addChatRoom(ChatRoomV2(c.id,c.title,ids,createdAt=c.createdAt,updatedAt=c.createdAt));ids.map{ChatPlatformModelV2(c.id,it,models[it]?:"")}.also{if(it.isNotEmpty())chatPlatformModelV2Dao.upsertAll(*it.toTypedArray())};messageV2Dao.addMessages(*ms.toTypedArray())}}
    override fun generateDefaultChatTitle(messages:List<MessageV2>)=messages.sortedBy{it.createdAt}.firstOrNull{it.platformType==null}?.content?.replace('\n',' ')?.take(50)
    override suspend fun updateChatTitle(chatRoom:ChatRoomV2,title:String){chatRoomV2Dao.editChatRoom(chatRoom.copy(title=title.replace('\n',' ').take(50)))};override suspend fun setChatFavoriteV2(chatId:Int,isFavorite:Boolean){chatRoomV2Dao.updateFavorite(chatId,isFavorite)}
    override suspend fun saveChat(c:ChatRoomV2,m:List<MessageV2>,models:Map<String,String>):ChatRoomV2{if(c.id==0){val id=chatRoomV2Dao.addChatRoom(c).toInt();val u=m.map{it.copy(chatId=id)};messageV2Dao.addMessages(*u.toTypedArray());saveChatPlatformModels(id,models.filterKeys{it in c.enabledPlatform});val s=c.copy(id=id);updateChatTitle(s,u[0].content);return s.copy(title=u[0].content.replace('\n',' ').take(50))};agentPersistenceDao.saveChatSnapshot(c,m,models.filterKeys{it in c.enabledPlatform});return c}
    override suspend fun duplicateChatV2(c:ChatRoomV2)=agentPersistenceDao.duplicateChatWithHistory(c.id,"${c.title} (copy)".take(50),System.currentTimeMillis()/1000);override suspend fun deleteChats(c:List<ChatRoom>){chatRoomDao.deleteChatRooms(*c.toTypedArray())};override suspend fun deleteChatsV2(c:List<ChatRoomV2>){chatRoomV2Dao.deleteChatRooms(*c.toTypedArray())}
    private fun contextString(id:Int,f:String)=runCatching{context.getString(id)}.getOrDefault(f)
}
internal fun MessageV2.sendableAssistantContent()=stripAssistantErrorNote(effectiveContent()).trim().let{if(it.startsWith("Error: "))"" else it};internal fun MessageV2.hasSendableAssistantPayload()=sendableAssistantContent().isNotBlank()||attachments.isNotEmpty();internal fun validateResponseInputPartsOrThrow(c:String,n:Int,id:Int){if(c.isBlank()&&n==0)throw IllegalStateException("No encodable message content for messageId=$id")}
private class ToolTraceSession(private val runId:String,tools:List<ResolvedAgentTool>,private val recorder:ToolEventRecorder){private val by=tools.associateBy{it.modelToolName};private val pending=mutableMapOf<String,ArrayDeque<String>>();private var seq=0;suspend fun start(c:ProviderEvent.ToolCall)=recorder.startTool(runId,seq++,c.callId,by[c.name]?.realToolName?:c.name,c.name,c.arguments,by[c.name]?.connectionUid,by[c.name]?.connectionName,currentEpochSeconds()).also{pending.getOrPut(c.callId,::ArrayDeque).addLast(it.eventId)};suspend fun finish(c:ProviderEvent.ToolCall,r:AgentToolResult){pending[c.callId]?.removeFirstOrNull()?.let{recorder.finishTool(it,r,currentEpochSeconds(),r.errorMessage())}}}
private fun AgentToolResult.errorMessage()=if(!isError)null else when(val v=content){is ToolResultContent.Text->v.text;is ToolResultContent.Json->v.value.toString();is ToolResultContent.ResourceLinks->"Tool call failed."};private fun currentEpochSeconds()=System.currentTimeMillis()/1000
