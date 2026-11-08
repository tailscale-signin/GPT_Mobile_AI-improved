package dev.chungjungsoo.gptmobile.data.github

import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object GatewayObservationClock {
    val session: String = UUID.randomUUID().toString()
    fun monotonic(): Long = android.os.SystemClock.elapsedRealtime()
}

data class GatewaySavedJob(val row: GatewayJobRecord, val job: GatewayCatalogJob, val conversation: GatewayConversationRecord, val connection: GatewayScopeRecord) {
    fun stale(now: Long): Boolean = connection.connectionState != "ONLINE" || now - row.observedAt !in 0..30_000
    fun elapsedMillis(monotonic: Long = GatewayObservationClock.monotonic()): Long? {
        val accepted = job.acceptedAtMs ?: return null
        val fixed = job.finishedAtMs ?: row.serverTime
        val tick = if (!job.terminal && row.clockSession == GatewayObservationClock.session) (monotonic - row.observedMonotonic).coerceAtLeast(0) else 0
        return (fixed - accepted).coerceAtLeast(0) + tick
    }
}

/** Room owns normalized job state; preferences are only a crash-safe legacy import source. */
@Singleton
class GatewayWorkStore @Inject constructor(private val database: ChatDatabaseV2, private val settings: SettingRepository) {
    val dao: GatewayWorkDao get() = database.gatewayWorkDao()
    private val json = Json { ignoreUnknownKeys = true }
    private val migration = Mutex()

    suspend fun importLegacy() = migration.withLock {
        val features = settings.getFeatureSettings()
        val legacy = features.gatewayEngineeringJobs
        if (legacy.isEmpty()) return@withLock
        val imported = mutableSetOf<String>()
        database.withTransaction {
            for (binding in legacy) {
                // Removed/temporary conversations must not be recreated by migration.
                val chat = dao.chat(binding.chatId) ?: continue
                if (chat.isTemporary) continue
                val current = dao.binding(binding.request.requestId)
                if (current == null) {
                    val original = features.conversationGitHub[binding.chatId]?.takeIf {
                        it.mainProfileUid == binding.request.profileId && "${it.owner}/${it.repo}" == binding.request.repository && it.ref == (binding.request.sourceBranch ?: binding.request.branch)
                    }
                    saveBindingLocked(binding.copy(originalContext = binding.originalContext ?: original))
                    val record = requireNotNull(dao.binding(binding.request.requestId))
                    val payload = json.encodeToString(binding.request)
                    dao.insertCommand(GatewayCommandRecord(binding.request.requestId, record.scopeId, null, binding.chatId, binding.userMessageId, binding.assistantMessageId, binding.localRunId, "create", digest(payload), payload, if (binding.job == null) "UNKNOWN" else "ACCEPTED", binding.job?.id, 0, 0, if (binding.job == null) "Legacy request retained; read-only reconciliation is required." else null))
                } else {
                    val retained = json.decodeFromString<GatewayEngineeringBinding>(current.binding)
                    check(retained.profileUid == binding.profileUid && retained.endpoint == binding.endpoint && retained.request == binding.request) { "Legacy gateway request identity conflicts with retained work." }
                }
                imported += binding.request.requestId
            }
        }
        // Removing only the unchanged imported values makes a crash between the
        // Room commit and preference cleanup safe, and preserves racing writes.
        settings.mutateFeatureSettings { current ->
            current.copy(gatewayEngineeringJobs = current.gatewayEngineeringJobs.filterNot { value -> value.request.requestId in imported && legacy.any { it == value } })
        }
    }

    suspend fun bindings(chatId: Int? = null): List<GatewayEngineeringBinding> {
        importLegacy()
        return dao.bindings().filter { chatId == null || it.localChatId == chatId }.map { restore(it) }
    }

    suspend fun binding(requestId: String): GatewayEngineeringBinding? {
        importLegacy()
        return dao.binding(requestId)?.let { restore(it) }
    }

    private suspend fun restore(record: GatewayEngineeringRecord): GatewayEngineeringBinding {
        val value = json.decodeFromString<GatewayEngineeringBinding>(record.binding)
        val job = record.jobId?.let { dao.job(record.scopeId, "engineering", it)?.engineeringPayload }?.let { json.decodeFromString<GatewayEngineeringJob>(it) }
        return value.copy(job = job, updates = dao.updates(record.requestId).asReversed().map { GatewayEngineeringUpdate(it.revision, it.state, it.message, it.createdAt) }, syncedRevision = record.syncedRevision, seenRevision = record.seenRevision, notifiedRevision = record.notifiedRevision)
    }

    suspend fun saveBinding(binding: GatewayEngineeringBinding) = database.withTransaction { saveBindingLocked(binding) }

    private suspend fun saveBindingLocked(binding: GatewayEngineeringBinding) {
        val old = dao.binding(binding.request.requestId)
        val original = old?.let { json.decodeFromString<GatewayEngineeringBinding>(it.binding) }
        check(original == null || original.request == binding.request && original.profileUid == binding.profileUid && original.endpoint == binding.endpoint && original.principalId == binding.principalId) { "A gateway job cannot change its original request or connection." }
        val scope = scopeFor(binding)
        if (dao.scope(scope.id) == null) dao.putScope(scope)
        val conversationId = binding.conversationId ?: binding.job?.conversationId ?: "legacy_${digest(binding.request.requestId).take(32)}"
        val conversationKey = key(scope.id, conversationId)
        val previous = dao.conversation(conversationKey)
        val conversation = previous ?: GatewayConversationRecord(conversationKey, scope.id, conversationId, binding.chatId, binding.request.profileId ?: binding.executionSelection?.profileUid.orEmpty(), binding.request.profileName.orEmpty(), binding.request.model, binding.request.repository, binding.request.sourceBranch ?: binding.request.branch, binding.request.objective.lineSequence().firstOrNull().orEmpty().take(200), binding.job?.conversationRevision ?: 0, (binding.job?.executionBinding ?: binding.executionAdmission?.binding)?.toString(), binding.originalContext?.let { json.encodeToString(it) }, false, "Replay history has not yet been verified.", System.currentTimeMillis(), binding.request.sourceSha ?: binding.originalContext?.commitSha)
        dao.insertConversation(conversation)
        if (previous != null && previous.localChatId == null) dao.updateConversation(previous.copy(localChatId = binding.chatId, originalContext = binding.originalContext?.let { json.encodeToString(it) } ?: previous.originalContext))
        val stable = binding.copy(job = null, updates = emptyList(), syncedRevision = -1, seenRevision = -1, notifiedRevision = -1)
        dao.putBinding(GatewayEngineeringRecord(binding.request.requestId, scope.id, binding.chatId, binding.job?.id, json.encodeToString(stable), binding.syncedRevision, binding.seenRevision, binding.notifiedRevision))
        dao.putUpdates(binding.updates.map { GatewayEngineeringUpdateRecord(binding.request.requestId, it.revision, it.state, it.message, it.createdAt) })
        binding.job?.let { job ->
            val value = engineeringCatalog(binding, conversationId)
            putJobLocked(scope, conversation, value, json.encodeToString(job.copy(updates = emptyList())))
        }
        val legacyId = "legacy_${digest(binding.request.requestId).take(32)}"
        if (conversationId != legacyId) dao.removeLegacyConversation(key(old?.scopeId ?: scope.id, legacyId))
    }

    suspend fun prepare(binding: GatewayEngineeringBinding, action: String = "create", commandPayload: String = json.encodeToString(binding.request)): Boolean = database.withTransaction {
        val existing = dao.command(binding.request.requestId)
        if (existing != null) {
            check(existing.payloadDigest == digest(commandPayload) && existing.localChatId == binding.chatId && existing.userMessageId == binding.userMessageId && existing.assistantMessageId == binding.assistantMessageId) { "The saved command ID belongs to a different message." }
            return@withTransaction false
        }
        require(dao.chat(binding.chatId)?.isTemporary == false) { "Gateway-owned work requires a saved conversation." }
        saveBindingLocked(binding)
        val record = requireNotNull(dao.binding(binding.request.requestId))
        val conversationKey = binding.conversationId?.let { key(record.scopeId, it) }
        dao.insertCommand(GatewayCommandRecord(binding.request.requestId, record.scopeId, conversationKey, binding.chatId, binding.userMessageId, binding.assistantMessageId, binding.localRunId, action, digest(commandPayload), commandPayload, "PREPARED", null, System.currentTimeMillis(), System.currentTimeMillis()))
        binding.localRunId?.let { runId ->
            val assistant = requireNotNull(binding.assistantMessageId)
            val message = requireNotNull(dao.chatMessage(assistant))
            check(message.chatId == binding.chatId && message.currentRunId == runId) { "The original assistant message changed before gateway admission." }
            check(dao.retainRemoteOwnership(runId, binding.chatId, assistant, null, binding.endpoint) == 1) { "The original run is no longer waiting for gateway admission." }
        }
        true
    }

    suspend fun commandState(requestId: String, state: String, error: String? = null) = database.withTransaction {
        val value = dao.command(requestId) ?: return@withTransaction
        if (value.state == "ACCEPTED") return@withTransaction
        if (value.state == "REJECTED" && state == "UNKNOWN") return@withTransaction
        dao.updateCommand(value.copy(state = state, updatedAt = System.currentTimeMillis(), error = error?.take(500)))
    }

    suspend fun accept(binding: GatewayEngineeringBinding) = database.withTransaction {
        val command = requireNotNull(dao.command(binding.request.requestId))
        val job = requireNotNull(binding.job)
        saveBindingLocked(binding)
        val saved = requireNotNull(dao.binding(binding.request.requestId))
        val conversationKey = (binding.conversationId ?: job.conversationId)?.let { key(saved.scopeId, it) }
        dao.updateCommand(command.copy(scopeId = saved.scopeId, conversationKey = conversationKey, state = "ACCEPTED", jobId = job.id, updatedAt = System.currentTimeMillis(), error = null))
        if (binding.localRunId != null && binding.assistantMessageId != null) {
            dao.retainRemoteOwnership(binding.localRunId, binding.chatId, binding.assistantMessageId, job.id, binding.endpoint)
            dao.bindInterruptedRemote(binding.localRunId, binding.chatId, binding.assistantMessageId, job.id, binding.endpoint)
        }
    }

    suspend fun verifiedScope(connectionUid: String, endpoint: String, identity: GatewayIdentity): GatewayScopeRecord {
        identity.validate()
        val id = key(connectionUid, selectedEndpoint(endpoint), identity.gatewayId, identity.principalId, identity.identityScope)
        val old = dao.scope(id)
        for (retained in dao.scopes().filter { it.connectionUid == connectionUid && it.id != id && it.endpoint == selectedEndpoint(endpoint) }) {
            dao.putScope(retained.copy(connectionState = "IDENTITY_CHANGED", error = "The configured connection now identifies another gateway or account. Restore the original identity to reconnect this retained work."))
        }
        val value = GatewayScopeRecord(id, connectionUid, selectedEndpoint(endpoint), identity.gatewayId, identity.principalId, identity.identityScope, "ONLINE", System.currentTimeMillis(), old?.catalogCursor, old?.catalogHasMore ?: false)
        dao.putScope(value)
        return value
    }

    suspend fun connectionFailure(scope: GatewayScopeRecord, failure: Throwable) {
        dao.putScope(scope.copy(connectionState = if (failure is GatewayIdentityChanged) "IDENTITY_CHANGED" else "OFFLINE", error = failure.message?.take(500)))
    }

    suspend fun acceptCatalog(scope: GatewayScopeRecord, page: GatewayJobCatalog) = database.withTransaction {
        requireIdentity(scope, page.gatewayId, page.principalId)
        require(page.items.size <= 100 && (!page.hasMore || !page.nextCursor.isNullOrBlank()))
        for (job in page.items) {
            val id = key(scope.id, job.conversationId)
            val existing = dao.conversation(id)
            val conversation = existing ?: GatewayConversationRecord(id, scope.id, job.conversationId, null, job.profileId, job.profileName, job.model, job.repository, job.sourceBranch ?: job.branch, job.title.take(200), job.conversationRevision ?: 0, job.executionBinding?.toString(), null, false, job.historyLimitation ?: "Saved transcript has not yet been read.", job.updatedAtMs, job.sourceSha)
            verifyConversation(conversation, job.profileId, job.model, job.repository, job.executionBinding, job.sourceBranch ?: job.branch, job.sourceSha)
            dao.insertConversation(conversation)
            val retained = mapOriginalOrdinaryChat(conversation, scope, job)
            putJobLocked(scope, retained, job, null)
        }
        dao.putScope(scope.copy(connectionState = "ONLINE", observedAt = System.currentTimeMillis(), catalogCursor = page.nextCursor.takeIf { page.hasMore }, catalogHasMore = page.hasMore, error = null))
    }

    private suspend fun mapOriginalOrdinaryChat(conversation: GatewayConversationRecord, scope: GatewayScopeRecord, job: GatewayCatalogJob): GatewayConversationRecord {
        if (job.kind != "chat" || conversation.localChatId != null || job.profileId.isBlank() || job.model.isBlank()) return conversation
        val run = dao.originalOrdinaryRuns(job.jobId, scope.endpoint, job.profileId, job.model).singleOrNull() ?: return conversation
        val chat = dao.chat(run.chatId)?.takeUnless { it.isTemporary } ?: return conversation
        val assistant = dao.chatMessage(run.assistantMessageId) ?: return conversation
        if (assistant.chatId != chat.id || assistant.platformType != job.profileId || assistant.currentRunId != run.runId || assistant.ownershipFence != run.ownershipFence) return conversation
        // Linking a view is not an ownership handoff. The original coordinator
        // retains its stream/recovery fence and publishes into the original reply.
        return conversation.copy(localChatId = chat.id, projectionOwner = "ANDROID_RUN").also { dao.updateConversation(it) }
    }

    private suspend fun putJobLocked(scope: GatewayScopeRecord, conversation: GatewayConversationRecord, job: GatewayCatalogJob, engineering: String?) {
        require(job.revision >= 0 && job.jobId.isNotBlank() && job.conversationId == conversation.conversationId && job.queuePosition?.let { it > 0 } != false)
        val old = dao.job(scope.id, job.kind, job.jobId)
        if (old != null && old.revision > job.revision) return
        check(old == null || old.conversationKey == conversation.id) { "A retained job moved to another conversation." }
        check(old?.acceptedAt == null || job.acceptedAtMs == old.acceptedAt) { "The accepted time of a retained job changed." }
        val monotonic = GatewayObservationClock.monotonic()
        val previousClock = old?.takeIf { it.clockSession == GatewayObservationClock.session }?.let { it.serverTime + (monotonic - it.observedMonotonic).coerceAtLeast(0) }
        val serverAnchor = maxOf(job.serverTimeMs, previousClock ?: job.serverTimeMs)
        dao.putJob(GatewayJobRecord(key(scope.id, job.kind, job.jobId), scope.id, job.jobId, conversation.id, job.kind, job.revision, job.schedulingState, job.phase, job.acceptedAtMs, job.finishedAtMs, System.currentTimeMillis(), monotonic, GatewayObservationClock.session, serverAnchor, json.encodeToString(job), engineering ?: old?.engineeringPayload))
    }

    suspend fun acceptSnapshot(scope: GatewayScopeRecord, snapshot: GatewayConversationSnapshot): GatewayConversationRecord = database.withTransaction {
        requireIdentity(scope, snapshot.gatewayId, snapshot.principalId)
        val id = key(scope.id, snapshot.conversationId)
        val old = dao.conversation(id)
        val value = old ?: GatewayConversationRecord(id, scope.id, snapshot.conversationId, null, snapshot.profileId, snapshot.profileName, snapshot.model, snapshot.repository, snapshot.sourceBranch ?: snapshot.branch, snapshot.title.take(200), snapshot.revision, snapshot.executionBinding?.toString(), null, false, snapshot.historyLimitation ?: "Saved transcript has not yet been read.", snapshot.serverTimeMs, snapshot.sourceSha)
        verifyConversation(value, snapshot.profileId, snapshot.model, snapshot.repository, snapshot.executionBinding, snapshot.sourceBranch ?: snapshot.branch, snapshot.sourceSha)
        if (old == null) dao.insertConversation(value)
        if (snapshot.revision >= value.revision) dao.updateConversation(value.copy(revision = snapshot.revision, title = snapshot.title.take(200), historyComplete = snapshot.historyComplete && dao.cursor(id)?.let { it.historyComplete && !it.hasMore } == true, historyLimitation = snapshot.historyLimitation ?: value.historyLimitation, updatedAt = snapshot.serverTimeMs))
        val saved = requireNotNull(dao.conversation(id))
        snapshot.latestJob?.let { job ->
            check(job.conversationId == snapshot.conversationId && job.jobId == snapshot.latestJobId)
            putJobLocked(scope, mapOriginalOrdinaryChat(saved, scope, job), job, null)
        }
        saved
    }

    /** Background reads cannot recreate a local view that was deleted during HTTP. */
    suspend fun acceptRetainedSnapshot(scope: GatewayScopeRecord, snapshot: GatewayConversationSnapshot, expected: GatewayConversationRecord): Boolean = database.withTransaction {
        val current = dao.conversation(expected.id) ?: return@withTransaction false
        if (current.scopeId != expected.scopeId || current.conversationId != expected.conversationId || current.localChatId != expected.localChatId) return@withTransaction false
        if (current.localChatId != null && dao.chat(current.localChatId)?.isTemporary != false) return@withTransaction false
        acceptSnapshot(scope, snapshot)
        true
    }

    /** Every accepted row, its local projection and the opaque cursor commit atomically. */
    suspend fun replay(scope: GatewayScopeRecord, conversationKey: String, page: GatewayMessagePage) = database.withTransaction {
        val conversation = requireNotNull(dao.conversation(conversationKey))
        requireIdentity(scope, page.gatewayId, page.principalId)
        check(conversation.scopeId == scope.id && conversation.conversationId == page.conversationId)
        val cursor = dao.cursor(conversationKey) ?: GatewayReplayCursor(conversationKey)
        val existing = mutableMapOf<Long, GatewayMessageRecord>()
        for (item in page.items) dao.message(conversationKey, item.sequence)?.let { existing[item.sequence] = it }
        val plan = GatewayReplayPolicy.plan(cursor, page, existing)
        if (plan.messages.isEmpty() && plan.cursor == cursor && page.items.isNotEmpty() && page.nextCursor != cursor.cursor) return@withTransaction
        for (item in plan.messages) {
            check(dao.messageById(conversationKey, item.messageId) == null) { "A gateway message ID was reused at another sequence." }
            val row = GatewayMessageRecord(conversationKey, item.sequence, item.messageId, item.jobId, item.role, item.kind, item.content, item.createdAtMs, GatewayReplayPolicy.fingerprint(item), null, item.logicalMessageId, item.partIndex, item.partCount, item.contentSha256)
            dao.insertMessage(row)
            projectLogicalMessage(conversation, row)
        }
        dao.putCursor(plan.cursor)
        dao.updateConversation(conversation.copy(revision = maxOf(conversation.revision, page.revision), historyComplete = plan.cursor.historyComplete && !plan.cursor.hasMore, historyLimitation = plan.cursor.historyLimitation ?: if (plan.cursor.hasMore) "More saved transcript parts remain to be read." else null, updatedAt = maxOf(conversation.updatedAt, page.serverTimeMs)))
        if (!plan.cursor.hasMore) for (job in dao.conversationJobs(conversationKey)) finishProjectedJob(scope.id, job.kind, job.jobId, plan.cursor.sequence)
    }

    private suspend fun projectLogicalMessage(conversation: GatewayConversationRecord, row: GatewayMessageRecord) {
        if (row.projectionComplete || dao.partCount(conversation.id, row.logicalMessageId) != row.partCount) return
        val first = dao.messageParts(conversation.id, row.logicalMessageId, 0).firstOrNull() ?: return
        if (first.projectionComplete) return
        if (conversation.localChatId == null && first.contentVerified) return
        val assembled = GatewayLogicalMessageAssembler.assemble(first) { offset -> dao.messageParts(conversation.id, row.logicalMessageId, offset) }
        dao.markContentVerified(conversation.id, row.logicalMessageId)
        if (conversation.projectionOwner != "CONTINUITY") {
            dao.markProjected(conversation.id, row.logicalMessageId, null)
            return
        }
        if (conversation.localChatId == null) return
        val content = assembled.content ?: "This complete gateway message contains ${assembled.bytes} UTF-8 bytes and is retained in bounded parts. [Open the full saved transcript](gptmobile://gateway-transcript/${conversation.id})."
        val message = GatewayConversationMessage(first.logicalMessageId, conversation.conversationId, first.jobId, first.sequence, first.role, first.kind, content, first.createdAt)
        val command = if (dao.job(conversation.scopeId, "engineering", first.jobId)?.conversationKey == conversation.id) dao.commandForJob(conversation.scopeId, first.jobId) else null
        val localId = projectMessage(conversation, message, command)
        // The stream owner may still be unwinding. Keep verified content pending
        // so a later interrupted/waiting owner can publish without another replay.
        if (localId == null && command?.assistantMessageId != null && message.role == "assistant" && message.kind !in setOf("progress", "status", "reasoning", "tool", "tool_request")) return
        dao.markProjected(conversation.id, first.logicalMessageId, localId)
    }

    private suspend fun projectMessage(conversation: GatewayConversationRecord, message: GatewayConversationMessage, command: GatewayCommandRecord?): Int? {
        val chatId = conversation.localChatId ?: return null
        if (message.role !in setOf("user", "assistant") || message.kind in setOf("progress", "status", "reasoning", "tool", "tool_request")) return null
        val originalId = if (message.role == "user") command?.userMessageId else command?.assistantMessageId
        if (originalId != null) {
            val ownedCommand = requireNotNull(command)
            val original = requireNotNull(dao.chatMessage(originalId))
            check(original.chatId == chatId) { "Gateway projection belongs to another local conversation." }
            if (message.role == "user") {
                check(original.platformType == null && original.content == message.content) { "Gateway user message differs from the accepted draft." }
            } else {
                if (ownedCommand.localRunId != null && original.currentRunId != ownedCommand.localRunId) return null
                if (ownedCommand.localRunId != null) {
                    val run = database.agentPersistenceDao().recoveryRun(ownedCommand.localRunId) ?: return null
                    if (!recoverableRemote(run.status, run.terminalError) || run.gatewayJobId != message.jobId || run.ownershipFence != original.ownershipFence || run.chatId != chatId || run.assistantMessageId != originalId || run.profileUid != conversation.profileUid) return originalId.takeIf { original.content == message.content }
                }
                check(original.platformType == conversation.profileUid) { "Gateway assistant profile differs from the original reply." }
                dao.updateChatMessage(original.copy(content = message.content, timeline = original.timeline.filter { it.type != AssistantTimelineItemType.TEXT } + AssistantTimelineItem(AssistantTimelineItemType.TEXT, content = message.content)))
            }
            return originalId
        }
        val linked = if (message.role == "assistant") dao.userMessage(conversation.id, message.jobId)?.localMessageId ?: 0 else 0
        return dao.insertChatMessage(MessageV2(chatId = chatId, content = message.content, linkedMessageId = linked, platformType = conversation.profileUid.takeIf { message.role == "assistant" }, createdAt = message.createdAtMs / 1000)).toInt()
    }

    suspend fun projectRetainedMessages(conversationKey: String) {
        var sequence = 0L
        do {
            val rows = dao.storedMessages(conversationKey, sequence)
            database.withTransaction {
                val conversation = dao.conversation(conversationKey) ?: return@withTransaction
                for (row in rows) {
                    if (!row.projectionComplete) projectLogicalMessage(conversation, row)
                    sequence = row.sequence
                }
            }
        } while (rows.size == 100)
        database.withTransaction {
            val conversation = dao.conversation(conversationKey) ?: return@withTransaction
            val cursor = dao.cursor(conversationKey) ?: return@withTransaction
            if (!cursor.hasMore) for (job in dao.conversationJobs(conversationKey)) finishProjectedJob(conversation.scopeId, job.kind, job.jobId, cursor.sequence)
        }
    }

    suspend fun updateJob(scope: GatewayScopeRecord, conversation: GatewayConversationRecord, job: GatewayCatalogJob, engineering: GatewayEngineeringJob? = null) = database.withTransaction {
        putJobLocked(scope, conversation, job, engineering?.let { json.encodeToString(it) })
    }

    private suspend fun finishProjectedJob(scopeId: String, kind: String, jobId: String, sequence: Long) {
        if (kind != "engineering") return
        val row = dao.job(scopeId, kind, jobId) ?: return
        val job = json.decodeFromString<GatewayCatalogJob>(row.payload)
        if (!job.terminal || sequence < job.latestMessageSequence) return
        val command = dao.commandForJob(scopeId, jobId) ?: return
        val runId = command.localRunId ?: return
        val run = database.agentPersistenceDao().recoveryRun(runId) ?: return
        if (!recoverableRemote(run.status, run.terminalError) || run.gatewayJobId != jobId) return
        val message = command.assistantMessageId?.let { dao.chatMessage(it) } ?: return
        if (message.currentRunId != runId || message.ownershipFence != run.ownershipFence || message.chatId != run.chatId || message.id != run.assistantMessageId || message.platformType != run.profileUid) return
        val status = when (job.schedulingState) {
            "completed" -> if (message.content.isNotBlank()) "COMPLETED" else "EVIDENCE_MISSING"
            "cancelled" -> "CANCELED"
            "failed" -> "FAILED"
            else -> "INTERRUPTED"
        }
        database.agentPersistenceDao().finishAgentRun(message, runId, status, run.startedAt, (job.finishedAtMs ?: job.serverTimeMs) / 1000, if (status == "COMPLETED") null else "GATEWAY_${job.schedulingState.uppercase()}")
    }

    private fun recoverableRemote(status: String, error: String?): Boolean = status == "WAITING_REMOTE_JOB" || status == "INTERRUPTED" && error != "GATEWAY_INTERRUPTED"

    fun jobs(limit: Int = 200): Flow<List<GatewaySavedJob>> = combine(dao.observeJobs(limit), dao.observeConversations(limit), dao.observeScopes()) { jobs, conversations, scopes ->
        val conversationIndex = conversations.associateBy { it.id }
        val scopeIndex = scopes.associateBy { it.id }
        jobs.mapNotNull { row ->
            val conversation = conversationIndex[row.conversationKey] ?: return@mapNotNull null
            val scope = scopeIndex[row.scopeId] ?: return@mapNotNull null
            GatewaySavedJob(row, json.decodeFromString(row.payload), conversation, scope)
        }
    }

    fun jobsForGateway(gatewayUid: String, limit: Int): Flow<List<GatewaySavedJob>> = combine(dao.observeJobsForGateway(gatewayUid, limit), dao.observeConversationsForGateway(gatewayUid, limit), dao.observeScopes()) { jobs, conversations, scopes ->
        val conversationIndex = conversations.associateBy { it.id }
        val scopeIndex = scopes.associateBy { it.id }
        jobs.mapNotNull { row ->
            val conversation = conversationIndex[row.conversationKey] ?: return@mapNotNull null
            val scope = scopeIndex[row.scopeId] ?: return@mapNotNull null
            GatewaySavedJob(row, json.decodeFromString(row.payload), conversation, scope)
        }
    }

    fun observeConversation(chatId: Int): Flow<GatewayConversationObservation> = combine(dao.observeJobsForChat(chatId), dao.observeConversationsForChat(chatId), dao.observeScopes(), dao.observeCommandForChat(chatId)) { rows, conversations, scopes, command ->
        val row = rows.firstOrNull { command?.jobId == null || it.jobId == command.jobId }
        val conversation = conversations.firstOrNull { it.id == row?.conversationKey }
        val scope = scopes.firstOrNull { it.id == row?.scopeId }
        val job = row?.let { json.decodeFromString<GatewayCatalogJob>(it.payload) }
        GatewayConversationObservation(job, command?.state, scope?.connectionState ?: "UNVERIFIED", scope?.connectionState != "ONLINE" || row == null || System.currentTimeMillis() - row.observedAt !in 0..30_000, command?.id, conversation?.conversationId, command?.error ?: scope?.error)
    }

    companion object {
        internal fun digest(text: String): String = selectionDigest(JsonPrimitive(text))
        internal fun key(vararg parts: String): String = selectionDigest(JsonArray(parts.map(::JsonPrimitive)))
        internal fun requireIdentity(scope: GatewayScopeRecord, gateway: String, principal: String) {
            if (scope.gatewayId != gateway || scope.principalId != principal) throw GatewayIdentityChanged()
        }
        private fun verifyConversation(saved: GatewayConversationRecord, profile: String, model: String, repository: String?, binding: JsonObject?, sourceBranch: String?, sourceSha: String?) {
            check(saved.profileUid == profile && saved.model == model && saved.repository == repository && saved.branch == sourceBranch && (saved.sourceSha == null || saved.sourceSha == sourceSha) && (saved.executionBinding == null || binding == Json.parseToJsonElement(saved.executionBinding))) { "The original gateway conversation binding changed." }
        }
        private fun scopeFor(binding: GatewayEngineeringBinding): GatewayScopeRecord {
            val gateway = binding.gatewayId ?: binding.job?.gatewayId ?: "legacy-unverified"
            val principal = binding.principalId ?: "legacy-unverified"
            val identityScope = binding.identityScope ?: "legacy-unverified"
            val endpoint = selectedEndpoint(binding.endpoint)
            return GatewayScopeRecord(key(binding.profileUid, endpoint, gateway, principal, identityScope), binding.profileUid, endpoint, gateway, principal, identityScope, "UNVERIFIED", 0)
        }
        internal fun engineeringCatalog(binding: GatewayEngineeringBinding, conversationId: String): GatewayCatalogJob {
            val job = requireNotNull(binding.job)
            val scheduling = job.schedulingState ?: when (job.state) {
                "COMPLETED" -> "completed"
                "CANCELLED" -> "cancelled"
                "FAILED" -> "failed"
                "QUEUED" -> "queued"
                "NEEDS_INPUT", "MERGE_READY" -> "waiting"
                else -> "unknown"
            }
            return GatewayCatalogJob(job.id, "engineering", conversationId, job.revision.toLong(), job.state, scheduling, binding.request.objective.lineSequence().firstOrNull().orEmpty().take(200), binding.request.scope, binding.request.profileId ?: binding.executionSelection?.profileUid.orEmpty(), binding.request.profileName.orEmpty(), binding.request.model, job.repository, job.branch, job.acceptedAtMs, updatedAtMs = job.serverTimeMs ?: (job.createdAt * 1000).toLong(), finishedAtMs = job.finishedAt?.let { (it * 1000).toLong() }, serverTimeMs = job.serverTimeMs ?: (job.createdAt * 1000).toLong(), queuePosition = job.queuePosition, permittedActions = job.permittedActions, executionBinding = job.executionBinding, historyLimitation = "Replay history has not yet been verified.", conversationRevision = job.conversationRevision, sourceBranch = job.sourceBranch ?: binding.request.sourceBranch, sourceSha = job.sourceSha ?: binding.request.sourceSha, workingBranch = job.workingBranch, base = job.base ?: binding.request.base)
        }
    }
}

class GatewayIdentityChanged : IllegalStateException("The original gateway or account identity changed. Retained work remains read-only; restore its original connection to reconnect.")
