package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import dev.chungjungsoo.gptmobile.data.agent.liveRevisionId
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

    @Query("DELETE FROM chat_platform_model_v2 WHERE chat_id = :chatId AND platform_uid IN (:profileUids)")
    suspend fun deleteCombinedModels(chatId: Int, profileUids: List<String>)

    /** Own only first-tab fields. Immediate privacy, tool, draft and archive changes survive. */
    @Transaction
    suspend fun applyCombinedSettings(
        previous: dev.chungjungsoo.gptmobile.data.model.CombinedChatSettingsDraft,
        next: dev.chungjungsoo.gptmobile.data.model.CombinedChatSettingsDraft
    ): ChatRoomV2 {
        require(previous.chatId == next.chatId && previous.conversationIdentity == next.conversationIdentity)
        val current = requireNotNull(getChatRoom(next.chatId)) { "The conversation was deleted." }
        val models = getModels(next.chatId).associate { it.platformUid to it.model }
        val affected = previous.models.keys + next.models.keys
        val nextPrimary = next.primaryProfileUid?.takeIf { it in next.participantUids }
            ?: current.combinedPrimaryUid?.takeIf { it in next.participantUids } ?: next.participantUids.firstOrNull()
        val alreadyApplied = current.activePlatform == next.participantUids &&
            current.combinedPrimaryUid == nextPrimary &&
            affected.all { uid ->
                models[uid] == next.models[uid] || models[uid] == null && next.models[uid] == previous.models[uid]
            }
        if (alreadyApplied) return current
        check(current.activePlatform == previous.participantUids) { "Conversation participants changed during save." }
        check(affected.all { uid -> models[uid] == null || models[uid] == previous.models[uid] }) { "Conversation models changed during save." }
        val updated = current.copy(
            enabledPlatform = (current.enabledPlatform + next.participantUids).distinct(),
            activePlatform = next.participantUids,
            combinedPrimaryUid = nextPrimary,
            updatedAt = System.currentTimeMillis() / 1000
        )
        require(updated.activePlatform.isNotEmpty()) { "Select at least one AI profile." }
        updateChatRoom(updated)
        val removed = (previous.models.keys - next.models.keys).toList()
        if (removed.isNotEmpty()) deleteCombinedModels(next.chatId, removed)
        upsertModels(next.models.map { (uid, model) -> ChatPlatformModelV2(chatId = next.chatId, platformUid = uid, model = model) })
        return updated
    }

    @Query("SELECT * FROM agent_runs WHERE chat_id = :chatId AND user_message_id = :userId AND synthesis_key IS NOT NULL")
    suspend fun synthesisRunsForTurn(chatId: Int, userId: Int): List<AgentRun>

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

    @Query("SELECT * FROM tool_events WHERE run_id = :runId AND call_id = :callId ORDER BY started_at LIMIT 1")
    suspend fun rawGetToolEventByCall(runId: String, callId: String): ToolEvent?

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

    @Query("SELECT * FROM combined_delivery_checkpoints WHERE runId = :runId AND revisionId = :revisionId")
    suspend fun combinedDeliveryCheckpoint(runId: String, revisionId: String): dev.chungjungsoo.gptmobile.data.database.entity.CombinedDeliveryCheckpoint?

    @Upsert
    suspend fun upsertCombinedDeliveryCheckpoint(row: dev.chungjungsoo.gptmobile.data.database.entity.CombinedDeliveryCheckpoint)

    @Transaction
    suspend fun commitCombinedDeliveryCheckpoint(row: dev.chungjungsoo.gptmobile.data.database.entity.CombinedDeliveryCheckpoint, expectedGeneration: Long, synthesisKey: String): Boolean {
        val run = recoveryRun(row.runId) ?: return false
        if (run.status != "RUNNING" || run.ownershipFence != row.fence || run.synthesisKey != synthesisKey || !validCombinedPublication(run)) return false
        val message = rawRecoveryMessage(run.assistantMessageId) ?: return false
        if (message.currentRunId != row.runId || message.ownershipFence != row.fence) return false
        val old = combinedDeliveryCheckpoint(row.runId, row.revisionId)
        if ((old?.generation ?: 0L) != expectedGeneration || row.generation != Math.addExact(expectedGeneration, 1)) return false
        val state = kotlinx.serialization.json.Json.decodeFromString<dev.chungjungsoo.gptmobile.data.agent.CombinedRecordCheckpoint>(row.stateJson)
        require(state.revisionId == row.revisionId && state.snapshotDigest == row.snapshotDigest)
        val sealed = kotlinx.serialization.json.Json.decodeFromString<dev.chungjungsoo.gptmobile.data.agent.CombinedInputSnapshot>(row.snapshotJson)
        require(sealed.inputDigest == row.snapshotDigest && sealed.contentDigest() == row.snapshotDigest)
        old?.let {
            require(it.snapshotDigest == row.snapshotDigest && it.fence == row.fence)
            val previous = kotlinx.serialization.json.Json.decodeFromString<dev.chungjungsoo.gptmobile.data.agent.CombinedRecordCheckpoint>(it.stateJson)
            require(state.records.take(previous.records.size) == previous.records) { "A committed record cannot be rewritten" }
        }
        val quality = kotlinx.serialization.json.Json.decodeFromString<dev.chungjungsoo.gptmobile.data.agent.CombinedQualityReport>(row.attributionJson)
        require(quality.answerDigest == dev.chungjungsoo.gptmobile.data.research.researchHash(row.renderedAnswer) && quality.passages.all { it.matches(row.renderedAnswer) })
        upsertCombinedDeliveryCheckpoint(row)
        return true
    }

    @Query("SELECT * FROM answer_segments WHERE runId = :runId ORDER BY sequence")
    suspend fun answerSegments(runId: String): List<dev.chungjungsoo.gptmobile.data.database.entity.AnswerSegment>

    @Query("SELECT answer_segments.* FROM answer_segments JOIN agent_runs ON agent_runs.run_id = answer_segments.runId WHERE agent_runs.chat_id = :chatId ORDER BY answer_segments.runId, answer_segments.sequence")
    suspend fun answerSegmentsForChat(chatId: Int): List<dev.chungjungsoo.gptmobile.data.database.entity.AnswerSegment>

    @Insert
    suspend fun insertAnswerSegments(segments: List<dev.chungjungsoo.gptmobile.data.database.entity.AnswerSegment>)

    @Query("UPDATE answer_segments SET runId = :archivedRunId WHERE runId = :runId")
    suspend fun archiveCombinedSegments(runId: String, archivedRunId: String)

    @Query("UPDATE agent_runs SET ownership_fence = :nextFence WHERE run_id = :runId AND ownership_fence = :expectedFence AND status = 'RUNNING'")
    suspend fun advanceCombinedFence(runId: String, expectedFence: Long, nextFence: Long): Int

    /** A Research fallback replaces one exact draft under the live run's ownership fence. */
    @Transaction
    suspend fun replaceResearchDraft(runId: String, expectedFence: Long, previousDigest: String, text: String): MessageV2? {
        val run = recoveryRun(runId) ?: return null
        if (run.status != "RUNNING" || run.ownershipFence != expectedFence || runId.startsWith("combined-synthesis:")) return null
        val current = rawRecoveryMessage(run.assistantMessageId) ?: return null
        if (current.currentRunId != runId || current.ownershipFence != expectedFence || dev.chungjungsoo.gptmobile.data.research.researchHash(current.content) != previousDigest) return null
        require(text.isNotBlank())
        val nextFence = Math.addExact(expectedFence, 1L)
        check(advanceCombinedFence(runId, expectedFence, nextFence) == 1)
        val now = System.currentTimeMillis() / 1000
        val previous = current.snapshotLatestAssistantRevision(now)
        val timeline = current.timeline.filterNot { it.type == dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType.TEXT } +
            dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem(dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType.TEXT, content = text)
        val replacement = current.copy(content = text, timeline = timeline, ownershipFence = nextFence, revisions = listOfNotNull(previous) + current.revisions, activeRevisionIndex = ACTIVE_REVISION_LATEST)
        updateMessage(replacement)
        return replacement
    }

    /** Archive old bytes and citations, advance ownership, and replace the live revision together. */
    @Transaction
    suspend fun replaceCombinedAnswer(
        runId: String,
        expectedFence: Long,
        candidate: dev.chungjungsoo.gptmobile.data.agent.CombinedReplacementCandidate
    ): MessageV2? {
        val run = recoveryRun(runId) ?: return null
        if (!runId.startsWith("combined-synthesis:") || run.status != "RUNNING" || run.ownershipFence != expectedFence) return null
        val current = rawRecoveryMessage(run.assistantMessageId) ?: return null
        if (current.currentRunId != runId || current.ownershipFence != expectedFence) return null
        val report = current.timeline.lastOrNull { it.combinedQuality != null }?.combinedQuality ?: return null
        if (report.revisionId != candidate.previousRevisionId || dev.chungjungsoo.gptmobile.data.research.researchHash(current.content) != candidate.previousDigest) return null
        require(candidate.text.isNotBlank() && candidate.revisionId.isNotBlank() && candidate.revisionId != candidate.previousRevisionId)
        require(candidate.report.revisionId == candidate.revisionId && candidate.report.answerDigest == dev.chungjungsoo.gptmobile.data.research.researchHash(candidate.text))
        val nextFence = Math.addExact(expectedFence, 1L)
        val archiveId = "combined-synthesis:revision:${candidate.previousRevisionId}:${java.util.UUID.randomUUID()}"
        val now = System.currentTimeMillis() / 1000
        insertRun(
            run.copy(
                runId = archiveId,
                status = "COMPLETED",
                completedAt = now,
                terminalError = "REVISION_REPLACED",
                gatewayJobId = null,
                gatewayBaseUrl = null,
                gatewayLastSequence = -1,
                inputTokens = null,
                outputTokens = null,
                totalTokens = null,
                displayRole = "INTERNAL"
            )
        )
        archiveCombinedSegments(runId, archiveId)
        check(advanceCombinedFence(runId, expectedFence, nextFence) == 1)
        val timeline = current.timeline.filterNot {
            it.type == dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType.TEXT || it.combinedQuality != null
        } + listOf(
            dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem(dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType.TEXT, content = candidate.text),
            dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem(dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType.NOTICE, combinedQuality = candidate.report)
        )
        val previous = current.snapshotLatestAssistantRevision(now)?.copy(runId = archiveId)
        val replaced = current.copy(
            content = candidate.text,
            timeline = timeline,
            ownershipFence = nextFence,
            revisions = listOfNotNull(previous) + current.revisions,
            activeRevisionIndex = ACTIVE_REVISION_LATEST,
            createdAt = now
        )
        updateMessage(replaced)
        preserveCombinedPrefix(replaced)
        return replaced
    }

    suspend fun preserveCombinedPrefix(message: MessageV2) {
        val runId = message.currentRunId ?: return
        if (!runId.startsWith("combined-synthesis:")) return
        val additions = dev.chungjungsoo.gptmobile.data.agent.AnswerSegmentChain.append(answerSegments(runId), runId, message.ownershipFence, message.content)
        if (additions.isNotEmpty()) insertAnswerSegments(additions)
    }

    @Query("SELECT * FROM agent_runs WHERE run_id = :runId")
    suspend fun recoveryRun(runId: String): AgentRun?

    @Query("SELECT * FROM messages_v2 WHERE message_id = :messageId")
    suspend fun rawRecoveryMessage(messageId: Int): MessageV2?

    @Transaction
    suspend fun restoreCombinedCandidate(runId: String): MessageV2? {
        val run = recoveryRun(runId) ?: return null
        if (run.status !in setOf("FAILED", "INTERRUPTED", "CANCELED")) return null
        val current = recoveryMessage(run.assistantMessageId) ?: return null
        if (current.currentRunId != runId || current.ownershipFence != run.ownershipFence || current.content.isNotBlank() || current.revisions.getOrNull(current.activeRevisionIndex)?.content?.isNotBlank() == true) return current
        val raw = run.synthesisSnapshot ?: return current
        if (run.synthesisKey != dev.chungjungsoo.gptmobile.data.research.researchHash(raw)) return current
        val snapshot = runCatching { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<dev.chungjungsoo.gptmobile.data.agent.CombinedInputSnapshot>(raw) }.getOrNull() ?: return current
        val source = snapshot.sources.firstOrNull { it.platformUid == current.platformType && it.content.isNotBlank() } ?: return current
        val existing = current.revisions.indexOfFirst { revision ->
            revision.runId == source.attemptId && (source.rawContentDigest.ifBlank { source.sourceDigest } == dev.chungjungsoo.gptmobile.data.research.researchHash(revision.content) || source.content == revision.content)
        }
        val restored = if (existing >= 0) {
            current.copy(activeRevisionIndex = existing)
        } else {
            current.copy(
                revisions = current.revisions + dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision(source.content, createdAt = source.createdAt ?: run.createdAt, runId = source.attemptId),
                activeRevisionIndex = current.revisions.size
            )
        }
        updateMessage(restored)
        return restored
    }

    @Transaction
    suspend fun publishCombinedFallback(runId: String, expectedFence: Long, sourceProfileUid: String? = null): MessageV2? {
        val prior = recoveryRun(runId) ?: return null
        val current = recoveryMessage(prior.assistantMessageId) ?: return null
        if (current.currentRunId != runId || current.ownershipFence != expectedFence || prior.ownershipFence != expectedFence) return null
        if (sourceProfileUid != null && prior.status !in setOf("COMPLETED", "FAILED", "INTERRUPTED")) return null
        if (sourceProfileUid == null && prior.status != "QUEUED") return null
        val raw = prior.synthesisSnapshot ?: return null
        if (prior.synthesisKey != dev.chungjungsoo.gptmobile.data.research.researchHash(raw)) return null
        val snapshot = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<dev.chungjungsoo.gptmobile.data.agent.CombinedInputSnapshot>(raw)
        if (snapshot.version >= 6 && snapshot.inputDigest != snapshot.contentDigest()) return null
        val original = recoveryMessage(prior.userMessageId) ?: return null
        if (original != snapshot.userMessage) return null
        val source = sourceProfileUid?.let { uid -> snapshot.sources.singleOrNull { it.platformUid == uid && it.content.isNotBlank() } ?: return null }
        val text = source?.content ?: snapshot.evidence.records.filter { it.readable }.joinToString("\n\n") { record ->
            "### ${record.title.ifBlank { record.id }}\n${record.url}\n\n${record.passage}"
        }.let { passages -> if (passages.isBlank()) "" else "Saved source excerpts\n\nA final report is unavailable. These retained excerpts are evidence for retrying the writer; they have not been assembled into a checked report.\n\n$passages" }
        val at = System.currentTimeMillis() / 1000
        val target = if (source != null) {
            val resumed = snapshot.copy(resumeFromRunId = runId, committedPrefix = "", deliveryResumeFromRevisionId = if (snapshot.deliveryProtocol == "PASSAGES_V3") snapshot.liveRevisionId(runId) else null)
            val resumedRaw = resumed.encode()
            persistAgentRetry(PersistAgentRetryRequest(original, current, dev.chungjungsoo.gptmobile.data.database.entity.AgentRunDraft("combined-synthesis:" + UUID.randomUUID(), prior.profileUid, prior.providerSnapshot, prior.modelSnapshot, at, dev.chungjungsoo.gptmobile.data.research.researchHash(resumedRaw), resumedRaw), resumeCombinedFrom = runId)).assistantMessage
        } else {
            current
        }
        val receipt = dev.chungjungsoo.gptmobile.data.agent.CombinedFallbackReceipt(if (source != null) "USE_REPLY" else "EVIDENCE_ONLY", source?.platformUid, source?.attemptId, source?.completionStatus, dev.chungjungsoo.gptmobile.data.research.researchHash(text), snapshot.snapshotId, runId)
        val report = dev.chungjungsoo.gptmobile.data.agent.CombinedQualityReport(answerDigest = dev.chungjungsoo.gptmobile.data.research.researchHash(text), status = "PARTIAL", deliveredScopeStatus = "PARTIAL", revisionId = UUID.randomUUID().toString(), issues = listOf(if (source != null) "EXPLICIT_CONTRIBUTOR_FALLBACK" else "NO_AUTHORIZED_WRITER"))
        val published = target.copy(
            content = text,
            activeRevisionIndex = ACTIVE_REVISION_LATEST,
            timeline = listOf(
                dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem(dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType.TEXT, content = text),
                dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem(
                    dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType.NOTICE,
                    combinedQuality = report,
                    combinedFallback = receipt,
                    researchDeliveryReceipt = if (source == null) {
                        dev.chungjungsoo.gptmobile.data.research.delivery.ResearchDeliveryReceipt(
                            reportValidity = if (text.isNotBlank()) dev.chungjungsoo.gptmobile.data.research.delivery.ResearchReportValidity.EVIDENCE_ONLY else dev.chungjungsoo.gptmobile.data.research.delivery.ResearchReportValidity.INCOMPLETE,
                            evidenceAvailable = snapshot.evidence.records.any { it.readable },
                            packetDigest = snapshot.inputDigest,
                            revisionId = dev.chungjungsoo.gptmobile.data.research.researchHash(text),
                            issueCodes = listOf("NO_AUTHORIZED_WRITER"),
                            sourceIds = snapshot.evidence.records.filter { it.readable }.map { it.id },
                            retryEligible = true
                        )
                    } else {
                        null
                    }
                )
            )
        )
        return if (finishRunningWithMessage(published, requireNotNull(target.currentRunId), "FAILED", at, if (source != null) "EXPLICIT_CONTRIBUTOR_FALLBACK" else "NO_AUTHORIZED_WRITER")) published else null
    }

    /** A canceled job or superseded assistant revision must never be resurrected. */
    @Transaction
    suspend fun restoreGatewayAnswer(runId: String, jobId: String, content: String, completedAt: Long): Boolean {
        val run = recoveryRun(runId) ?: return false
        if (run.gatewayJobId != jobId || run.status != "INTERRUPTED" || run.terminalError == "BACKUP_RESTORED") return false
        val message = recoveryMessage(run.assistantMessageId) ?: return false
        if (message.currentRunId != runId || message.chatId != run.chatId || message.ownershipFence != run.ownershipFence) return false
        val restored = message.copy(content = content, thoughts = "", timeline = emptyList())
        updateMessage(restored)
        publishReply(run, restored, "COMPLETED", completedAt)
        updateRunStatus(runId, "COMPLETED", run.startedAt, completedAt, null)
        cancelActiveToolEvents(runId, completedAt)
        return true
    }

    @Query("SELECT * FROM reply_publications WHERE logicalReplyKey = :key")
    suspend fun publication(key: String): dev.chungjungsoo.gptmobile.data.chat.ReplyPublication?

    @Query("SELECT COALESCE(MAX(publicationSequence), 0) + 1 FROM reply_publications")
    suspend fun nextPublicationSequence(): Long

    @Upsert
    suspend fun savePublication(row: dev.chungjungsoo.gptmobile.data.chat.ReplyPublication)

    suspend fun publishReply(run: AgentRun, message: MessageV2, status: String, at: Long) {
        if (!dev.chungjungsoo.gptmobile.data.chat.ReplyDisplayRole.visible(run.displayRole)) return
        if (status != "COMPLETED" && status !in setOf("FAILED", "CANCELED", "INTERRUPTED")) return
        val answerText = dev.chungjungsoo.gptmobile.util.stripAssistantErrorNote(message.content)
        // A host-created error message alone is not a partial answer publication.
        if (status != "COMPLETED" && answerText == message.content && dev.chungjungsoo.gptmobile.util.isAssistantErrorMessage(message.content)) return
        val content = dev.chungjungsoo.gptmobile.data.conversation.AnswerProjection.text(answerText)
        if (content.isBlank()) return
        val channel = if (run.displayRole == dev.chungjungsoo.gptmobile.data.chat.ReplyDisplayRole.COMBINED) "combined" else run.profileUid
        val key = "${run.chatId}:${run.userMessageId}:$channel"
        val previous = publication(key)
        if (previous != null && previous.ownerFence > run.ownershipFence) return
        val quality = message.timeline.lastOrNull { it.combinedQuality != null }?.combinedQuality
        val limitedCombinedDelivery = run.displayRole == dev.chungjungsoo.gptmobile.data.chat.ReplyDisplayRole.COMBINED &&
            quality?.let {
                !it.correctionRequired &&
                    it.complete &&
                    it.deliveredScopeStatus in setOf("COMPLETED", "COMPLETED_WITH_LIMITS") &&
                    it.answerDigest == dev.chungjungsoo.gptmobile.data.research.researchHash(message.content) &&
                    it.issues.none { issue -> issue in dev.chungjungsoo.gptmobile.data.agent.COMBINED_FRAMING_FAILURES }
            } != true
        val kind = if (status == "COMPLETED" && !limitedCombinedDelivery) "FINAL" else "PARTIAL"
        val digest = dev.chungjungsoo.gptmobile.data.research.researchHash(content)
        if (previous?.contentDigest == digest && previous.deliveryKind == kind && previous.ownerFence == run.ownershipFence) return
        savePublication(
            dev.chungjungsoo.gptmobile.data.chat.ReplyPublication(
                key, run.chatId, message.id, run.userMessageId, run.profileUid, run.displayRole, run.ownershipFence,
                (previous?.committedRevision ?: 0L) + 1, at, nextPublicationSequence(),
                previous?.acknowledgedRevision ?: 0L, kind, digest
            )
        )
    }

    @Query("SELECT * FROM pending_prompts WHERE id = :id AND userMessageId IS NULL")
    suspend fun pendingPrompt(id: String): dev.chungjungsoo.gptmobile.data.queue.PendingPrompt?

    @Query("UPDATE pending_prompts SET userMessageId = :messageId WHERE id = :id AND userMessageId IS NULL")
    suspend fun consumePrompt(id: String, messageId: Int): Int

    @Query("SELECT id FROM pending_prompts WHERE chatId = :chatId AND userMessageId IS NULL ORDER BY position, id LIMIT 1")
    suspend fun firstPendingId(chatId: Int): String?

    @Transaction
    suspend fun markCombinedMergeUnavailable(chatId: Int, userMessageId: Int, rootBudgetId: String): Boolean {
        val rows = rawGetMessages(chatId).filter { it.linkedMessageId == userMessageId && it.platformType != null }
        if (rows.any { row -> row.currentRunId?.let { recoveryRun(it)?.status } in setOf("QUEUED", "RUNNING") }) return false
        val matching = rows.filter { row -> row.timeline.any { it.combinedTurn?.rootBudgetId == rootBudgetId } }
        if (matching.isEmpty()) return false
        matching.forEach { row ->
            updateMessage(
                row.copy(
                    timeline = row.timeline.map { item ->
                        item.combinedTurn?.takeIf { it.rootBudgetId == rootBudgetId }?.let { marker ->
                            item.copy(combinedTurn = marker.copy(deliveryStatus = "MERGE_UNAVAILABLE"))
                        } ?: item
                    }
                )
            )
        }
        return true
    }

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
        val latest = latestAssistantMessages(chatId)
        val accepted = latest.asSequence().flatMap { it.timeline.asSequence() }.mapNotNull { it.combinedTurn }.firstOrNull()
        if (latest.any { it.timeline.any { item -> item.combinedTurn?.deliveryStatus == "MERGE_UNAVAILABLE" } }) return true
        if (accepted != null &&
            latest.filter { it.timeline.any { item -> item.combinedTurn != null } }.all {
                it.timeline.any { item -> item.combinedTurn?.deliveryStatus == "CANDIDATE_CANCELED" }
            }
        ) {
            return true
        }
        val participants = accepted?.participantUids?.toSet() ?: (room.activePlatform.toSet() - pausedProfiles)
        if (participants.isEmpty()) return true
        val replies = latest.filter { it.platformType in participants }
        if (replies.mapNotNull { it.platformType }.toSet() != participants) return true
        val runIds = replies.map { it.currentRunId }
        if (runIds.any { it.isNullOrBlank() || it.startsWith("combined-synthesis:") }) return true
        if (runIds.any { recoveryRun(it!!) == null }) return true
        return replies.none {
            val content = it.effectiveContent().trim()
            content.isNotEmpty() && !dev.chungjungsoo.gptmobile.util.isAssistantErrorMessage(content)
        }
    }

    /** Fresh user-authorized scope, without copying any saved response/evidence/attachments. */
    @Transaction
    suspend fun createEvidenceFreeRetry(sourceChatId: Int, question: String, profileUid: String, model: String, now: Long): ChatRoomV2 {
        val source = requireNotNull(getChatRoom(sourceChatId)) { "The source conversation was deleted." }
        require(question.isNotBlank() && profileUid.isNotBlank())
        val draft = ChatRoomV2(
            title = question.replace('\n', ' ').take(50),
            enabledPlatform = listOf(profileUid),
            activePlatform = listOf(profileUid),
            createdAt = now,
            updatedAt = now,
            isTemporary = source.isTemporary
        )
        val room = draft.copy(id = insertChatRoom(draft).toInt())
        val user = insertMessage(MessageV2(chatId = room.id, content = question, platformType = null, createdAt = now)).toInt()
        insertMessage(MessageV2(chatId = room.id, content = "", platformType = profileUid, linkedMessageId = user, createdAt = now))
        upsertModels(listOf(ChatPlatformModelV2(room.id, profileUid, model, now)))
        return room
    }

    @Transaction
    suspend fun persistAgentTurn(request: PersistAgentTurnRequest): PersistAgentTurnResult {
        request.queuedPromptId?.let { id ->
            val pending = requireNotNull(pendingPrompt(id)) { "Queued input has already been dispatched or removed." }
            require(pending.chatId == request.chatRoom.id && !pending.paused)
            val currentRoom = requireNotNull(getChatRoom(pending.chatId))
            val accepted = pending.details().tools.combined
            val permitted = accepted?.requested?.participantUids ?: currentRoom.activePlatform
            require(request.runs.isNotEmpty() && request.runs.all { it.profileUid in permitted && it.profileUid !in request.queuedPausedProfiles }) { "A queued profile was removed or paused. Review the queued input before resuming it." }
            if (accepted != null) {
                require(
                    request.runs.all { draft ->
                        val frozen = dev.chungjungsoo.gptmobile.data.agent.CombinedAcceptedTurn.context(draft.synthesisSnapshot)
                        frozen?.requested == accepted.requested && draft.modelSnapshot == accepted.requested.requestedModels[draft.profileUid]
                    }
                ) { "Queued Combined policy changed during dispatch." }
            }
            require(firstPendingId(pending.chatId) == id) { "Queue order changed." }
            require(queuedTurnReady(pending.chatId, request.queuedPausedProfiles)) { "Conversation is still running or awaiting its combined answer." }
            require(pending.text == request.userMessage.content && pending.details().attachments == request.userMessage.attachments) { "Queued input was edited." }
        }
        val pinnedRequestRoom = request.chatRoom.let { room ->
            if (room.conversationMode == dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode.COMBINED && room.combinedPrimaryUid == null) room.copy(combinedPrimaryUid = room.activePlatform.firstOrNull()) else room
        }
        val chatRoom = if (request.chatRoom.id == 0) {
            pinnedRequestRoom.copy(id = insertChatRoom(pinnedRequestRoom).toInt())
        } else {
            val current = requireNotNull(getChatRoom(request.chatRoom.id))
            val submittedDraft = request.queuedPromptId == null &&
                current.draftText.orEmpty() == request.userMessage.content &&
                runCatching { kotlinx.serialization.json.Json.decodeFromString<List<dev.chungjungsoo.gptmobile.data.model.ChatAttachment>>(current.draftAttachments) }.getOrNull() == request.userMessage.attachments
            // A queue worker may have read this room before the user changed membership.
            val roomSnapshot = (if (request.queuedPromptId == null) pinnedRequestRoom else current).let { room ->
                if (room.conversationMode == dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode.COMBINED) room.copy(combinedPrimaryUid = current.combinedPrimaryUid ?: room.combinedPrimaryUid ?: room.activePlatform.firstOrNull()) else room
            }
            val merged = roomSnapshot.copy(draftText = if (submittedDraft) null else current.draftText, draftAttachments = if (submittedDraft) "[]" else current.draftAttachments, draftUpdatedAt = if (submittedDraft) null else current.draftUpdatedAt, lastShareToken = current.lastShareToken)
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
                createdAt = draft.createdAt,
                timeline = listOfNotNull(
                    dev.chungjungsoo.gptmobile.data.agent.CombinedAcceptedTurn.context(draft.synthesisSnapshot)?.let { context ->
                        dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem(
                            type = dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType.NOTICE,
                            combinedTurn = dev.chungjungsoo.gptmobile.data.context.CombinedTurnHistory.accepted(context.copy(chatId = chatRoom.id, profileUid = draft.profileUid))
                        )
                    }
                )
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
                createdAt = draft.createdAt,
                synthesisKey = draft.synthesisKey,
                synthesisSnapshot = dev.chungjungsoo.gptmobile.data.agent.CombinedAcceptedTurn.decode(draft.synthesisSnapshot)?.let { accepted ->
                    val task = dev.chungjungsoo.gptmobile.data.agent.AcceptedTaskRef.from(userMessage)
                    val rebound = accepted.context.copy(chatId = chatRoom.id, acceptedTask = task)
                    accepted.copy(context = rebound, tools = accepted.tools.copy(combined = rebound), acceptedTask = task).encode()
                } ?: draft.synthesisSnapshot,
                displayRole = draft.displayRole ?: when {
                    chatRoom.conversationMode == dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode.COMBINED -> "COMBINED_CONTRIBUTOR"
                    request.runs.size > 1 -> "MULTI_REPLY"
                    else -> "DIRECT_REPLY"
                }
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
    suspend fun regenerateCombinedTurn(
        userMessage: MessageV2,
        expectedOwner: MessageV2,
        drafts: List<dev.chungjungsoo.gptmobile.data.database.entity.AgentRunDraft>,
        accepted: dev.chungjungsoo.gptmobile.data.agent.CombinedAcceptedTurn
    ): PersistAgentTurnResult {
        val room = requireNotNull(getChatRoom(userMessage.chatId)) { "The conversation was deleted." }
        require(room.conversationMode == "COMBINED" && accepted.context.chatId == room.id && accepted.context.plan != null)
        require(accepted.context.requested.userPrompt == userMessage.content)
        require(drafts.map { it.profileUid } == accepted.context.requested.participantUids && drafts.map { it.runId }.distinct().size == drafts.size)
        require(drafts.all { it.modelSnapshot == accepted.context.requested.requestedModels[it.profileUid] && it.synthesisKey == null })
        val messages = rawGetMessages(room.id)
        val user = requireNotNull(messages.singleOrNull { it.id == userMessage.id && it.platformType == null })
        require(user.content == userMessage.content && user.attachments == userMessage.attachments) { "The original question changed before regeneration." }
        val rows = messages.filter { it.linkedMessageId == user.id && it.platformType != null }
        val owner = requireNotNull(rows.singleOrNull { it.id == expectedOwner.id })
        require(owner.currentRunId == expectedOwner.currentRunId && owner.ownershipFence == expectedOwner.ownershipFence) { "The answer changed before regeneration." }
        require(rows.none { row -> row.currentRunId?.let { recoveryRun(it)?.status } in setOf("RUNNING", "QUEUED") }) { "Stop all current contributors before regenerating." }
        require(drafts.all { it.profileUid in room.activePlatform }) { "Combined participants changed before regeneration." }
        val selected = drafts.mapTo(hashSetOf()) { it.profileUid }
        rows.filter { it.platformType !in selected }.forEach { old ->
            updateMessage(
                old.copy(
                    content = "", thoughts = "", timeline = emptyList(), combinedSources = emptyList(), attachments = emptyList(),
                    currentRunId = null, ownershipFence = Math.addExact(old.ownershipFence, 1L),
                    activeRevisionIndex = ACTIVE_REVISION_LATEST,
                    revisions = listOfNotNull(old.snapshotLatestAssistantRevision()) + old.revisions
                )
            )
        }
        val record = accepted.encode()
        val created = drafts.map { draft ->
            val prior = rows.singleOrNull { it.platformType == draft.profileUid } ?: MessageV2(
                chatId = room.id,
                platformType = draft.profileUid,
                linkedMessageId = user.id,
                content = ""
            ).let { it.copy(id = insertMessage(it).toInt()) }
            persistAgentRetry(
                PersistAgentRetryRequest(
                    userMessage = user,
                    assistantMessage = prior.copy(combinedSources = emptyList()),
                    run = draft.copy(synthesisSnapshot = record, displayRole = "COMBINED_CONTRIBUTOR")
                )
            )
        }
        return PersistAgentTurnResult(room, user, created.map { it.assistantMessage }, created.map { it.run })
    }

    @Transaction
    suspend fun persistAgentRetry(request: PersistAgentRetryRequest): PersistAgentRetryResult {
        require(request.userMessage.id > 0 && request.assistantMessage.id > 0)
        require(request.userMessage.chatId == request.assistantMessage.chatId)

        val current = requireNotNull(recoveryMessage(request.assistantMessage.id)) { "The answer slot was deleted." }
        val previousRun = current.currentRunId?.let { recoveryRun(it) }
        if (request.run.synthesisKey != null) {
            val decoder = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val incoming = request.run.synthesisSnapshot?.let { raw -> runCatching { decoder.decodeFromString<dev.chungjungsoo.gptmobile.data.agent.CombinedInputSnapshot>(raw) }.getOrNull() }
            if (incoming != null && incoming.dispatchIdentity.isNotBlank()) {
                if (request.freezeCombinedSources) {
                    val membership = requireNotNull(getChatRoom(current.chatId)).activePlatform.toSet()
                    val expected = incoming.combined?.requested?.participantUids.orEmpty().filter { it in membership }.toSet()
                    require(incoming.sources.map { it.platformUid }.toSet() == expected) { "COMBINED_MEMBERSHIP_CHANGED: Recheck the complete remaining input before dispatch." }
                }
                val existing = synthesisRunsForTurn(current.chatId, request.userMessage.id).firstOrNull { run ->
                    run.synthesisSnapshot?.let { runCatching { decoder.decodeFromString<dev.chungjungsoo.gptmobile.data.agent.CombinedInputSnapshot>(it).dispatchIdentity }.getOrNull() } == incoming.dispatchIdentity
                }
                if (existing != null) return PersistAgentRetryResult(requireNotNull(recoveryMessage(existing.assistantMessageId)), existing)
            }
        }
        if (request.run.synthesisKey != null && previousRun?.synthesisKey == request.run.synthesisKey) {
            return PersistAgentRetryResult(current, previousRun)
        }
        require(current.currentRunId == request.assistantMessage.currentRunId && current.ownershipFence == request.assistantMessage.ownershipFence) { "The answer slot changed; reload before retrying." }
        require(request.run.synthesisKey == null || previousRun?.status !in setOf("QUEUED", "RUNNING")) { "A Combined writer still owns this answer. Stop it before replacing the corpus." }
        require(request.run.synthesisKey == null || previousRun?.synthesisKey == null || request.resumeCombinedFrom != null || request.replaceCombinedCorpus || request.successorOfRunId != null) {
            "The Combined answer already owns a frozen corpus. Late source updates cannot replace it automatically."
        }
        request.successorOfRunId?.let { predecessorId ->
            require(previousRun != null && previousRun.runId == predecessorId && previousRun.status == "FAILED") { "COMBINED_SUCCESSOR_OWNER_CHANGED" }
            val decoder = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val previousRaw = requireNotNull(previousRun.synthesisSnapshot)
            val successorRaw = requireNotNull(request.run.synthesisSnapshot)
            require(previousRun.synthesisKey == dev.chungjungsoo.gptmobile.data.research.researchHash(previousRaw) && request.run.synthesisKey == dev.chungjungsoo.gptmobile.data.research.researchHash(successorRaw)) { "COMBINED_SUCCESSOR_INTEGRITY_FAILED" }
            dev.chungjungsoo.gptmobile.data.agent.CombinedSuccessorSnapshot.validate(decoder.decodeFromString(previousRaw), decoder.decodeFromString(successorRaw), predecessorId)
        }
        if (request.freezeCombinedSources) {
            val rows = rawGetMessages(current.chatId).filter { it.linkedMessageId == request.userMessage.id && it.platformType != null }
            require(request.assistantMessage.combinedSources.isNotEmpty()) { "Combined sources are missing." }
            request.assistantMessage.combinedSources.forEach { source ->
                val row = requireNotNull(rows.singleOrNull { it.platformType == source.platformUid }) { "A Combined contributor changed." }
                val sourceRun = row.currentRunId?.let { recoveryRun(it) }
                require(
                    row.combinedSources.isEmpty() &&
                        (row.currentRunId == null || sourceRun?.status in setOf("COMPLETED", "FAILED", "INTERRUPTED")) &&
                        source.completionStatus == (sourceRun?.status ?: source.completionStatus) &&
                        source.content.isNotBlank() &&
                        (if (source.rawContentDigest.isNotBlank()) source.rawContentDigest == dev.chungjungsoo.gptmobile.data.research.researchHash(row.content) else dev.chungjungsoo.gptmobile.util.stripAssistantErrorNote(row.content).trim() == dev.chungjungsoo.gptmobile.util.stripAssistantErrorNote(source.content).trim())
                ) {
                    "A Combined contribution is still settling. The inputs will be checked again before dispatch."
                }
            }
        }
        val resumedPrefix = request.resumeCombinedFrom?.let { priorId ->
            val priorRun = requireNotNull(previousRun)
            require(priorId == priorRun.runId && priorRun.status in setOf("COMPLETED", "FAILED", "CANCELED", "INTERRUPTED")) { "The Combined recovery owner changed." }
            val snapshot = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<dev.chungjungsoo.gptmobile.data.agent.CombinedInputSnapshot>(requireNotNull(request.run.synthesisSnapshot))
            require(snapshot.resumeFromRunId == priorId && request.run.synthesisKey == dev.chungjungsoo.gptmobile.data.research.researchHash(requireNotNull(request.run.synthesisSnapshot)))
            val original = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<dev.chungjungsoo.gptmobile.data.agent.CombinedInputSnapshot>(requireNotNull(priorRun.synthesisSnapshot))
            require(snapshot.copy(version = original.version, resumeFromRunId = original.resumeFromRunId, committedPrefix = original.committedPrefix, deliveryResumeFromRevisionId = original.deliveryResumeFromRevisionId) == original) { "Recovery cannot replace the frozen input." }
            val prefix = if (priorRun.status == "COMPLETED") "" else dev.chungjungsoo.gptmobile.data.agent.AnswerSegmentChain.text(answerSegments(priorId), priorId, priorRun.ownershipFence)
            val resumed = if (snapshot.deliveryProtocol in setOf("PASSAGES_V2", "PASSAGES_V3")) "" else prefix
            require(snapshot.committedPrefix == resumed && (priorRun.status == "COMPLETED" || current.content == prefix)) { "The committed Combined prefix changed." }
            resumed
        }.orEmpty()
        val nextFence = Math.addExact(current.ownershipFence, 1L)
        val previousRevision = current.snapshotLatestAssistantRevision(request.run.createdAt)
        val selectedPrior = current.revisions.getOrNull(current.activeRevisionIndex)
        val preserveVisibleRevision = (request.resumeCombinedFrom != null || request.freezeCombinedSources || request.successorOfRunId != null || request.replaceCombinedCorpus || dev.chungjungsoo.gptmobile.data.agent.CombinedAcceptedTurn.decode(request.run.synthesisSnapshot)?.context?.requested?.singlePass == true) && resumedPrefix.isEmpty() && (selectedPrior?.content ?: current.content).isNotBlank()
        val retainedRevisionIndex = if (selectedPrior != null) current.activeRevisionIndex + if (previousRevision != null) 1 else 0 else 0
        val assistantMessage = request.assistantMessage.copy(
            content = resumedPrefix,
            thoughts = "",
            timeline = listOfNotNull(
                request.run.synthesisKey?.let {
                    dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem(
                        dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType.NOTICE,
                        writerIdentity = dev.chungjungsoo.gptmobile.data.database.entity.CombinedWriterIdentity(current.platformType, request.run.profileUid, request.run.modelSnapshot, request.assistantMessage.combinedSources.map { it.platformUid })
                    )
                },
                request.recovery?.let { descriptor ->
                    require(descriptor.generationId == request.run.runId && descriptor.rootWorkId == "${request.userMessage.chatId}:${descriptor.sourceMessageId}")
                    dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem(
                        type = dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType.NOTICE,
                        content = "Resuming unfinished work from saved evidence.",
                        recovery = descriptor
                    )
                },
                dev.chungjungsoo.gptmobile.data.agent.CombinedAcceptedTurn.decode(request.run.synthesisSnapshot)?.let { accepted ->
                    dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem(
                        type = dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType.NOTICE,
                        combinedTurn = dev.chungjungsoo.gptmobile.data.context.CombinedTurnHistory.accepted(accepted.context.copy(profileUid = request.run.profileUid))
                    )
                }
            ),
            attachments = emptyList(),
            revisions = previousRevision
                ?.let { listOf(it) + request.assistantMessage.revisions }
                ?: request.assistantMessage.revisions,
            activeRevisionIndex = if (preserveVisibleRevision) retainedRevisionIndex else ACTIVE_REVISION_LATEST,
            currentRunId = request.run.runId,
            ownershipFence = nextFence,
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
            ownershipFence = nextFence,
            synthesisKey = request.run.synthesisKey,
            synthesisSnapshot = request.run.synthesisSnapshot,
            displayRole = request.run.displayRole ?: if (request.run.synthesisKey != null) {
                "COMBINED_FINAL"
            } else {
                previousRun?.displayRole?.takeIf { it != "INTERNAL" } ?: when {
                    getChatRoom(request.userMessage.chatId)?.conversationMode == "COMBINED" -> "COMBINED_CONTRIBUTOR"
                    getChatRoom(request.userMessage.chatId)?.enabledPlatform.orEmpty().size > 1 -> "MULTI_REPLY"
                    else -> "DIRECT_REPLY"
                }
            },
            createdAt = request.run.createdAt
        )
        insertRun(run)
        preserveCombinedPrefix(assistantMessage)
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
        val current = recoveryMessage(assistantMessage.id) ?: return
        val run = recoveryRun(runId) ?: return
        if (current.currentRunId != runId || current.ownershipFence != run.ownershipFence || assistantMessage.ownershipFence != run.ownershipFence) return
        if (!validCombinedPublication(run)) return
        val selected = dev.chungjungsoo.gptmobile.data.agent.preserveCombinedRetrySelection(current, assistantMessage, run, status)
        updateMessage(selected)
        publishReply(run, selected, status, completedAt ?: System.currentTimeMillis() / 1000)
        updateRunStatus(runId, status, startedAt, completedAt, terminalError)
        if (run.synthesisKey != null && status != "COMPLETED") restoreCombinedCandidate(runId)
        if (getChatRoom(assistantMessage.chatId)?.isArchived == true) setArchivedWithCompression(assistantMessage.chatId, true)
    }

    /** Recheck the sealed original at publication, after any asynchronous provider work. */
    private suspend fun validCombinedPublication(run: AgentRun): Boolean {
        if (run.synthesisKey == null) return true
        val raw = run.synthesisSnapshot ?: return false
        if (run.synthesisKey != dev.chungjungsoo.gptmobile.data.research.researchHash(raw)) return false
        return try {
            val snapshot = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<dev.chungjungsoo.gptmobile.data.agent.CombinedInputSnapshot>(raw)
            val stage = dev.chungjungsoo.gptmobile.data.agent.AcceptedTaskRef.from(snapshot.userMessage)
            val original = recoveryMessage(run.userMessageId) ?: return false
            stage.validate(original)
            snapshot.acceptedTask?.validate(original)
            (snapshot.version < 6 || snapshot.inputDigest == snapshot.contentDigest()) && snapshot.profileUid == run.profileUid && run.chatId == original.chatId
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    /** Commit the complete accumulated answer and terminal status under the same ownership check. */
    @Transaction
    suspend fun finishRunningWithMessage(
        assistantMessage: MessageV2,
        runId: String,
        status: String,
        completedAt: Long,
        terminalError: String?
    ): Boolean {
        require(status in setOf("COMPLETED", "FAILED", "CANCELED", "INTERRUPTED"))
        val run = recoveryRun(runId) ?: return false
        if (run.status != "RUNNING" && !(run.status == "QUEUED" && status != "COMPLETED")) return false
        val current = recoveryMessage(run.assistantMessageId) ?: return false
        if (current.ownershipFence != run.ownershipFence ||
            assistantMessage.ownershipFence != run.ownershipFence ||
            current.currentRunId != runId ||
            assistantMessage.currentRunId != runId ||
            assistantMessage.id != run.assistantMessageId ||
            assistantMessage.chatId != run.chatId
        ) {
            return false
        }
        if (!validCombinedPublication(run)) return false
        val selected = dev.chungjungsoo.gptmobile.data.agent.preserveCombinedRetrySelection(current, assistantMessage, run, status)
        preserveCombinedPrefix(selected)
        updateMessage(selected)
        publishReply(run, selected, status, completedAt)
        updateRunStatus(runId, status, run.startedAt, completedAt, terminalError)
        if (run.synthesisKey != null && status != "COMPLETED") restoreCombinedCandidate(runId)
        cancelActiveToolEvents(runId, completedAt)
        if (getChatRoom(run.chatId)?.isArchived == true) setArchivedWithCompression(run.chatId, true)
        return true
    }

    /** A late streaming checkpoint cannot replace a terminal or newer revision. */
    @Transaction
    suspend fun checkpointRunningMessage(message: MessageV2): Boolean {
        val runId = message.currentRunId ?: return false
        val run = recoveryRun(runId) ?: return false
        if (run.status != "RUNNING" || run.assistantMessageId != message.id || run.chatId != message.chatId) return false
        val current = recoveryMessage(message.id) ?: return false
        if (current.currentRunId != runId || current.ownershipFence != run.ownershipFence || message.ownershipFence != run.ownershipFence) return false
        if (!validCombinedPublication(run)) return false
        val selected = dev.chungjungsoo.gptmobile.data.agent.preserveCombinedRetrySelection(current, message, run, null)
        if (selected == current) return true
        preserveCombinedPrefix(selected)
        updateMessage(selected)
        return true
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
