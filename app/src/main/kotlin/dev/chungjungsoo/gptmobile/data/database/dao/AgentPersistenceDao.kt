package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import dev.chungjungsoo.gptmobile.data.chat.decodedArchiveText
import dev.chungjungsoo.gptmobile.data.database.entity.ACTIVE_REVISION_LATEST
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.ChatPlatformModelV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentRetryRequest
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentRetryResult
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnRequest
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnResult
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.database.entity.snapshotLatestAssistantRevision
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao
interface AgentPersistenceDao {
    @Insert
    suspend fun insertChatRoom(chatRoom: ChatRoomV2): Long

    @Update
    suspend fun updateChatRoom(chatRoom: ChatRoomV2)

    @Insert
    suspend fun insertMessage(message: MessageV2): Long

    @Insert
    suspend fun insertRun(run: AgentRun)

    @Insert
    suspend fun insertToolEvent(event: ToolEvent)

    @Delete
    suspend fun deleteMessages(messages: List<MessageV2>)

    @Upsert
    suspend fun upsertModels(models: List<ChatPlatformModelV2>)

    @Query("SELECT * FROM chats_v2 WHERE chat_id = :chatId")
    suspend fun getChatRoom(chatId: Int): ChatRoomV2?

    @Query("SELECT * FROM messages_v2 WHERE chat_id = :chatId ORDER BY created_at, message_id")
    suspend fun rawGetMessages(chatId: Int): List<MessageV2>

    @Query("SELECT * FROM chat_platform_model_v2 WHERE chat_id = :chatId")
    suspend fun getModels(chatId: Int): List<ChatPlatformModelV2>

    @Query("SELECT * FROM agent_runs WHERE chat_id = :chatId AND status = 'COMPLETED' ORDER BY created_at, run_id")
    suspend fun getCompletedRuns(chatId: Int): List<AgentRun>

    @Query("SELECT failed.* FROM agent_runs failed WHERE failed.chat_id = :chatId AND failed.user_message_id IN (:userMessageIds) AND failed.status IN ('FAILED', 'CANCELED', 'INTERRUPTED') AND NOT EXISTS (SELECT 1 FROM agent_runs done WHERE done.assistant_message_id = failed.assistant_message_id AND done.status = 'COMPLETED' AND done.created_at > failed.created_at) ORDER BY failed.created_at, failed.run_id")
    suspend fun getIncompleteRuns(chatId: Int, userMessageIds: List<Int>): List<AgentRun>

    @Query("SELECT * FROM tool_events WHERE run_id IN (:runIds) ORDER BY run_id, sequence")
    suspend fun rawGetToolEvents(runIds: List<String>): List<ToolEvent>

    @Query("SELECT * FROM tool_events WHERE run_id = :runId ORDER BY sequence")
    suspend fun rawGetToolEventsForRun(runId: String): List<ToolEvent>

    @Query(
        """
        SELECT tool_events.*
        FROM tool_events
        INNER JOIN agent_runs ON agent_runs.run_id = tool_events.run_id
        WHERE agent_runs.chat_id = :chatId
        ORDER BY agent_runs.created_at, agent_runs.run_id, tool_events.sequence
        """
    )
    fun rawObserveToolEventsForChat(chatId: Int): Flow<List<ToolEvent>>

    @Query("SELECT * FROM tool_events WHERE event_id = :eventId")
    suspend fun rawGetToolEventById(eventId: String): ToolEvent?

    @Query("SELECT * FROM tool_events ORDER BY COALESCE(completed_at, started_at, 0) DESC, sequence DESC LIMIT :limit")
    fun rawObserveRecentToolEvents(limit: Int = 100): Flow<List<ToolEvent>>

    @Query(
        """
        UPDATE tool_events
        SET result = :result,
            result_type = :resultType,
            status = :status,
            is_error = :isError,
            completed_at = :completedAt,
            error = :error
        WHERE event_id = :eventId
            AND call_id = :callId
            AND status IN ('PENDING', 'RUNNING')
        """
    )
    suspend fun finishToolEvent(
        eventId: String,
        callId: String,
        result: String,
        resultType: String,
        status: String,
        isError: Boolean,
        completedAt: Long,
        error: String?
    ): Int

    @Query("UPDATE tool_events SET status = 'CANCELED', completed_at = :completedAt WHERE run_id = :runId AND status IN ('PENDING', 'RUNNING')")
    suspend fun cancelActiveToolEvents(runId: String, completedAt: Long)

    @Query(
        """
        UPDATE tool_events
        SET status = 'CANCELED',
            completed_at = :completedAt,
            error = COALESCE(error, 'INTERRUPTED_APP_STOPPED')
        WHERE status IN ('PENDING', 'RUNNING')
            AND run_id IN (SELECT run_id FROM agent_runs WHERE status = 'INTERRUPTED')
        """
    )
    suspend fun cancelInterruptedToolEvents(completedAt: Long)

    @Query("SELECT * FROM agent_runs WHERE run_id = :runId")
    suspend fun recoveryRun(runId: String): AgentRun?

    @Query("SELECT * FROM messages_v2 WHERE message_id = :messageId")
    suspend fun rawRecoveryMessage(messageId: Int): MessageV2?

    /** A canceled job or superseded assistant revision must never be resurrected. */
    @Transaction
    suspend fun restoreGatewayAnswer(runId: String, jobId: String, content: String, completedAt: Long): Boolean {
        val run = recoveryRun(runId) ?: return false
        if (run.gatewayJobId != jobId || run.status != "INTERRUPTED" || run.terminalError == "BACKUP_RESTORED") return false
        val message = recoveryMessage(run.assistantMessageId) ?: return false
        if (message.currentRunId != runId || message.chatId != run.chatId) return false
        updateMessage(message.copy(content = content, thoughts = "", timeline = emptyList()))
        updateRunStatus(runId, "COMPLETED", run.startedAt, completedAt, null)
        cancelActiveToolEvents(runId, completedAt)
        return true
    }

    @Query("SELECT * FROM pending_prompts WHERE id = :id AND userMessageId IS NULL")
    suspend fun pendingPrompt(id: String): dev.chungjungsoo.gptmobile.data.queue.PendingPrompt?

    @Query("UPDATE pending_prompts SET userMessageId = :messageId WHERE id = :id AND userMessageId IS NULL")
    suspend fun consumePrompt(id: String, messageId: Int): Int

    @Query("SELECT id FROM pending_prompts WHERE chatId = :chatId AND userMessageId IS NULL ORDER BY position, id LIMIT 1")
    suspend fun firstPendingId(chatId: Int): String?

    @Query("SELECT COUNT(*) FROM agent_runs WHERE chat_id = :chatId AND status IN ('QUEUED', 'RUNNING')")
    suspend fun activeRunCount(chatId: Int): Int

    @Query("SELECT * FROM messages_v2 WHERE chat_id = :chatId AND platform_type IS NOT NULL AND linked_message_id = (SELECT message_id FROM messages_v2 WHERE chat_id = :chatId AND platform_type IS NULL ORDER BY created_at DESC, message_id DESC LIMIT 1)")
    suspend fun rawLatestAssistantMessages(chatId: Int): List<MessageV2>

    /** Keep the handoff from primary replies to synthesis ahead of queued input. */
    @Transaction
    suspend fun queuedTurnReady(chatId: Int, pausedProfiles: Set<String> = emptySet()): Boolean {
        if (activeRunCount(chatId) > 0) return false
        val room = getChatRoom(chatId) ?: return false
        if (room.conversationMode != dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode.COMBINED) return true
        val participants = room.activePlatform.toSet() - pausedProfiles
        if (participants.size < 2) return true
        val replies = latestAssistantMessages(chatId).filter { it.platformType in participants }
        if (replies.mapNotNull { it.platformType }.toSet() != participants) return true
        val runIds = replies.map { it.currentRunId }
        if (runIds.any { it.isNullOrBlank() || it.startsWith("combined-synthesis:") }) return true
        if (runIds.any { recoveryRun(it!!) == null }) return true
        return replies.none {
            val content = it.effectiveContent().trim()
            content.isNotEmpty() && !dev.chungjungsoo.gptmobile.util.isAssistantErrorMessage(content)
        }
    }

    @Transaction
    suspend fun persistAgentTurn(request: PersistAgentTurnRequest): PersistAgentTurnResult {
        request.queuedPromptId?.let { id ->
            val pending = requireNotNull(pendingPrompt(id)) { "Queued input has already been dispatched or removed." }
            require(pending.chatId == request.chatRoom.id && !pending.paused)
            require(firstPendingId(pending.chatId) == id) { "Queue order changed." }
            require(queuedTurnReady(pending.chatId, request.queuedPausedProfiles)) { "Conversation is still running or awaiting its combined answer." }
            require(pending.text == request.userMessage.content && pending.details().attachments == request.userMessage.attachments) { "Queued input was edited." }
        }
        val chatRoom = if (request.chatRoom.id == 0) {
            request.chatRoom.copy(id = insertChatRoom(request.chatRoom).toInt())
        } else {
            val current = requireNotNull(getChatRoom(request.chatRoom.id))
            val submittedDraft = request.queuedPromptId == null &&
                current.draftText.orEmpty() == request.userMessage.content &&
                runCatching { kotlinx.serialization.json.Json.decodeFromString<List<dev.chungjungsoo.gptmobile.data.model.ChatAttachment>>(current.draftAttachments) }.getOrNull() == request.userMessage.attachments
            val merged = request.chatRoom.copy(draftText = if (submittedDraft) null else current.draftText, draftAttachments = if (submittedDraft) "[]" else current.draftAttachments, draftUpdatedAt = if (submittedDraft) null else current.draftUpdatedAt, lastShareToken = current.lastShareToken)
            updateChatRoom(merged)
            merged
        }
        val userMessage = request.userMessage.copy(chatId = chatRoom.id).let { message ->
            if (message.id == 0) {
                message.copy(id = insertMessage(message).toInt())
            } else {
                updateMessage(message)
                message
            }
        }
        val assistantMessages = request.runs.map { draft ->
            MessageV2(
                chatId = chatRoom.id,
                content = "",
                linkedMessageId = userMessage.id,
                platformType = draft.profileUid,
                currentRunId = draft.runId,
                createdAt = draft.createdAt
            ).let { it.copy(id = insertMessage(it).toInt()) }
        }
        val runs = request.runs.zip(assistantMessages) { draft, assistantMessage ->
            AgentRun(
                runId = draft.runId,
                chatId = chatRoom.id,
                userMessageId = userMessage.id,
                assistantMessageId = assistantMessage.id,
                profileUid = draft.profileUid,
                providerSnapshot = draft.providerSnapshot,
                modelSnapshot = draft.modelSnapshot,
                createdAt = draft.createdAt
            ).also { insertRun(it) }
        }
        upsertModels(
            request.chatPlatformModels.map { (profileUid, model) ->
                ChatPlatformModelV2(chatId = chatRoom.id, platformUid = profileUid, model = model)
            }
        )
        request.queuedPromptId?.let { check(consumePrompt(it, userMessage.id) == 1) }
        return PersistAgentTurnResult(chatRoom, userMessage, assistantMessages, runs)
    }

    @Transaction
    suspend fun saveChatSnapshot(
        chatRoom: ChatRoomV2,
        messages: List<MessageV2>,
        chatPlatformModels: Map<String, String>
    ) {
        require(chatRoom.id > 0)
        val current = requireNotNull(getChatRoom(chatRoom.id))
        updateChatRoom(chatRoom.copy(draftText = current.draftText, draftAttachments = current.draftAttachments, draftUpdatedAt = current.draftUpdatedAt, lastShareToken = current.lastShareToken))

        val incomingIds = messages.asSequence().map(MessageV2::id).filter { it > 0 }.toSet()
        val removedMessages = getMessages(chatRoom.id).filter { it.id !in incomingIds }
        if (removedMessages.isNotEmpty()) deleteMessages(removedMessages)

        messages.forEach { message ->
            val persisted = message.copy(chatId = chatRoom.id)
            if (persisted.id == 0) {
                insertMessage(persisted)
            } else {
                updateMessage(persisted)
            }
        }
        upsertModels(
            chatPlatformModels.map { (profileUid, model) ->
                ChatPlatformModelV2(chatId = chatRoom.id, platformUid = profileUid, model = model)
            }
        )
    }

    @Transaction
    suspend fun persistAgentRetry(request: PersistAgentRetryRequest): PersistAgentRetryResult {
        require(request.userMessage.id > 0 && request.assistantMessage.id > 0)
        require(request.userMessage.chatId == request.assistantMessage.chatId)

        val previousRevision = request.assistantMessage.snapshotLatestAssistantRevision(request.run.createdAt)
        val assistantMessage = request.assistantMessage.copy(
            content = "",
            thoughts = "",
            timeline = emptyList(),
            attachments = emptyList(),
            revisions = previousRevision
                ?.let { listOf(it) + request.assistantMessage.revisions }
                ?: request.assistantMessage.revisions,
            activeRevisionIndex = ACTIVE_REVISION_LATEST,
            currentRunId = request.run.runId,
            createdAt = request.run.createdAt
        )
        updateMessage(assistantMessage)

        val run = AgentRun(
            runId = request.run.runId,
            chatId = request.userMessage.chatId,
            userMessageId = request.userMessage.id,
            assistantMessageId = assistantMessage.id,
            profileUid = request.run.profileUid,
            providerSnapshot = request.run.providerSnapshot,
            modelSnapshot = request.run.modelSnapshot,
            createdAt = request.run.createdAt
        )
        insertRun(run)
        return PersistAgentRetryResult(assistantMessage, run)
    }

    @Transaction
    suspend fun finishAgentRun(
        assistantMessage: MessageV2,
        runId: String,
        status: String,
        startedAt: Long?,
        completedAt: Long?,
        terminalError: String?
    ) {
        updateMessage(assistantMessage)
        updateRunStatus(runId, status, startedAt, completedAt, terminalError)
        if (getChatRoom(assistantMessage.chatId)?.isArchived == true) setArchivedWithCompression(assistantMessage.chatId, true)
    }

    @Query(
        "UPDATE agent_runs SET status = :status, started_at = :startedAt, " +
            "completed_at = :completedAt, terminal_error = :terminalError WHERE run_id = :runId"
    )
    suspend fun updateRunStatus(
        runId: String,
        status: String,
        startedAt: Long?,
        completedAt: Long?,
        terminalError: String?
    )

    @Transaction
    suspend fun duplicateChatWithHistory(
        sourceChatId: Int,
        title: String,
        timestamp: Long,
        editedUser: MessageV2? = null
    ): ChatRoomV2 {
        val sourceChat = requireNotNull(getChatRoom(sourceChatId))
        val allMessages = getMessages(sourceChatId)
        require(editedUser == null || allMessages.any { it.id == editedUser.id && it.platformType == null }) { "The edited source message is unavailable." }
        val sourceMessages = if (editedUser == null) allMessages else allMessages.takeWhile { it.id != editedUser.id } + editedUser
        val sourceIds = sourceMessages.map { it.id }.toSet()
        val completedRuns = getCompletedRuns(sourceChatId).filter { it.userMessageId in sourceIds && it.assistantMessageId in sourceIds }
        val sourceEvents = if (completedRuns.isEmpty()) {
            emptyList()
        } else {
            getToolEvents(completedRuns.map { it.runId })
        }

        val duplicate = sourceChat.copy(
            id = 0,
            title = title,
            createdAt = timestamp,
            updatedAt = timestamp,
            parentChatId = if (editedUser != null) sourceChatId else sourceChat.parentChatId,
            branchMessageId = editedUser?.id ?: sourceChat.branchMessageId,
            draftText = null,
            draftAttachments = "[]",
            lastShareToken = null,
            draftUpdatedAt = null
        ).let { it.copy(id = insertChatRoom(it).toInt()) }

        val messageIdMap = sourceMessages.associate { source ->
            val insertedId = insertMessage(
                source.copy(
                    id = 0,
                    chatId = duplicate.id,
                    linkedMessageId = 0,
                    currentRunId = null
                )
            ).toInt()
            source.id to insertedId
        }
        val runIdMap = completedRuns.associate { it.runId to UUID.randomUUID().toString() }

        sourceMessages.forEach { source ->
            updateMessage(
                source.copy(
                    id = messageIdMap.getValue(source.id),
                    chatId = duplicate.id,
                    linkedMessageId = messageIdMap[source.linkedMessageId] ?: 0,
                    currentRunId = source.currentRunId?.let(runIdMap::get),
                    revisions = source.revisions.map { revision ->
                        revision.copy(runId = revision.runId?.let(runIdMap::get))
                    }
                )
            )
        }

        completedRuns.forEach { source ->
            insertRun(
                source.copy(
                    runId = runIdMap.getValue(source.runId),
                    chatId = duplicate.id,
                    userMessageId = messageIdMap.getValue(source.userMessageId),
                    assistantMessageId = messageIdMap.getValue(source.assistantMessageId)
                )
            )
        }
        sourceEvents.forEach { source ->
            insertToolEvent(
                source.copy(
                    eventId = UUID.randomUUID().toString(),
                    runId = runIdMap.getValue(source.runId)
                )
            )
        }
        upsertModels(
            getModels(sourceChatId).map { model ->
                model.copy(chatId = duplicate.id, updatedAt = timestamp)
            }
        )
        if (editedUser != null) {
            duplicate.activePlatform.forEach { uid ->
                insertMessage(MessageV2(chatId = duplicate.id, content = "", linkedMessageId = messageIdMap.getValue(editedUser.id), platformType = uid, createdAt = timestamp))
            }
        }
        return duplicate
    }

    @Query("SELECT run_id FROM agent_runs WHERE chat_id=:chatId")
    suspend fun archiveRunIds(chatId: Int): List<String>

    @Query("UPDATE messages_v2 SET content=:content, thoughts=:thoughts, revisions=:revisions, timeline=:timeline, combined_sources=:sources WHERE message_id=:id")
    suspend fun updateArchiveMessage(id: Int, content: String, thoughts: String, revisions: String, timeline: String, sources: String)

    @Query("UPDATE tool_events SET arguments=:arguments, result=:result, error=:error WHERE event_id=:id")
    suspend fun updateArchiveTool(id: String, arguments: String, result: String?, error: String?)

    @Query("UPDATE chats_v2 SET is_archived=:archived WHERE chat_id=:chatId")
    suspend fun updateArchiveFlag(chatId: Int, archived: Boolean)

    @Query("DELETE FROM messages_search WHERE docid IN (SELECT message_id FROM messages_v2 WHERE chat_id=:chatId)")
    suspend fun removeArchiveSearchIndex(chatId: Int)

    @Transaction
    suspend fun compactArchivedConversation(chatId: Int) {
        if (getChatRoom(chatId)?.isArchived == true) setArchivedWithCompression(chatId, true)
    }

    @Transaction
    suspend fun setArchivedWithCompression(chatId: Int, archived: Boolean) {
        fun stored(value: String): String = if (archived) dev.chungjungsoo.gptmobile.data.chat.ArchivedTextCodec.encode(value) else value
        getMessages(chatId).forEach { message ->
            updateArchiveMessage(
                message.id,
                stored(message.content),
                stored(message.thoughts),
                stored(dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevisionListConverter().fromList(message.revisions)),
                stored(dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineListConverter().fromList(message.timeline)),
                stored(dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponseListConverter().fromList(message.combinedSources))
            )
        }
        val runs = archiveRunIds(chatId)
        if (runs.isNotEmpty()) {
            getToolEvents(runs).forEach { event ->
                updateArchiveTool(event.eventId, stored(event.arguments), event.result?.let(::stored), event.error?.let(::stored))
            }
        }
        updateArchiveFlag(chatId, archived)
        // Archived text is searched lazily after decompression; compressed text needs no FTS index.
        if (archived) removeArchiveSearchIndex(chatId)
    }

    @Update
    suspend fun updateMessage(message: MessageV2)

    suspend fun getMessages(chatId: Int): List<MessageV2> = rawGetMessages(chatId).map { it.decodedArchiveText() }

    suspend fun getToolEvents(runIds: List<String>): List<ToolEvent> = rawGetToolEvents(runIds).map { it.decodedArchiveText() }

    suspend fun getToolEventsForRun(runId: String): List<ToolEvent> = rawGetToolEventsForRun(runId).map { it.decodedArchiveText() }

    fun observeToolEventsForChat(chatId: Int): Flow<List<ToolEvent>> = rawObserveToolEventsForChat(chatId).map { messages -> messages.map { it.decodedArchiveText() } }

    suspend fun getToolEventById(eventId: String): ToolEvent? = rawGetToolEventById(eventId)?.decodedArchiveText()

    fun observeRecentToolEvents(limit: Int = 100): Flow<List<ToolEvent>> = rawObserveRecentToolEvents(limit).map { messages -> messages.map { it.decodedArchiveText() } }

    suspend fun recoveryMessage(messageId: Int): MessageV2? = rawRecoveryMessage(messageId)?.decodedArchiveText()

    suspend fun latestAssistantMessages(chatId: Int): List<MessageV2> = rawLatestAssistantMessages(chatId).map { it.decodedArchiveText() }
}
