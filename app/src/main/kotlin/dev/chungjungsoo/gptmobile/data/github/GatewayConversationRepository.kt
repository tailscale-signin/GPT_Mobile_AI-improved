package dev.chungjungsoo.gptmobile.data.github

import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.agent.GatewayOutcomeRecovery
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.ChatPlatformModelV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayConversationRecord
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayMessagePartRecord
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayMessageRecord
import dev.chungjungsoo.gptmobile.data.database.entity.GatewaySubmissionRecord
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A server conversation has one local mirror, independent of the workspace currently selected. */
@Singleton
class GatewayConversationRepository @Inject constructor(
    private val database: ChatDatabaseV2,
    private val settings: SettingRepository,
    private val client: GatewayJobClient,
    private val jobs: GatewayJobRepository,
    private val commands: GatewayConversationCommandClient
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val attachmentLock = Mutex()
    private val dao get() = database.gatewayJobDao()

    suspend fun ownsConversation(chatId: Int): Boolean = dao.conversationsForChat(chatId).isNotEmpty() || dao.bindings(chatId).isNotEmpty() || dao.submissionsForChat(chatId).isNotEmpty()

    /** An original server conversation cannot silently become a differently selected local run. */
    suspend fun conversationWriteBlocker(chatId: Int): String? {
        if (!ownsConversation(chatId)) return null
        return try {
            val chat = requireNotNull(dao.localChat(chatId)) { "The original conversation is unavailable. Its gateway work remains retained." }
            check(!chat.isTemporary) { "Saved gateway work cannot continue in a temporary conversation." }
            val profiles = settings.fetchPlatformV2s()
            val features = settings.getFeatureSettings()
            val records = dao.conversationsForChat(chatId)
            val bindings = dao.bindings(chatId).map { json.decodeFromString<GatewayEngineeringBinding>(it.binding) }
            val submissions = dao.submissionsForChat(chatId)
            val ownedProfiles = (records.map { it.executionProfileUid } + bindings.mapNotNull { it.request.profileId } + submissions.map { it.executionProfileUid }).toSet()
            check(chat.activePlatform.any { it in ownedProfiles }) { "Restore the original gateway participant before sending, or start a separate conversation." }
            val checkedRoutes = mutableMapOf<Pair<String, String>, GatewayScopeIdentity>()
            suspend fun identity(profileUid: String, endpoint: String): GatewayScopeIdentity = checkedRoutes.getOrPut(profileUid to endpoint) {
                val route = jobs.originalRoute(profileUid, endpoint)
                val observed = client.identity(route.config)
                check(jobs.originalRoute(profileUid, endpoint).config == route.config) { "The original gateway credentials changed during verification." }
                observed
            }
            suspend fun selected(uid: String, model: String): PlatformV2 {
                val profile = profiles.singleOrNull { it.uid == uid && it.enabled } ?: error("The original model profile is unavailable. Saved history remains read-only.")
                val current = profile.copy(model = dao.selectedModel(chatId, uid) ?: profile.model, reasoning = features.conversationReasoning[chatId] ?: profile.reasoning)
                check(current.model == model) { "This conversation requires its original model. Restore the saved chat model before sending." }
                return current
            }
            for (record in records.filter { it.executionProfileUid in chat.activePlatform }) {
                val original = json.decodeFromString<GatewayConversationSnapshot>(record.snapshot)
                val observed = identity(record.connectionProfileUid, record.endpoint)
                check(observed.gatewayId == record.gatewayId && observed.principalId == record.principalId) { "The original gateway or account changed. Saved history remains read-only." }
                val profile = selected(record.executionProfileUid, original.model)
                if (original.repository == null) {
                    check(profile.apiUrl.trimEnd('/') == record.endpoint && profile.uid == record.connectionProfileUid) { "The original model endpoint changed. Restore it before continuing this conversation." }
                    check(GitHubAutonomousEngineeringPolicy.supportsSavedJobs(profile)) { "This saved conversation requires its original private gateway provider." }
                    for (submission in submissions.filter { it.conversationKey == record.key }) {
                        val run = submission.runId?.let { database.agentRunDao().getById(it) } ?: continue
                        check(run.providerSnapshot == profile.compatibleType.name) { "The original gateway provider type changed. Restore it before continuing this conversation." }
                    }
                    continue
                }
                check(chat.activePlatform == listOf(profile.uid)) { "This gateway repository conversation requires its original model profile as the only active participant." }
                val prior = bindings.lastOrNull { it.request.conversationId == record.conversationId || it.job?.conversationId == record.conversationId }
                val currentContext = features.conversationGitHub[chatId]
                prior?.originalContext?.let { check(currentContext != null && sameWorkspace(it, currentContext)) { "The original GitHub connection or workspace changed." } }
                if (currentContext != null) check("${currentContext.owner}/${currentContext.repo}" == original.repository && currentContext.mainProfileUid == profile.uid && currentContext.ref == (original.sourceBranch ?: original.branch)) { "The original repository or source branch changed. Restore its workspace before continuing." }
                check(!profile.disableAllTools && !profile.disableRemoteTools && features.githubProfileOverrides[profile.uid]?.githubTools == true) { "Remote GitHub tools are disabled for the original profile." }
                val selectedGateway = features.githubProfileOverrides[profile.uid]?.engineeringGatewayProfileUid
                check(selectedGateway == record.connectionProfileUid || selectedGateway == null && bindings.none { it.request.profileId == profile.uid }) { "The original engineering gateway selection changed. Restore it before continuing." }
                val route = jobs.originalRoute(record.connectionProfileUid, record.endpoint)
                val fresh = client.conversation(route.config, record.conversationId)
                requireSameIdentity(fresh, record.gatewayId, record.principalId, record.conversationId)
                check(fresh.profileId == profile.uid && fresh.model == original.model && fresh.repository == original.repository && (original.sourceBranch == null || original.sourceBranch == fresh.sourceBranch) && (original.sourceSha == null || original.sourceSha == fresh.sourceSha)) { "The saved conversation execution destination changed." }
                verifyCurrentSelection(profile, prior?.executionSelection, fresh.executionBinding)
            }
            for (binding in bindings.filter { it.request.profileId in chat.activePlatform && records.none { record -> record.executionProfileUid == it.request.profileId } }) {
                val profile = selected(requireNotNull(binding.request.profileId), binding.request.model)
                check(chat.activePlatform == listOf(profile.uid)) { "The saved gateway submission requires its original model participant." }
                val observed = identity(binding.profileUid, binding.endpoint)
                check(binding.gatewayId != null && observed.gatewayId == binding.gatewayId && observed.principalId == binding.principalId) { "The original gateway identity is unverified. Reconnect before continuing." }
                check(!profile.disableAllTools && !profile.disableRemoteTools && features.githubProfileOverrides[profile.uid]?.githubTools == true && features.githubProfileOverrides[profile.uid]?.engineeringGatewayProfileUid == binding.profileUid) { "The original gateway selection or tool consent changed." }
                verifyCurrentSelection(profile, binding.executionSelection, binding.executionAdmission?.binding)
            }
            for (pending in submissions.filter { it.executionProfileUid in chat.activePlatform && records.none { record -> record.executionProfileUid == it.executionProfileUid } && bindings.none { binding -> binding.request.profileId == it.executionProfileUid } }) {
                val reservation = runCatching { json.decodeFromString<GatewayOrdinaryReservation>(pending.payload) }.getOrNull() ?: error("The original saved command identity is incomplete. Reconnect to it before sending another turn.")
                val profile = selected(pending.executionProfileUid, reservation.model)
                check(profile.apiUrl.trimEnd('/') == pending.endpoint && profile.uid == pending.connectionProfileUid) { "The original gateway route changed." }
                check(GitHubAutonomousEngineeringPolicy.supportsSavedJobs(profile)) { "The saved command requires its original private gateway provider." }
                pending.runId?.let { database.agentRunDao().getById(it) }?.let { check(it.providerSnapshot == profile.compatibleType.name) { "The original gateway provider type changed." } }
                val observed = identity(pending.connectionProfileUid, pending.endpoint)
                check(observed.gatewayId == reservation.gatewayId && observed.principalId == reservation.principalId) { "The saved gateway command belongs to a different account or installation." }
            }
            null
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failure.message ?: "The original gateway selection could not be verified. Saved history remains readable."
        }
    }

    private suspend fun verifyCurrentSelection(profile: PlatformV2, saved: GatewayExecutionSelection?, binding: JsonObject?) {
        val privacy = saved?.privacyScope ?: binding?.get("privacy_scope")?.jsonPrimitive?.content ?: error("The original execution identity is incomplete. Reconnect to the saved conversation before sending.")
        val current = executionSelection(profile, profile.providerConnectionUid?.let { settings.getProviderConnection(it) }, privacy)
        if (saved != null) check(current == saved) { "The original model profile or provider connection changed. Restore its saved settings before sending." }
        if (binding != null) GatewayExecutionAdmission(binding, JsonObject(emptyMap())).verifySelection(current)
    }

    internal suspend fun requireSavedSelection(binding: GatewayEngineeringBinding) {
        val profile = settings.fetchPlatformV2s().singleOrNull { it.uid == binding.request.profileId && it.enabled } ?: error("The original model profile is unavailable. The saved command was not submitted.")
        val features = settings.getFeatureSettings()
        check(!profile.disableAllTools && !profile.disableRemoteTools && features.githubProfileOverrides[profile.uid]?.githubTools == true) { "Remote GitHub tool permission changed before submission." }
        val selected = profile.copy(model = dao.selectedModel(binding.chatId, profile.uid) ?: profile.model, reasoning = features.conversationReasoning[binding.chatId] ?: profile.reasoning)
        check(selected.model == binding.request.model) { "The original chat model changed before submission." }
        verifyCurrentSelection(selected, requireNotNull(binding.executionSelection), binding.executionAdmission?.binding)
        val configured = features.githubProfileOverrides[profile.uid]?.engineeringGatewayProfileUid
        check(configured == binding.profileUid || configured == null && binding.request.conversationId != null) { "The original engineering gateway selection changed before submission." }
        binding.originalContext?.let { original ->
            val current = features.conversationGitHub[binding.chatId]
            check(current != null && sameWorkspace(original, current)) { "The original GitHub workspace changed before submission." }
        }
    }

    private fun sameWorkspace(original: GitHubConversationContext, current: GitHubConversationContext): Boolean = original.connectionUid == current.connectionUid && original.owner == current.owner && original.repo == current.repo && original.ref == current.ref && original.mainProfileUid == current.mainProfileUid

    /** Reads retained server history without fabricating a local workspace or execution profile. */
    suspend fun prepareTranscript(connectionProfileUid: String, gatewayId: String, principalId: String, conversationId: String): String = attachmentLock.withLock {
        val route = jobs.originalRoute(connectionProfileUid, jobs.route(connectionProfileUid, useEngineeringGateway = false).endpoint)
        val snapshot = client.conversation(route.config, conversationId)
        requireSameIdentity(snapshot, gatewayId, principalId, conversationId)
        val local = attachSnapshot(route, snapshot, null, createRoom = false)
        sync(local, snapshot)
        local.key
    }

    suspend fun refreshTranscript(conversationKey: String) = attachmentLock.withLock {
        val local = requireNotNull(dao.conversation(conversationKey)) { "The retained conversation is unavailable." }
        val route = jobs.originalRoute(local.connectionProfileUid, local.endpoint)
        val snapshot = client.conversation(route.config, local.conversationId)
        requireSameIdentity(snapshot, local.gatewayId, local.principalId, local.conversationId)
        val retained = retainSnapshot(local, route, snapshot) ?: return@withLock
        sync(retained, snapshot)
    }

    suspend fun transcriptPage(conversationKey: String, cursor: String? = null): GatewayLocalTranscriptPage {
        val local = requireNotNull(dao.conversation(conversationKey)) { "The retained conversation is unavailable." }
        val snapshot = json.decodeFromString<GatewayConversationSnapshot>(local.snapshot)
        if (cursor?.startsWith("retained:") == true || cursor == null && dao.transcriptParts(local.key, 0).isEmpty()) retainedTranscriptPage(local, snapshot, cursor)?.let { return it }
        val offset = cursor?.let {
            val values = it.split(':')
            require(values.size == 2 && values[0].toLongOrNull() == local.revision) { "The saved transcript changed. Refresh its first page." }
            requireNotNull(values[1].toIntOrNull()).also { value -> require(value in 0..10_000_000) }
        } ?: 0
        val parts = dao.transcriptParts(local.key, offset)
        val complete = local.historyComplete && !local.syncHasMore && dao.incompleteMessageParts(local.key) == 0
        return GatewayLocalTranscriptPage(local.key, snapshot.title, local.executionProfileUid.takeIf(String::isNotBlank), complete, local.historyLimitation ?: if (!complete) "More retained transcript parts remain to be read or verified." else null, parts.map { GatewayTranscriptPart(it.messageId, it.logicalMessageId, it.role, it.kind, it.content, it.partIndex, it.partCount, it.contentVerified) }, if (parts.size == 8) "${local.revision}:${offset + parts.size}" else null)
    }

    /** Archived alternate-v50 rows remain inspectable in bounded slices; never project them as a new answer. */
    private fun retainedTranscriptPage(local: GatewayConversationRecord, snapshot: GatewayConversationSnapshot, cursor: String?): GatewayLocalTranscriptPage? {
        val db = database.openHelper.readableDatabase
        if (!db.query("SELECT 1 FROM sqlite_master WHERE name='retained_v50_gateway_messages'").use { it.moveToFirst() }) return null
        val values = cursor?.split(':')
        val sequence = values?.getOrNull(1)?.toLongOrNull() ?: 0
        val slice = values?.getOrNull(2)?.toIntOrNull() ?: 0
        require(sequence >= 0 && slice in 0..1_000_000)
        val items = mutableListOf<GatewayTranscriptPart>()
        var next: String? = null
        db.query("SELECT m.messageId,m.logicalMessageId,m.role,m.kind,m.sequence,length(m.content),substr(m.content,?,65536) FROM retained_v50_gateway_messages m INNER JOIN retained_v50_gateway_conversations c ON c.id=m.conversationKey INNER JOIN retained_v50_gateway_scopes s ON s.id=c.scopeId WHERE c.conversationId=? AND s.gatewayId=? AND s.principalId=? AND m.sequence>=? ORDER BY m.sequence LIMIT 1", arrayOf<Any?>(slice * 65536 + 1, local.conversationId, local.gatewayId, local.principalId, sequence)).use { rows ->
            if (rows.moveToFirst()) {
                val count = maxOf(1, (rows.getInt(5) + 65535) / 65536)
                check(slice < count) { "The retained transcript cursor is invalid." }
                items += GatewayTranscriptPart("${rows.getString(0)}:$slice", rows.getString(1), rows.getString(2), "retained_v50_evidence", rows.getString(6), slice, count, false)
                next = if (slice + 1 < count) "retained:${rows.getLong(4)}:${slice + 1}" else "retained:${rows.getLong(4) + 1}:0"
            }
        }
        return GatewayLocalTranscriptPage(local.key, snapshot.title, local.executionProfileUid.takeIf(String::isNotBlank), false, "Retained earlier-format evidence. Its original parts are preserved; reconnect to verify canonical history before continuing work.", items, next)
    }

    suspend fun clientConversationId(chatId: Int): String {
        check(dao.localChat(chatId)?.isTemporary == false) { "Temporary chats do not retain a gateway transcript." }
        val previousKey = dao.migration("conversation-client-key:$chatId")?.checksum
        return settings.ensureConversationIdentity(chatId, previousKey)
    }

    suspend fun savedConversation(chatId: Int, connectionProfileUid: String, endpoint: String, principalId: String, executionProfileUid: String): GatewayConversationRecord? =
        dao.conversationsForChat(chatId).singleOrNull {
            it.connectionProfileUid == connectionProfileUid && it.endpoint == endpoint && it.principalId == principalId && it.executionProfileUid == executionProfileUid
        }

    /** Routing for a Send in an attached conversation; never chooses the active workspace or account. */
    suspend fun requestBinding(chatId: Int, profileUid: String, endpoint: String, model: String): GatewayConversationRecord? {
        val retained = dao.conversationsForChat(chatId).filter { it.executionProfileUid == profileUid }
        if (retained.isEmpty()) return null
        val matching = retained.filter { it.endpoint == endpoint.trimEnd('/') }
        check(matching.size == 1) { "Restore the original gateway connection and AI profile to send in this saved conversation." }
        val binding = matching.single()
        val snapshot = json.decodeFromString<GatewayConversationSnapshot>(binding.snapshot)
        check(snapshot.model.isBlank() || snapshot.model == model) { "This saved conversation uses ${snapshot.model}. Restore its original model before sending." }
        return binding
    }

    suspend fun boundSnapshot(chatId: Int, profileUid: String, model: String): Pair<GatewayConversationRecord, GatewayConversationSnapshot>? {
        val retained = dao.conversationsForChat(chatId).filter { it.executionProfileUid == profileUid }
        if (retained.isEmpty()) return null
        check(retained.size == 1) { "This local room has multiple saved gateway bindings. Open the intended server conversation before sending." }
        val binding = retained.single()
        val route = jobs.originalRoute(binding.connectionProfileUid, binding.endpoint)
        val snapshot = client.conversation(route.config, binding.conversationId)
        requireSameIdentity(snapshot, binding.gatewayId, binding.principalId, binding.conversationId)
        check(snapshot.profileId == profileUid && snapshot.model == model) { "The saved conversation requires its original AI profile and model." }
        val local = requireNotNull(retainSnapshot(binding, route, snapshot)) { "The original conversation was removed during verification." }
        return local to snapshot
    }

    suspend fun attach(jobKey: String): GatewayConversationAttachment = attachmentLock.withLock {
        val record = requireNotNull(dao.job(jobKey)) { "This job has no verified saved gateway record. Open its retained evidence instead." }
        val job = jobs.summary(record)
        val conversationId = requireNotNull(job.conversationId) { "This legacy job retained evidence but no complete conversation." }
        val route = jobs.originalRoute(record.connectionProfileUid, record.endpoint)
        reconcileOrdinaryAdmissions(connectionProfileUid = route.profileUid, endpoint = route.endpoint)
        reconcileEngineeringAdmissions(route)
        val snapshot = client.conversation(route.config, conversationId)
        requireSameIdentity(snapshot, record.gatewayId, record.principalId, conversationId)
        val local = attachSnapshot(route, snapshot, record.executionProfileUid)
        sync(local, snapshot)
        dao.markSeen(record.key, job.revision)
        attachment(local)
    }

    suspend fun openConversation(profileUid: String, gatewayId: String, principalId: String, conversationId: String): GatewayConversationAttachment = attachmentLock.withLock {
        val route = jobs.route(profileUid, useEngineeringGateway = false)
        reconcileOrdinaryAdmissions(connectionProfileUid = route.profileUid, endpoint = route.endpoint)
        reconcileEngineeringAdmissions(route)
        val snapshot = client.conversation(route.config, conversationId)
        requireSameIdentity(snapshot, gatewayId, principalId, conversationId)
        val local = attachSnapshot(route, snapshot, snapshot.profileId)
        sync(local, snapshot)
        attachment(local)
    }

    /** Only admission receipts call this association; opening a row never adopts the active workspace. */
    suspend fun associate(chatId: Int, profileUid: String, endpoint: String, gatewayId: String, principalId: String, conversationId: String, executionProfileUid: String = profileUid) = attachmentLock.withLock {
        retainReceipt(chatId, profileUid, endpoint, gatewayId, principalId, conversationId, executionProfileUid)
        val expected = requireNotNull(dao.conversation(gatewayIdentityKey(gatewayId, principalId, conversationId)))
        val route = jobs.originalRoute(profileUid, endpoint)
        val snapshot = client.conversation(route.config, conversationId)
        requireSameIdentity(snapshot, gatewayId, principalId, conversationId)
        val local = retainSnapshot(expected, route, snapshot) ?: return@withLock
        sync(local, snapshot)
    }

    /** The command is a hash of the frozen request. This receipt-only reservation never replays it. */
    suspend fun reserveOrdinaryTurn(chatId: Int, profileUid: String, endpoint: String, commandId: String, userMessageId: Int, assistantMessageId: Int, runId: String, selectedModel: String) {
        require(commandId.length in 1..128 && selectedModel.isNotBlank())
        val route = jobs.originalRoute(profileUid, endpoint)
        val scope = client.scope(route.config)
        val payload = json.encodeToString(GatewayOrdinaryReservation("ordinary", selectedModel, scope.gatewayId, scope.principalId))
        val fingerprint = gatewayIdentityKey(commandId, profileUid, endpoint.trimEnd('/'), selectedModel, chatId.toString(), userMessageId.toString(), assistantMessageId.toString(), runId)
        database.withTransaction {
            val run = requireNotNull(database.agentRunDao().getById(runId)) { "The original chat run is unavailable." }
            val message = requireNotNull(dao.localMessage(assistantMessageId))
            check(dao.localChat(chatId)?.isTemporary == false && dao.localMessage(userMessageId)?.chatId == chatId) { "Temporary or missing turns cannot retain a server transcript." }
            check(run.chatId == chatId && run.userMessageId == userMessageId && run.assistantMessageId == assistantMessageId && run.profileUid == profileUid && run.modelSnapshot == selectedModel && run.status in setOf("RUNNING", "QUEUED") && message.currentRunId == runId && message.ownershipFence == run.ownershipFence) { "This turn was stopped or replaced before gateway admission." }
            val existing = dao.submission(commandId)
            if (existing != null) {
                check(existing.fingerprint == fingerprint && existing.payload == payload) { "The frozen gateway command or account changed." }
            } else {
                val createdAt = maxOf(System.currentTimeMillis(), (dao.latestSubmissionForRun(runId)?.createdAtMillis ?: 0) + 1)
                dao.insertSubmission(GatewaySubmissionRecord(commandId, chatId, userMessageId, assistantMessageId, runId, profileUid, endpoint.trimEnd('/'), profileUid, fingerprint, payload, "PENDING_ORDINARY", createdAtMillis = createdAt))
            }
        }
    }

    /** A notice only: lifecycle observers never turn an uncertain Stop into another request. */
    suspend fun unconfirmedOrdinaryStops(runIds: Set<String>): Map<String, Int> = buildMap {
        for (runId in runIds) {
            val run = database.agentRunDao().getById(runId) ?: continue
            if (run.status != "CANCELED" || run.gatewayJobId != null) continue
            val submission = dao.latestSubmissionForRun(runId) ?: continue
            val ordinary = runCatching { json.decodeFromString<GatewayOrdinaryReservation>(submission.payload) }.getOrNull()
            if (ordinary?.reservationKind == "ordinary" && submission.chatId == run.chatId) put(runId, run.chatId)
        }
    }

    /** Null is legacy-only. A saved canonical command with missing authority must fail closed. */
    suspend fun ordinaryCancellationConfig(run: AgentRun): ProviderRequestConfig? {
        val submission = dao.latestSubmissionForRun(run.runId)
        if (submission == null) {
            check(
                dao.conversationsForChat(run.chatId).none { it.executionProfileUid == run.profileUid } &&
                    dao.bindings(run.chatId).none {
                        json.decodeFromString<GatewayEngineeringBinding>(it.binding).localRunId == run.runId
                    }
            ) { "The saved gateway command authority is unavailable. Check its original job before confirming Stop." }
            return null
        }
        check(submission.chatId == run.chatId && submission.userMessageId == run.userMessageId && submission.assistantMessageId == run.assistantMessageId && submission.executionProfileUid == run.profileUid && submission.connectionProfileUid == run.profileUid && submission.endpoint == run.gatewayBaseUrl?.trimEnd('/')) { "The saved gateway command belongs to another execution destination." }
        check(dao.binding(submission.commandId) == null) { "Engineering jobs require their original engineering control." }
        val reservation = runCatching { json.decodeFromString<GatewayOrdinaryReservation>(submission.payload) }.getOrNull()
        check(reservation != null || submission.payload.isBlank() && submission.state == "ACCEPTED") { "The saved ordinary command authority is malformed. No Stop request was sent." }
        val conversation = submission.conversationKey?.let { requireNotNull(dao.conversation(it)) { "The original gateway conversation authority is unavailable." } }
        if (reservation != null) check(reservation.reservationKind == "ordinary" && reservation.model == run.modelSnapshot) { "The saved ordinary command identity changed." }
        if (conversation != null) {
            check(conversation.localChatId == run.chatId && conversation.executionProfileUid == run.profileUid && conversation.connectionProfileUid == submission.connectionProfileUid && conversation.endpoint == submission.endpoint) { "The original gateway conversation destination changed." }
            val snapshot = json.decodeFromString<GatewayConversationSnapshot>(conversation.snapshot)
            check(snapshot.model.isBlank() || snapshot.model == run.modelSnapshot) { "The saved gateway model changed." }
            check(reservation == null || reservation.gatewayId == conversation.gatewayId && reservation.principalId == conversation.principalId) { "The saved command and conversation accounts disagree." }
        }
        val expected = reservation?.let { GatewayScopeIdentity(it.gatewayId, it.principalId) }
            ?: conversation?.let { GatewayScopeIdentity(it.gatewayId, it.principalId) }
            ?: error("The saved gateway installation and account are unverified. No Stop request was sent.")
        requireGatewayIdentity(expected.gatewayId, expected.principalId)
        val route = jobs.originalRoute(submission.connectionProfileUid, submission.endpoint)
        val identity = client.identity(route.config)
        check(identity == expected) { "The original gateway installation or account changed. No Stop request was sent." }
        val profile = settings.fetchPlatformV2s().singleOrNull { it.uid == run.profileUid && it.enabled }
        check(profile != null && profile.compatibleType.name == run.providerSnapshot && GitHubAutonomousEngineeringPolicy.supportsSavedJobs(profile)) { "Restore the original gateway provider before confirming Stop." }
        check(dao.latestSubmissionForRun(run.runId) == submission) { "The active saved gateway command changed while Stop was being verified." }
        val current = database.agentRunDao().getById(run.runId)
        val message = dao.localMessage(run.assistantMessageId)
        check(current != null && current.gatewayJobId == run.gatewayJobId && current.gatewayBaseUrl == run.gatewayBaseUrl && current.ownershipFence == run.ownershipFence && message != null && message.chatId == run.chatId && message.currentRunId == run.runId && message.ownershipFence == run.ownershipFence) { "The original reply owner changed while Stop was being verified." }
        check(jobs.originalRoute(submission.connectionProfileUid, submission.endpoint).config == route.config) { "The original gateway credentials changed before Stop." }
        return route.config.withGatewayIdentity(identity)
    }

    /** Resolve an ambiguous ordinary admission without dispatching a replacement generation. */
    suspend fun reconcileOrdinaryAdmissions(chatId: Int? = null, connectionProfileUid: String? = null, endpoint: String? = null) {
        for (pending in dao.pendingOrdinary(chatId).asReversed()) {
            if (connectionProfileUid != null && (pending.connectionProfileUid != connectionProfileUid || pending.endpoint != endpoint?.trimEnd('/'))) continue
            val reservation = json.decodeFromString<GatewayOrdinaryReservation>(pending.payload)
            val route = jobs.originalRoute(pending.connectionProfileUid, pending.endpoint)
            val receipt = client.command(route.config, pending.commandId) ?: continue
            check(receipt.jobKind == "generation" && receipt.gatewayId == reservation.gatewayId && receipt.principalId == reservation.principalId && (receipt.profileId == null || receipt.profileId == pending.executionProfileUid) && (receipt.model == null || receipt.model == reservation.model)) { "The original gateway command account or execution profile changed." }
            val conversationKey = gatewayIdentityKey(receipt.gatewayId, receipt.principalId, receipt.conversationId)
            database.withTransaction {
                dao.saveSubmission(pending.copy(state = "ACCEPTED", conversationKey = conversationKey, receipt = json.encodeToString(receipt)))
                if (dao.localChat(pending.chatId) != null) retainReceipt(pending.chatId, pending.connectionProfileUid, pending.endpoint, receipt.gatewayId, receipt.principalId, receipt.conversationId, pending.executionProfileUid)
                pending.runId?.let { runId ->
                    val run = database.agentRunDao().getById(runId)
                    if (run != null && dao.latestSubmissionForRun(runId)?.commandId == pending.commandId) {
                        val existingRecovery = GatewayOutcomeRecovery.canReadSavedResult(run)
                        val terminalError = run.terminalError.orEmpty()
                        val acceptedTransportFailure = run.status in setOf("FAILED", "PARTIAL", "EVIDENCE_MISSING") &&
                            terminalError != "BACKUP_RESTORED" &&
                            !terminalError.startsWith("POLICY_SNAPSHOT_") &&
                            !terminalError.contains("GATEWAY_SAVED_RESULT_") &&
                            !terminalError.contains("GATEWAY_RECOVERED_RESULT_UNVERIFIED")
                        if (existingRecovery || acceptedTransportFailure || run.status in setOf("QUEUED", "RUNNING", "WAITING_REMOTE_JOB")) {
                            dao.bindOrdinaryReceipt(runId, run.assistantMessageId, run.ownershipFence, receipt.jobId, pending.endpoint, run.status, run.terminalError, restoreInterrupted = acceptedTransportFailure && !existingRecovery)
                        }
                    }
                }
            }
        }
    }

    private suspend fun reconcileEngineeringAdmissions(route: GatewayJobRoute) {
        for (pending in dao.pendingEngineering()) {
            if (pending.connectionProfileUid != route.profileUid || pending.endpoint != route.endpoint) continue
            val binding = dao.binding(pending.commandId)?.let { json.decodeFromString<GatewayEngineeringBinding>(it.binding) } ?: continue
            val receipt = client.command(route.config, pending.commandId) ?: continue
            check(receipt.jobKind == "engineering" && receipt.principalId == binding.principalId && (binding.gatewayId == null || receipt.gatewayId == binding.gatewayId) && receipt.profileId == binding.request.profileId && receipt.model == binding.request.model && receipt.repository == binding.request.repository && gatewayWorkingBranchMatches(binding.request, receipt.branch, receipt.sourceBranch, receipt.sourceSha, receipt.workingBranch) && receipt.base == binding.request.base) { "The retained engineering receipt changed its original execution destination." }
            val key = gatewayIdentityKey(receipt.gatewayId, receipt.principalId, receipt.conversationId)
            database.withTransaction {
                dao.saveSubmission(pending.copy(state = "ACCEPTED", conversationKey = key, receipt = json.encodeToString(receipt)))
                if (dao.localChat(pending.chatId) != null) retainReceipt(pending.chatId, pending.connectionProfileUid, pending.endpoint, receipt.gatewayId, receipt.principalId, receipt.conversationId, pending.executionProfileUid)
            }
        }
    }

    /** Capture an ordinary generation receipt before importing its user and assistant messages. */
    suspend fun associateTurn(
        chatId: Int,
        profileUid: String,
        endpoint: String,
        gatewayId: String,
        principalId: String,
        conversationId: String,
        commandId: String,
        userMessageId: Int,
        assistantMessageId: Int,
        runId: String
    ) {
        requireGatewayIdentity(gatewayId, principalId)
        val key = gatewayIdentityKey(gatewayId, principalId, conversationId)
        database.withTransaction {
            check(dao.localChat(chatId)?.isTemporary == false) { "Temporary chats do not retain a gateway transcript." }
            check(dao.localMessage(userMessageId)?.chatId == chatId && dao.localMessage(assistantMessageId)?.chatId == chatId) { "The original conversation turn is unavailable." }
            val old = dao.submission(commandId)
            if (old != null) {
                if (old.state == "PENDING_ORDINARY") {
                    val expected = json.decodeFromString<GatewayOrdinaryReservation>(old.payload)
                    check(expected.gatewayId == gatewayId && expected.principalId == principalId) { "The admission receipt came from a different gateway or account." }
                }
                check(old.chatId == chatId && old.userMessageId == userMessageId && old.assistantMessageId == assistantMessageId && old.runId == runId && old.endpoint == endpoint.trimEnd('/')) { "The saved command is already associated with another turn." }
                check(old.conversationKey == null || old.conversationKey == key) { "The gateway conversation identity changed." }
                dao.saveSubmission(old.copy(state = "ACCEPTED", conversationKey = key))
            } else {
                dao.saveSubmission(
                    GatewaySubmissionRecord(
                        commandId, chatId, userMessageId, assistantMessageId, runId, profileUid, endpoint.trimEnd('/'), profileUid,
                        gatewayIdentityKey("observed-admission", gatewayId, principalId, conversationId, commandId), "", "ACCEPTED", key,
                        createdAtMillis = System.currentTimeMillis()
                    )
                )
            }
            retainReceipt(chatId, profileUid, endpoint, gatewayId, principalId, conversationId, profileUid)
        }
        associate(chatId, profileUid, endpoint, gatewayId, principalId, conversationId)
    }

    suspend fun recordEngineering(binding: GatewayEngineeringBinding) {
        val job = binding.job ?: return
        val gatewayId = job.gatewayId ?: return
        val conversationId = job.conversationId ?: return
        val principal = binding.principalId ?: return
        check(job.principalId == null || job.principalId == principal)
        binding.localRunId?.let { runId ->
            val messageId = binding.localAssistantMessageId ?: return@let
            val message = dao.localMessage(messageId) ?: return@let
            if (message.chatId == binding.chatId && message.currentRunId == runId) dao.recoverRemoteRun(runId, message.id, message.ownershipFence)
        }
        dao.conversationsForChat(binding.chatId).filter { it.gatewayId == gatewayId && it.principalId == principal && it.conversationId == conversationId }.forEach { projectRetained(it) }
        associate(binding.chatId, binding.profileUid, binding.endpoint, gatewayId, principal, conversationId, binding.request.profileId ?: binding.profileUid)
    }

    suspend fun needsEngineeringRefresh(binding: GatewayEngineeringBinding): Boolean {
        val job = binding.job ?: return true
        val gatewayId = job.gatewayId ?: return false
        val principal = binding.principalId ?: return false
        val conversationId = job.conversationId ?: return false
        val local = dao.conversation(gatewayIdentityKey(gatewayId, principal, conversationId)) ?: return true
        if (local.syncHasMore || local.syncedAtMillis == 0L || local.revision < json.decodeFromString<GatewayConversationSnapshot>(local.snapshot).revision) return true
        return binding.localRunId?.let { database.agentRunDao().getById(it)?.status in setOf("WAITING_REMOTE_JOB", "INTERRUPTED") } == true
    }

    /** An accepted receipt is sufficient to retain routing even if the first transcript GET fails. */
    private suspend fun retainReceipt(chatId: Int, profileUid: String, endpoint: String, gatewayId: String, principalId: String, conversationId: String, executionProfileUid: String) {
        requireGatewayIdentity(gatewayId, principalId)
        val key = gatewayIdentityKey(gatewayId, principalId, conversationId)
        database.withTransaction {
            val room = requireNotNull(dao.localChat(chatId)) { "The original local conversation is unavailable." }
            check(!room.isTemporary) { "Temporary chats do not retain a gateway transcript." }
            val existing = dao.conversation(key)
            if (existing != null) {
                check((existing.localChatId == null || existing.localChatId == chatId) && (existing.executionProfileUid.isBlank() || existing.executionProfileUid == executionProfileUid) && existing.connectionProfileUid == profileUid && existing.endpoint == endpoint.trimEnd('/')) { "The saved gateway receipt is already bound to another conversation or connection." }
                if (existing.localChatId == null) dao.saveConversation(existing.copy(localChatId = chatId, executionProfileUid = executionProfileUid, messageCursor = null, revision = -1, syncHasMore = true))
            } else {
                val receiptSnapshot = GatewayConversationSnapshot(gatewayId = gatewayId, principalId = principalId, conversationId = conversationId, title = room.title, profileId = executionProfileUid, revision = -1)
                dao.saveConversation(GatewayConversationRecord(key, gatewayId, principalId, conversationId, profileUid, endpoint.trimEnd('/'), executionProfileUid, chatId, json.encodeToString(receiptSnapshot)))
            }
        }
    }

    /** Persist the exact instruction command before dispatch; only an explicit UI action calls this. */
    suspend fun queueInstruction(jobKey: String, content: String, commandId: String) = attachmentLock.withLock {
        require(content.isNotBlank() && content.length <= 16000 && commandId.length in 1..128)
        val record = requireNotNull(dao.job(jobKey)) { "Refresh the gateway jobs before adding an instruction." }
        val job = jobs.summary(record)
        check(job.acceptsInstruction) { "Instructions require an active engineering job." }
        val conversationId = requireNotNull(job.conversationId) { "This legacy job does not accept conversation instructions." }
        val route = jobs.originalRoute(record.connectionProfileUid, record.endpoint)
        val snapshot = client.conversation(route.config, conversationId)
        requireSameIdentity(snapshot, record.gatewayId, record.principalId, conversationId)
        val local = attachSnapshot(route, snapshot, record.executionProfileUid)
        val existing = dao.submission(commandId)
        val command = if (existing == null) {
            GatewayInstructionCommand(commandId, content, snapshot.revision)
        } else {
            check(existing.conversationKey == local.key && existing.connectionProfileUid == route.profileUid && existing.endpoint == route.endpoint) { "The saved instruction belongs to another gateway conversation." }
            json.decodeFromString<GatewayInstructionCommand>(existing.payload).also { check(it.content == content) { "A retry must preserve the original instruction." } }
        }
        val payload = Json { encodeDefaults = true }.encodeToString(command)
        if (existing == null) {
            database.withTransaction {
                dao.insertSubmission(GatewaySubmissionRecord(commandId, requireNotNull(local.localChatId), null, null, null, route.profileUid, route.endpoint, local.executionProfileUid, gatewayIdentityKey(payload), payload, "PENDING_INSTRUCTION", local.key, createdAtMillis = System.currentTimeMillis()))
                check(dao.submission(commandId)?.fingerprint == gatewayIdentityKey(payload)) { "The instruction command identity changed." }
            }
        }
        val receipt = try {
            val retained = if (existing != null) client.command(route.config, commandId) else null
            retained ?: run {
                val identity = client.identity(route.config)
                check(identity.gatewayId == record.gatewayId && identity.principalId == record.principalId) { "The original gateway or account changed before the instruction." }
                check(jobs.originalRoute(record.connectionProfileUid, record.endpoint).config == route.config) { "The original gateway credentials changed before the instruction." }
                commands.instruction(route.config.withGatewayIdentity(identity), conversationId, command)
            }
        } catch (conflict: GatewayConversationConflict) {
            dao.submission(commandId)?.let { dao.saveSubmission(it.copy(state = "INSTRUCTION_CONFLICT")) }
            throw conflict
        }
        acceptInstruction(local, receipt)
        try {
            sync(local, client.conversation(route.config, conversationId).also { requireSameIdentity(it, local.gatewayId, local.principalId, local.conversationId) })
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The accepted instruction is durable; a later GET repairs the display transcript.
        }
    }

    /** Lost instruction replies are reconciled with GET only, even after process death. */
    suspend fun refreshPendingInstructions(chatId: Int? = null) {
        for (pending in dao.pendingInstructions(chatId)) {
            val local = pending.conversationKey?.let { dao.conversation(it) } ?: continue
            val route = jobs.originalRoute(pending.connectionProfileUid, pending.endpoint)
            val receipt = client.command(route.config, pending.commandId) ?: continue
            acceptInstruction(local, receipt)
        }
    }

    private suspend fun acceptInstruction(local: GatewayConversationRecord, receipt: GatewayCommandReceipt) = database.withTransaction {
        check(receipt.gatewayId == local.gatewayId && receipt.principalId == local.principalId && receipt.conversationId == local.conversationId && receipt.jobKind == "engineering") { "The gateway instruction identity changed." }
        val pending = requireNotNull(dao.submission(receipt.commandId))
        check(pending.conversationKey == local.key)
        dao.saveSubmission(pending.copy(state = "ACCEPTED_INSTRUCTION", receipt = json.encodeToString(receipt)))
    }

    suspend fun refreshChat(chatId: Int) = attachmentLock.withLock {
        reconcileOrdinaryAdmissions(chatId)
        refreshPendingInstructions(chatId)
        for (local in dao.conversationsForChat(chatId)) {
            projectRetained(local)
            val route = jobs.originalRoute(local.connectionProfileUid, local.endpoint)
            val snapshot = client.conversation(route.config, local.conversationId)
            requireSameIdentity(snapshot, local.gatewayId, local.principalId, local.conversationId)
            val retained = retainSnapshot(local, route, snapshot) ?: continue
            sync(retained, snapshot)
        }
    }

    suspend fun cachedAttachment(jobKey: String): GatewayConversationAttachment? {
        val job = dao.job(jobKey) ?: return null
        val local = job.conversationKey?.let { dao.conversation(it) } ?: return null
        if (dao.localChat(local.localChatId) == null) return null
        return attachment(local)
    }

    private suspend fun attachSnapshot(route: GatewayJobRoute, snapshot: GatewayConversationSnapshot, fallbackProfileUid: String?, originalChatId: Int? = null, createRoom: Boolean = true): GatewayConversationRecord {
        check(snapshot.transcriptAvailable) { "This conversation has no retained transcript." }
        val key = gatewayIdentityKey(snapshot.gatewayId, snapshot.principalId, snapshot.conversationId)
        val profileUid = snapshot.profileId?.takeIf(String::isNotBlank) ?: fallbackProfileUid?.takeIf(String::isNotBlank) ?: ""
        check(!createRoom || profileUid.isNotBlank()) { "The original AI profile identity is unavailable. Read its saved transcript without assigning it to another profile." }
        return database.withTransaction {
            val previous = dao.conversation(key)
            if (previous != null) {
                check(previous.gatewayId == snapshot.gatewayId && previous.principalId == snapshot.principalId && (previous.executionProfileUid.isBlank() || previous.executionProfileUid == profileUid)) { "The saved conversation binding changed." }
                check(originalChatId == null || previous.localChatId == originalChatId || dao.localChat(previous.localChatId) == null) { "This gateway conversation is already linked to another local room." }
            }
            val original = if (previous == null) dao.originalSubmission(key)?.takeIf { it.executionProfileUid == profileUid && dao.localChat(it.chatId) != null && it.userMessageId?.let { id -> dao.localMessage(id)?.chatId == it.chatId } == true } else null
            val retainedRoomExists = previous?.localChatId?.let { dao.localChat(it) != null } == true
            val roomId = originalChatId?.takeIf { dao.localChat(it) != null }
                ?: previous?.localChatId?.takeIf { dao.localChat(it) != null }
                ?: original?.chatId
                ?: if (createRoom) {
                    database.chatRoomDao().addChatRoom(
                        ChatRoomV2(title = snapshot.title.take(160), enabledPlatform = listOf(profileUid), activePlatform = listOf(profileUid))
                    ).toInt()
                } else {
                    null
                }
            check(roomId == null || dao.localChat(roomId)?.isTemporary == false) { "Temporary chats do not import a retained remote transcript." }
            if (roomId != null) database.chatPlatformModelDao().upsertChatPlatformModel(ChatPlatformModelV2(roomId, profileUid, snapshot.model))
            val keepCursor = retainedRoomExists || !createRoom
            GatewayConversationRecord(
                key, snapshot.gatewayId, snapshot.principalId, snapshot.conversationId,
                previous?.connectionProfileUid ?: original?.connectionProfileUid ?: route.profileUid, previous?.endpoint ?: original?.endpoint ?: route.endpoint,
                profileUid, roomId, json.encodeToString(snapshot), if (keepCursor) previous?.revision ?: -1 else -1,
                if (keepCursor) previous?.messageCursor else null, if (keepCursor) previous?.syncedAtMillis ?: 0 else 0,
                if (keepCursor) previous?.syncHasMore ?: false else false,
                previous?.historyComplete != false && snapshot.historyComplete, previous?.historyLimitation ?: snapshot.historyLimitation
            ).also { dao.saveConversation(it) }
        }
    }

    private suspend fun retainSnapshot(expected: GatewayConversationRecord, route: GatewayJobRoute, snapshot: GatewayConversationSnapshot): GatewayConversationRecord? = database.withTransaction {
        val current = dao.conversation(expected.key) ?: return@withTransaction null
        if (current.localChatId != expected.localChatId || current.connectionProfileUid != route.profileUid || current.endpoint != route.endpoint) return@withTransaction null
        if (current.localChatId != null && dao.localChat(current.localChatId)?.isTemporary != false) return@withTransaction null
        val previous = json.decodeFromString<GatewayConversationSnapshot>(current.snapshot)
        requireSameIdentity(snapshot, current.gatewayId, current.principalId, current.conversationId)
        check(previous.model.isBlank() || previous.model == snapshot.model) { "The gateway changed the original model." }
        check(previous.profileId == null || previous.profileId == snapshot.profileId) { "The gateway changed the original profile." }
        check(previous.repository == null || previous.repository == snapshot.repository) { "The gateway changed the original repository." }
        check(previous.sourceBranch == null || previous.sourceBranch == snapshot.sourceBranch) { "The gateway changed the original source branch." }
        check(previous.sourceSha == null || previous.sourceSha == snapshot.sourceSha) { "The gateway changed the original source commit." }
        check(previous.executionBinding == null || previous.executionBinding == snapshot.executionBinding) { "The gateway changed the original execution binding." }
        current.copy(snapshot = json.encodeToString(snapshot)).also { dao.saveConversation(it) }
    }

    private suspend fun projectRetained(local: GatewayConversationRecord) = database.withTransaction {
        if (dao.conversation(local.key)?.localChatId != local.localChatId || dao.localChat(local.localChatId)?.isTemporary != false) return@withTransaction
        for (row in dao.bindings(local.localChatId)) {
            val binding = json.decodeFromString<GatewayEngineeringBinding>(row.binding)
            val job = binding.job ?: continue
            if (job.conversationId != local.conversationId || job.gatewayId != local.gatewayId || binding.principalId != local.principalId) continue
            val runId = binding.localRunId ?: continue
            val message = binding.localAssistantMessageId?.let { dao.localMessage(it) } ?: continue
            if (message.chatId == local.localChatId && message.currentRunId == runId) dao.recoverRemoteRun(runId, message.id, message.ownershipFence)
        }
        reproject(local)
    }

    private suspend fun sync(initial: GatewayConversationRecord, snapshot: GatewayConversationSnapshot) {
        val route = jobs.originalRoute(initial.connectionProfileUid, initial.endpoint)
        var local = initial
        if (local.messageCursor == null && local.historyLimitation?.startsWith("Retained from the earlier gateway history format.") == true) local = local.copy(historyComplete = snapshot.historyComplete, historyLimitation = snapshot.historyLimitation)
        // Bounded pages keep a large remote conversation from monopolizing a foreground observer.
        var pageCount = 0
        var resetCursor = false
        var more: Boolean
        do {
            val page = try {
                client.messages(route.config, local.conversationId, local.messageCursor)
            } catch (expired: GatewayCursorExpired) {
                if (local.messageCursor == null || resetCursor) throw expired
                resetCursor = true
                // A fresh read replays stable message IDs; the old cursor is replaced only with saved output.
                client.messages(route.config, local.conversationId, null)
            }
            check(page.gatewayId == local.gatewayId && page.principalId == local.principalId && page.conversationId == local.conversationId) { "The gateway conversation identity changed." }
            check(page.revision >= local.revision) { "The gateway returned an older conversation snapshot." }
            check(page.messages.all { it.revision <= page.revision }) { "A transcript message exceeds its snapshot watermark." }
            val applied = database.withTransaction {
                val retained = dao.conversation(local.key)
                if (retained == null || retained.localChatId != local.localChatId || local.localChatId != null && dao.localChat(local.localChatId)?.isTemporary != false) return@withTransaction false
                for (message in page.messages) for (part in GatewayLogicalMessageAssembler.boundedParts(message)) importPart(local, part)
                check(page.hasMore || dao.incompleteMessageParts(local.key) == 0 || !page.historyComplete || page.gap) { "The gateway ended its transcript before all message parts were received." }
                local = local.copy(snapshot = json.encodeToString(snapshot), revision = page.revision, messageCursor = page.nextCursor, syncedAtMillis = System.currentTimeMillis(), syncHasMore = page.hasMore, historyComplete = local.historyComplete && snapshot.historyComplete && page.historyComplete && !page.gap, historyLimitation = local.historyLimitation ?: page.historyLimitation ?: if (page.gap) "Some earlier gateway history is no longer retained." else null)
                dao.saveConversation(local)
                reproject(local)
                true
            }
            if (!applied) return
            more = page.hasMore
            pageCount++
        } while (more && pageCount < 10)
    }

    private suspend fun importPart(conversation: GatewayConversationRecord, remote: GatewayTranscriptMessage) {
        val row = GatewayMessagePartRecord(conversation.key, remote.logicalMessageId, remote.revision, remote.partIndex, remote.partCount, remote.messageId, remote.turnId, remote.role, remote.kind, remote.content, remote.status, remote.publicationId, remote.commandId, remote.contentSha256, remote.createdAt)
        dao.physicalMessagePart(row.conversationKey, row.messageId)?.let { check(it.logicalMessageId == row.logicalMessageId) { "A gateway physical message identity was reused for another logical message." } }
        dao.message(row.conversationKey, row.logicalMessageId)?.let { check(it.role == row.role && it.turnId == row.turnId && it.publicationId == row.publicationId) { "The original saved message identity changed." } }
        val legacy = dao.messagePart(row.conversationKey, row.logicalMessageId, row.revision, 0)?.takeIf { it.kind == "legacy_cache" && it.contentVerified }
        if (legacy != null) {
            val incomingDigest = remote.contentSha256 ?: java.security.MessageDigest.getInstance("SHA-256").digest(remote.content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            check(legacy.contentSha256 == incomingDigest && legacy.role == row.role && legacy.turnId == row.turnId && legacy.publicationId == row.publicationId) { "Gateway replay conflicts with the retained pre-migration message." }
            val assembled = GatewayLogicalMessageAssembler.assemble(legacy) { offset -> dao.messageParts(row.conversationKey, row.logicalMessageId, row.revision, offset) }
            val content = assembled.content ?: largeTranscriptNotice(assembled.bytes)
            importMessage(conversation, remote.copy(messageId = row.logicalMessageId, content = content, partIndex = 0, partCount = 1))
            return
        }
        val previous = dao.messagePart(row.conversationKey, row.logicalMessageId, row.revision, row.partIndex)
        if (previous != null) {
            check(previous.copy(contentVerified = false, messageId = row.messageId) == row) { "The gateway reused a message part revision for different content." }
        } else {
            dao.saveMessagePart(row)
        }
        if (dao.messagePartCount(row.conversationKey, row.logicalMessageId, row.revision) != row.partCount) return
        val first = requireNotNull(dao.messagePart(row.conversationKey, row.logicalMessageId, row.revision, 0))
        val assembled = GatewayLogicalMessageAssembler.assemble(first) { offset -> dao.messageParts(row.conversationKey, row.logicalMessageId, row.revision, offset) }
        dao.verifyMessageParts(row.conversationKey, row.logicalMessageId, row.revision)
        dao.pruneMessageParts(row.conversationKey, row.logicalMessageId, row.revision)
        val content = assembled.content ?: largeTranscriptNotice(assembled.bytes)
        importMessage(conversation, remote.copy(messageId = row.logicalMessageId, content = content, partIndex = 0, partCount = 1))
    }

    private fun largeTranscriptNotice(bytes: Long): String = "This complete gateway message contains $bytes UTF-8 bytes. Open Saved transcript from this conversation in Gateway or Jobs to read every retained part."

    private suspend fun importMessage(conversation: GatewayConversationRecord, remote: GatewayTranscriptMessage) {
        val previous = dao.message(conversation.key, remote.messageId)
        if (previous != null && previous.revision > remote.revision) return
        if (previous != null && previous.revision == remote.revision) {
            check(previous.content == remote.content && previous.role == remote.role && previous.turnId == remote.turnId && previous.status == remote.status && previous.publicationId == remote.publicationId) { "The gateway reused a message revision for different content." }
            if (conversation.localChatId == null || previous.localMessageId?.let { dao.localMessage(it)?.chatId == conversation.localChatId } == true) return
        }
        if (previous != null) check(previous.role == remote.role && previous.turnId == remote.turnId && previous.publicationId == remote.publicationId) { "A saved message identity changed." }
        val submission = dao.submissionForTurn(conversation.key, remote.commandId ?: remote.turnId)
        val originalId = when (remote.role) {
            "user" -> submission?.userMessageId
            "assistant" -> submission?.assistantMessageId
            else -> null
        }
        val historyCommand = remote.publicationId?.substringBefore(":history:")?.takeIf { remote.publicationId.contains(":history:") }
        val adoptedHistory = historyCommand?.let { dao.submission(it) }?.let { it.chatId == conversation.localChatId && it.userMessageId?.let { id -> dao.localMessage(id)?.chatId == conversation.localChatId } == true } == true
        val localId = (previous?.localMessageId ?: originalId)?.takeIf { dao.localMessage(it)?.chatId == conversation.localChatId }
            ?: if (conversation.localChatId != null && remote.role in setOf("user", "assistant") && remote.kind !in setOf("progress", "status", "reasoning", "tool", "tool_request") && !adoptedHistory) {
                val linked = if (remote.role == "assistant") dao.userForTurn(conversation.key, remote.turnId)?.localMessageId ?: submission?.userMessageId ?: 0 else 0
                dao.insertLocalMessage(
                    MessageV2(
                        chatId = conversation.localChatId,
                        content = remote.content,
                        platformType = if (remote.role == "user") null else conversation.executionProfileUid,
                        linkedMessageId = linked,
                        createdAt = remote.createdAt.toLong().coerceAtLeast(0)
                    )
                ).toInt()
            } else {
                null
            }
        dao.saveMessage(GatewayMessageRecord(conversation.key, remote.messageId, remote.turnId, remote.role, remote.content, remote.status, remote.revision, remote.publicationId, remote.createdAt, localId))
    }

    private suspend fun reproject(conversation: GatewayConversationRecord) {
        for (remote in dao.messages(conversation.key)) {
            if (remote.role != "assistant") continue
            val message = remote.localMessageId?.let { dao.localMessage(it) } ?: continue
            if (dao.liveMessageOwners(message.id) > 0) continue
            val submission = dao.submissionForTurn(conversation.key, remote.turnId)
            if (submission?.runId != null) {
                // Ordinary results pass through GatewayOutcomeRecovery, including its GitHub evidence policy.
                if (dao.binding(submission.commandId) == null) continue
                val owner = database.agentRunDao().getById(submission.runId) ?: continue
                if (message.currentRunId != owner.runId || message.ownershipFence != owner.ownershipFence || owner.status != "WAITING_REMOTE_JOB" || owner.terminalError == "BACKUP_RESTORED") continue
            }
            val projected = message.copy(content = remote.content, timeline = message.timeline.filter { it.type != AssistantTimelineItemType.TEXT } + listOfNotNull(remote.content.takeIf(String::isNotBlank)?.let { AssistantTimelineItem(AssistantTimelineItemType.TEXT, content = it) }))
            if (dao.projectMessage(message.id, projected.content, projected.timeline, message.ownershipFence, message.currentRunId) == 1) {
                val terminal = when (remote.status.lowercase()) {
                    "completed", "complete", "succeeded", "final" -> "COMPLETED"
                    "cancelled", "canceled" -> "CANCELED"
                    "failed", "error" -> "FAILED"
                    else -> null
                }
                if (terminal != null && submission?.runId != null) {
                    val at = System.currentTimeMillis() / 1000
                    val changed = dao.finishRemoteRun(submission.runId, terminal, at, message.ownershipFence)
                    if (changed == 1) {
                        val run = requireNotNull(database.agentRunDao().getById(submission.runId))
                        database.agentPersistenceDao().publishReply(run, projected, terminal, at)
                        dao.touchChat(message.chatId, at)
                    }
                }
            }
        }
    }

    private suspend fun attachment(local: GatewayConversationRecord): GatewayConversationAttachment = GatewayConversationAttachment(
        requireNotNull(local.localChatId),
        local.executionProfileUid,
        settings.getFeatureSettings().conversationGitHub[requireNotNull(local.localChatId)],
        local.key,
        historyComplete = dao.conversation(local.key)?.let { !it.syncHasMore && it.historyComplete } == true && dao.incompleteMessageParts(local.key) == 0
    )

    private fun requireSameIdentity(snapshot: GatewayConversationSnapshot, gatewayId: String, principalId: String, conversationId: String) {
        check(snapshot.gatewayId == gatewayId && snapshot.principalId == principalId && snapshot.conversationId == conversationId) { "The gateway or account changed. The original saved conversation was retained." }
    }
}
