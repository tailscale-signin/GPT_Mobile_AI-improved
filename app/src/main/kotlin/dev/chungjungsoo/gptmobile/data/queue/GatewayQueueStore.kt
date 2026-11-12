package dev.chungjungsoo.gptmobile.data.queue

import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.agent.AgentRunRequest
import dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import dev.chungjungsoo.gptmobile.data.database.entity.GatewaySubmissionRecord
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnRequest
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnResult
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GatewayCommandRejected
import dev.chungjungsoo.gptmobile.data.github.GatewayCommandRejection
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationSnapshot
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringBinding
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringClient
import dev.chungjungsoo.gptmobile.data.github.GatewayJobClient
import dev.chungjungsoo.gptmobile.data.github.GatewayJobGroup
import dev.chungjungsoo.gptmobile.data.github.GatewayJobRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayJobSummary
import dev.chungjungsoo.gptmobile.data.github.GatewayOrdinaryReservation
import dev.chungjungsoo.gptmobile.data.github.retainGatewayCommandRejection
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAPI
import dev.chungjungsoo.gptmobile.data.network.gateway.matchesRequestedModel
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.research.researchHash
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class GatewayQueuedTurn(
    val promptId: String,
    val accepted: PendingPromptPayload,
    /** Exact accepted history, including receipts; later chat edits cannot change a restart. */
    val history: List<MessageV2>
)

/**
 * The row is a global lease, independent of a screen, process or coroutine mutex. Its
 * creation and transfer of queued input into a run share one Room transaction. Remote
 * admission rows remain separate: a locally cancelled run cannot erase an unknown job.
 */
@Singleton
class GatewayQueueStore @Inject constructor(
    private val database: ChatDatabaseV2,
    private val settings: SettingRepository,
    private val conversations: GatewayConversationRepository,
    private val jobs: GatewayJobRepository,
    private val client: GatewayJobClient,
    private val gateway: GatewayAPI,
    private val engineering: GatewayEngineeringClient
) {
    private val dao get() = database.gatewayJobDao()
    private val json = Json { ignoreUnknownKeys = true }
    internal val active = dao.observeQueuedDispatches()

    internal fun turn(lease: GatewaySubmissionRecord): GatewayQueuedTurn = json.decodeFromString(lease.payload)

    internal suspend fun persist(
        prompt: PendingPrompt,
        history: List<MessageV2>,
        request: PersistAgentTurnRequest
    ): PersistAgentTurnResult? {
        val accepted = prompt.details()
        val selected = requireNotNull(accepted.gateway)
        require(request.chatRoom.conversationMode == ConversationMode.GATEWAY && !request.chatRoom.isTemporary)
        require(request.runs.size == 1 && request.runs.single().let { it.profileUid == selected.profileUid && it.modelSnapshot == selected.model })
        require(accepted.profileUids == listOf(selected.profileUid) && accepted.models[selected.profileUid] == selected.model && accepted.tools.combined == null)
        val encoded = json.encodeToString(GatewayQueuedTurn(prompt.id, accepted, history))
        return database.withTransaction {
            if (dao.queuedDispatches().isNotEmpty() || dao.queuedDeliveries(prompt.chatId).isNotEmpty() || dao.unsettledSubmissions().isNotEmpty() || dao.legacyUnsettledRuns().isNotEmpty() || dao.cachedJobs().any { json.decodeFromString<GatewayJobSummary>(it.snapshot).group != GatewayJobGroup.RECENT }) return@withTransaction null
            val result = database.agentPersistenceDao().persistAgentTurn(request)
            val run = result.runs.single()
            val lease = GatewaySubmissionRecord(
                commandId = "local-queue:${prompt.id}",
                chatId = result.chatRoom.id,
                userMessageId = result.userMessage.id,
                assistantMessageId = run.assistantMessageId,
                runId = run.runId,
                connectionProfileUid = selected.profileUid,
                endpoint = selected.endpoint,
                executionProfileUid = selected.profileUid,
                fingerprint = researchHash(encoded),
                payload = encoded,
                state = "LOCAL_QUEUE_ACTIVE",
                createdAtMillis = System.currentTimeMillis()
            )
            check(dao.insertSubmission(lease) != -1L) { "This Gateway prompt already owns a saved dispatch." }
            result
        }
    }

    /** Every physical attempt inherits this check, including retries initiated outside the queue. */
    internal suspend fun guard(request: AgentRunRequest, inherited: RequestAuthorizationGuard?): RequestAuthorizationGuard? {
        if (dao.localChat(request.chatId)?.conversationMode != ConversationMode.GATEWAY) return inherited
        return RequestAuthorizationGuard("gateway-queue:${request.runId}") {
            inherited?.validate?.invoke()
            val lease = dao.queuedDispatches().singleOrNull { it.runId == request.runId && it.chatId == request.chatId }
            check(lease != null) { "GATEWAY_QUEUE_REQUIRED: Send this message through its saved Gateway queue; another job may still own the gateway." }
            val selected = requireNotNull(turn(lease).accepted.gateway)
            val problem = problem(lease)
            check(problem == null) { "GATEWAY_QUEUE_CONFIGURATION_CHANGED: $problem" }
            check(request.platform.uid == selected.profileUid && request.platform.model == selected.model && request.platform.apiUrl.trimEnd('/') == selected.endpoint && request.platform.compatibleType.name == selected.providerType) {
                "GATEWAY_QUEUE_CONFIGURATION_CHANGED: The queued execution destination changed."
            }
        }
    }

    internal suspend fun problem(lease: GatewaySubmissionRecord): String? {
        if (lease.receipt == "BACKUP_RESTORED" || lease.runId?.let { database.agentRunDao().getById(it)?.terminalError } == "BACKUP_RESTORED") return "A restored Gateway job needs review of its original remote outcome."
        val selected = turn(lease).accepted.gateway ?: return "The saved Gateway selection is unavailable."
        val room = dao.localChat(lease.chatId) ?: return "The original Gateway chat was removed. Its remote outcome still needs reconciliation."
        if (room.conversationMode != ConversationMode.GATEWAY || room.isTemporary || room.activePlatform != listOf(selected.profileUid)) return "Restore the original Gateway chat participant before resuming."
        val profile = settings.fetchPlatformV2s().singleOrNull { it.uid == selected.profileUid } ?: return "The original Gateway profile is unavailable."
        selected.validate(profile)?.let { return it }
        if (settings.getFeatureSettings().conversationGateway[lease.chatId] != selected || dao.selectedModel(lease.chatId, selected.profileUid) != selected.model) return "The Gateway chat selection changed. Restore the original model and profile to resume its saved queue."
        return null
    }

    internal suspend fun resumedRequest(lease: GatewaySubmissionRecord, profile: PlatformV2): AgentRunRequest? = database.withTransaction {
        val runId = requireNotNull(lease.runId)
        val run = database.agentRunDao().getById(runId) ?: return@withTransaction null
        if (run.status !in setOf("QUEUED", "INTERRUPTED") || run.gatewayJobId != null || dao.submissionsForRun(runId).isNotEmpty()) return@withTransaction null
        // A crashed active round may contain client tools. Preserve that checkpoint for
        // explicit logical recovery; only an undispatched saved turn restarts here.
        if (run.status == "INTERRUPTED" && run.startedAt != null) return@withTransaction null
        if (run.status == "INTERRUPTED" && dao.restoreQueuedDispatch(runId, run.ownershipFence) != 1) return@withTransaction null
        val original = turn(lease)
        val user = dao.localMessage(run.userMessageId) ?: return@withTransaction null
        val assistant = dao.localMessage(run.assistantMessageId) ?: return@withTransaction null
        if (assistant.currentRunId != runId || assistant.ownershipFence != run.ownershipFence) return@withTransaction null
        val users = original.history.filter { it.platformType == null } + user
        val rows = users.map { parent -> (original.history + assistant).filter { it.platformType != null && it.linkedMessageId == parent.id } }
        AgentRunRequest(runId, run.chatId, assistant, profile.copy(model = run.modelSnapshot, reasoning = original.accepted.tools.reasoning ?: profile.reasoning), users, rows, original.accepted.tools)
    }

    /** Read-only reconciliation; absence of a command receipt is never permission to resubmit. */
    internal suspend fun remoteSettled(lease: GatewaySubmissionRecord): Boolean = remoteSettled(lease, forceRefresh = false)

    private suspend fun remoteSettled(lease: GatewaySubmissionRecord, forceRefresh: Boolean): Boolean {
        val runId = requireNotNull(lease.runId)
        if (!forceRefresh && (lease.receipt == "BACKUP_RESTORED" || database.agentRunDao().getById(runId)?.terminalError == "BACKUP_RESTORED")) return false
        if (dao.submissionsForRun(runId).any { it.state in setOf("PENDING_ORDINARY", "DISPATCHING_ORDINARY") }) {
            // Preserve the recovered acknowledgement on the original run before
            // terminal proof removes that command from the pending-admission set.
            conversations.reconcileOrdinaryAdmissions(lease.chatId, forceRefresh = forceRefresh)
        }
        val reservations = dao.submissionsForRun(runId)
        if (reservations.isEmpty()) return database.agentRunDao().getById(runId)?.gatewayJobId == null
        val selected = requireNotNull(turn(lease).accepted.gateway)
        for (reservation in reservations) {
            if (reservation.state == "SETTLED" || !forceRefresh && reservation.state == "REJECTED_NOT_DISPATCHED") continue
            if (!forceRefresh && reservation.state == "RESERVED_ORDINARY") {
                if (dao.markOrdinaryNotDispatched(reservation.commandId, runId) != 1) return false
                continue
            }
            val original = queuedAuthority(lease, reservation)
            if (!forceRefresh && verifiedRejection(reservation, original.gatewayId, original.principalId, "generation")) continue
            val route = jobs.originalRoute(reservation.connectionProfileUid, reservation.endpoint)
            val receipt = try {
                (if (forceRefresh) client.command(route.config, reservation.commandId) else client.observeCommand(route.config, reservation.commandId)) ?: return false
            } catch (rejected: GatewayCommandRejected) {
                if (!retainRejection(reservation, rejected.outcome, original.gatewayId, original.principalId, "generation")) return false
                continue
            }
            check(receipt.jobKind == "generation" && receipt.gatewayId == original.gatewayId && receipt.principalId == original.principalId && (receipt.profileId == null || receipt.profileId == selected.profileUid) && (receipt.model == null || receipt.model == selected.model)) {
                "The saved Gateway command identity changed."
            }
            val result = gateway.getJobResult(receipt.jobId, route.config) ?: return false
            if (result.jobId != receipt.jobId || !result.matchesRequestedModel(selected.model) || result.status.lowercase() !in TERMINAL_REMOTE_STATES) return false
            dao.markSubmissionSettled(reservation.commandId, reservation.state)
            markCachedTerminal(receipt.jobId, reservation.connectionProfileUid, reservation.endpoint, result.status)
        }
        // No-dispatch proof for these commands cannot certify an older job whose
        // accepted command was not retained with the run.
        return database.agentRunDao().getById(runId)?.gatewayJobId == null || needsDelivery(lease)
    }

    /** Existing server work participates in the same fence, including before this feature was enabled. */
    internal suspend fun externalWorkBlocker(): String? {
        for (pending in dao.unsettledSubmissions()) {
            val run = pending.runId?.let { database.agentRunDao().getById(it) }
            if (run?.status in setOf("QUEUED", "RUNNING", "WAITING_PROVIDER_LIMIT")) return "Waiting for an existing Gateway request to finish."
            if (pending.state == "RESERVED_ORDINARY") {
                if (pending.runId?.let { dao.markOrdinaryNotDispatched(pending.commandId, it) } != 1) return "An earlier Gateway admission changed while its status was being checked."
                continue
            }
            val binding = dao.binding(pending.commandId)?.let { json.decodeFromString<GatewayEngineeringBinding>(it.binding) }
            if (binding?.job?.terminal == true) {
                dao.markSubmissionSettled(pending.commandId, pending.state)
                continue
            }
            val route = jobs.originalRoute(pending.connectionProfileUid, pending.endpoint)
            val receipt = try {
                client.observeCommand(route.config, pending.commandId)
                    ?: return "An earlier Gateway submission has no confirmed outcome. Open its saved job to reconcile it before new work starts."
            } catch (rejected: GatewayCommandRejected) {
                val original = if (binding == null) ordinaryAuthority(pending) else null
                if (!retainRejection(pending, rejected.outcome, binding?.gatewayId ?: original?.gatewayId, binding?.principalId ?: original?.principalId, if (binding == null) "generation" else "engineering")) {
                    return "An earlier Gateway admission changed while its status was being checked."
                }
                continue
            }
            val terminal = if (binding != null) {
                check(receipt.jobKind == "engineering" && receipt.gatewayId == binding.gatewayId && receipt.principalId == binding.principalId && receipt.profileId == binding.request.profileId && receipt.model == binding.request.model && receipt.repository == binding.request.repository && receipt.base == binding.request.base && dev.chungjungsoo.gptmobile.data.github.gatewayWorkingBranchMatches(binding.request, receipt.branch, receipt.sourceBranch, receipt.sourceSha, receipt.workingBranch)) {
                    "The original engineering job destination changed."
                }
                val job = engineering.get(route.config, receipt.jobId, 0)
                check(job.id == receipt.jobId && job.gatewayId == receipt.gatewayId)
                if (job.terminal) markCachedTerminal(job.id, pending.connectionProfileUid, pending.endpoint, job.state)
                job.terminal
            } else {
                val original = ordinaryAuthority(pending)
                check(receipt.jobKind == "generation" && receipt.gatewayId == original.gatewayId && receipt.principalId == original.principalId && (receipt.profileId == null || receipt.profileId == pending.executionProfileUid) && (receipt.model == null || receipt.model == original.model)) { "The original Gateway command identity changed." }
                val result = gateway.getJobResult(receipt.jobId, route.config)
                val finished = result != null && result.jobId == receipt.jobId && result.matchesRequestedModel(original.model) && result.status.lowercase() in TERMINAL_REMOTE_STATES
                if (finished) markCachedTerminal(receipt.jobId, pending.connectionProfileUid, pending.endpoint, requireNotNull(result).status)
                finished
            }
            if (!terminal) return "Waiting for an existing Gateway job or its required approval to finish."
            dao.markSubmissionSettled(pending.commandId, pending.state)
        }
        for (run in dao.legacyUnsettledRuns()) {
            if (run.status in setOf("QUEUED", "RUNNING", "WAITING_PROVIDER_LIMIT")) return "Waiting for the original Gateway generation to finish."
            val endpoint = run.gatewayBaseUrl ?: return "The original Gateway destination is unavailable. Restore that job's profile before starting new work."
            val route = jobs.originalRoute(run.profileUid, endpoint)
            val result = gateway.getJobResult(requireNotNull(run.gatewayJobId), route.config)
            if (result == null || result.jobId != run.gatewayJobId || !result.matchesRequestedModel(run.modelSnapshot) || result.status.lowercase() !in TERMINAL_REMOTE_STATES) return "Checking the original saved Gateway generation before new work starts."
            dao.insertSubmission(
                GatewaySubmissionRecord(
                    "local-terminal:${researchHash("${run.runId}|${run.gatewayJobId}")}", run.chatId, run.userMessageId, run.assistantMessageId, run.runId,
                    run.profileUid, endpoint.trimEnd('/'), run.profileUid, researchHash("${run.gatewayJobId}|${run.modelSnapshot}|${result.status}"), requireNotNull(run.gatewayJobId),
                    "LOCAL_REMOTE_TERMINAL", receipt = "VERIFIED_REMOTE_TERMINAL", createdAtMillis = System.currentTimeMillis()
                )
            )
            markCachedTerminal(requireNotNull(run.gatewayJobId), run.profileUid, endpoint.trimEnd('/'), result.status)
        }
        // Jobs imported from remote history may have no local submission or run.
        for (record in dao.cachedJobs()) {
            val saved = json.decodeFromString<GatewayJobSummary>(record.snapshot)
            if (saved.group == GatewayJobGroup.RECENT) continue
            val route = jobs.originalRoute(record.connectionProfileUid, record.endpoint)
            val identity = client.identity(route.config)
            check(identity.gatewayId == record.gatewayId && identity.principalId == record.principalId) { "The original Gateway account changed. Check its saved jobs before starting new work." }
            val terminalState = when (record.kind) {
                "engineering" -> engineering.get(route.config, record.remoteJobId, 0).takeIf { it.id == record.remoteJobId && it.gatewayId == record.gatewayId && it.terminal }?.state
                "generation", "ordinary" -> gateway.getJobResult(record.remoteJobId, route.config)?.takeIf { it.jobId == record.remoteJobId && (saved.model.isBlank() || it.matchesRequestedModel(saved.model)) && it.status.lowercase() in TERMINAL_REMOTE_STATES }?.status
                else -> null
            } ?: return "Waiting for a saved Gateway job or its required approval to finish."
            dao.saveJobs(listOf(record.copy(snapshot = json.encodeToString(saved.copy(state = terminalState, schedulingState = null)))))
        }
        return null
    }

    private suspend fun ordinaryAuthority(record: GatewaySubmissionRecord): GatewayOrdinaryReservation =
        runCatching { json.decodeFromString<GatewayOrdinaryReservation>(record.payload) }.getOrNull()
            ?: record.conversationKey?.let { key -> dao.conversation(key) }?.let { conversation ->
                val snapshot = json.decodeFromString<GatewayConversationSnapshot>(conversation.snapshot)
                val model = record.runId?.let { database.agentRunDao().getById(it)?.modelSnapshot } ?: snapshot.model
                require(model.isNotBlank()) { "The original Gateway model is unavailable." }
                GatewayOrdinaryReservation("ordinary", model, conversation.gatewayId, conversation.principalId)
            } ?: error("The original Gateway command authority is unavailable. Open the saved job before starting new work.")

    private suspend fun queuedAuthority(lease: GatewaySubmissionRecord, record: GatewaySubmissionRecord): GatewayOrdinaryReservation {
        val selected = requireNotNull(turn(lease).accepted.gateway)
        val original = ordinaryAuthority(record)
        check(original.reservationKind == "ordinary" && original.model == selected.model && record.connectionProfileUid == selected.profileUid && record.executionProfileUid == selected.profileUid && record.endpoint == selected.endpoint) {
            "The saved Gateway command destination changed."
        }
        return original
    }

    private fun requireRejectionAuthority(record: GatewaySubmissionRecord, outcome: GatewayCommandRejection, gatewayId: String?, principalId: String?, kind: String) {
        outcome.verify(record.commandId)
        check(outcome.gatewayId == gatewayId && outcome.principalId == principalId && outcome.jobKind == kind) { "The saved Gateway command rejection belongs to another account or execution destination." }
    }

    private fun verifiedRejection(record: GatewaySubmissionRecord, gatewayId: String?, principalId: String?, kind: String): Boolean = runCatching {
        val outcome = json.decodeFromString<GatewayCommandRejection>(requireNotNull(record.receipt))
        require(record.state == "REJECTED:${outcome.httpStatus}")
        requireRejectionAuthority(record, outcome, gatewayId, principalId, kind)
    }.isSuccess

    private suspend fun retainRejection(record: GatewaySubmissionRecord, outcome: GatewayCommandRejection, gatewayId: String?, principalId: String?, kind: String): Boolean = database.withTransaction {
        requireRejectionAuthority(record, outcome, gatewayId, principalId, kind)
        val current = dao.submission(record.commandId) ?: return@withTransaction false
        if (current.copy(state = record.state, receipt = record.receipt) != record) return@withTransaction false
        if (current.state == "REJECTED_NOT_DISPATCHED") {
            // A restored local marker may predate a dispatch on the original device.
            // Only this fresh, verified remote rejection can replace it with proof.
            dao.saveSubmission(current.copy(state = "REJECTED:${outcome.httpStatus}", receipt = json.encodeToString(outcome)))
        } else {
            retainGatewayCommandRejection(database, current, outcome)
        }
        val retained = dao.submission(record.commandId) ?: return@withTransaction false
        verifiedRejection(retained, gatewayId, principalId, kind)
    }

    private suspend fun notDispatched(lease: GatewaySubmissionRecord, record: GatewaySubmissionRecord): Boolean {
        if (record.state in setOf("RESERVED_ORDINARY", "REJECTED_NOT_DISPATCHED")) return true
        if (!record.state.startsWith("REJECTED:")) return false
        val original = queuedAuthority(lease, record)
        return verifiedRejection(record, original.gatewayId, original.principalId, "generation")
    }

    /** Reconciliation may replace an unknown command with a rejection; read its current proof. */
    internal suspend fun needsDelivery(lease: GatewaySubmissionRecord): Boolean = dao.submissionsForRun(requireNotNull(lease.runId)).any { !notDispatched(lease, it) }

    private suspend fun markCachedTerminal(jobId: String, profileUid: String, endpoint: String, state: String) {
        val records = dao.cachedJobs().filter { it.remoteJobId == jobId && it.connectionProfileUid == profileUid && it.endpoint == endpoint }
        if (records.isNotEmpty()) {
            dao.saveJobs(
                records.map { record ->
                    val saved = json.decodeFromString<GatewayJobSummary>(record.snapshot)
                    record.copy(snapshot = json.encodeToString(saved.copy(state = state, schedulingState = null)))
                }
            )
        }
    }

    /** Explicit read-only review can release imported work only with an original remote identity. */
    internal suspend fun reviewRestored(lease: GatewaySubmissionRecord): Boolean {
        val runId = requireNotNull(lease.runId)
        if (dao.submissionsForRun(runId).isEmpty()) return false
        if (!remoteSettled(lease, forceRefresh = true)) return false
        val commands = dao.submissionsForRun(runId)
        val last = commands.lastOrNull { !notDispatched(lease, it) }
        if (last == null) {
            if (commands.none { it.state.startsWith("REJECTED:") }) return false
            // Restored no-dispatch markers alone cannot unlock a queue. Every retained
            // remote rejection above was checked again with an explicit, live GET.
            return database.withTransaction {
                if (dao.submission(lease.commandId) != lease || dao.submissionsForRun(runId) != commands || database.agentRunDao().getById(runId)?.gatewayJobId != null) return@withTransaction false
                if (lease.state == "LOCAL_QUEUE_DELIVERY_PENDING") {
                    dao.reviewQueuedDelivery(lease.commandId) == 1 && dao.finishQueuedDelivery(lease.commandId) == 1
                } else {
                    release(lease, "RESTORED_REMOTE_TERMINAL_VERIFIED")
                }
            }
        }
        val original = ordinaryAuthority(last)
        val route = jobs.originalRoute(last.connectionProfileUid, last.endpoint)
        val receipt = client.command(route.config, last.commandId) ?: return false
        check(receipt.gatewayId == original.gatewayId && receipt.principalId == original.principalId && receipt.jobKind == "generation" && (receipt.profileId == null || receipt.profileId == last.executionProfileUid) && (receipt.model == null || receipt.model == original.model))
        val result = gateway.getJobResult(receipt.jobId, route.config) ?: return false
        if (result.jobId != receipt.jobId || !result.matchesRequestedModel(original.model) || result.status.lowercase() !in TERMINAL_REMOTE_STATES) return false
        return database.withTransaction {
            val run = database.agentRunDao().getById(runId)
            if (run != null && !deliveryReady(lease.copy(receipt = "RESTORED_REMOTE_TERMINAL_VERIFIED"))) {
                if (dao.restoreReviewedQueuedDelivery(runId, run.ownershipFence, receipt.jobId, last.endpoint) != 1) return@withTransaction false
            }
            if (lease.state == "LOCAL_QUEUE_DELIVERY_PENDING") {
                dao.reviewQueuedDelivery(lease.commandId) == 1
            } else {
                release(lease, "RESTORED_REMOTE_TERMINAL_VERIFIED", pendingDelivery = run != null)
            }
        }
    }

    internal suspend fun cancelUnsubmitted(chatId: Int): Boolean = database.withTransaction {
        val lease = dao.queuedDispatches().singleOrNull { it.chatId == chatId } ?: return@withTransaction false
        val run = lease.runId?.let { database.agentRunDao().getById(it) } ?: return@withTransaction false
        if (lease.receipt == "BACKUP_RESTORED" || run.terminalError == "BACKUP_RESTORED" || run.gatewayJobId != null || run.status !in setOf("QUEUED", "INTERRUPTED")) return@withTransaction false
        val commands = dao.submissionsForRun(run.runId)
        if (commands.any { !notDispatched(lease, it) }) return@withTransaction false
        val message = dao.localMessage(run.assistantMessageId) ?: return@withTransaction false
        if (message.currentRunId != run.runId || message.ownershipFence != run.ownershipFence) return@withTransaction false
        commands.filter { it.state == "RESERVED_ORDINARY" }.forEach { dao.markOrdinaryNotDispatched(it.commandId, run.runId) }
        database.agentPersistenceDao().updateRunStatus(run.runId, "CANCELED", run.startedAt, System.currentTimeMillis() / 1000, "GATEWAY_QUEUED_TURN_CANCELLED_BEFORE_DISPATCH")
        dao.releaseQueuedDispatch(lease.commandId, "USER_CANCELLED_BEFORE_DISPATCH") == 1
    }

    internal suspend fun refresh(lease: GatewaySubmissionRecord) {
        if (dao.localChat(lease.chatId) != null) conversations.refreshChat(lease.chatId)
    }

    internal suspend fun release(lease: GatewaySubmissionRecord, proof: String, pendingDelivery: Boolean = false): Boolean = database.withTransaction {
        val current = dao.submission(lease.commandId)
        if (current != lease) return@withTransaction false
        dao.releaseQueuedDispatch(lease.commandId, proof, if (pendingDelivery) "LOCAL_QUEUE_DELIVERY_PENDING" else "LOCAL_QUEUE_RELEASED") == 1
    }

    internal suspend fun deliveryReady(lease: GatewaySubmissionRecord): Boolean {
        if (lease.receipt == "BACKUP_RESTORED") return false
        if (dao.localChat(lease.chatId) == null) return true
        val run = lease.runId?.let { database.agentRunDao().getById(it) } ?: return false
        if (run.terminalError == "BACKUP_RESTORED") return false
        val commands = dao.submissionsForRun(run.runId)
        if (run.gatewayJobId == null && commands.any { it.state.startsWith("REJECTED:") } && commands.all { notDispatched(lease, it) }) return true
        if (run.status in setOf("QUEUED", "RUNNING", "WAITING_REMOTE_JOB", "WAITING_PROVIDER_LIMIT", "INTERRUPTED", "BLOCKED")) return false
        if (run.status == "CANCELED") return true
        val error = run.terminalError.orEmpty()
        val reconciled = error.contains("GATEWAY_SAVED_RESULT_") || error.contains("GATEWAY_RECOVERED_RESULT_UNVERIFIED")
        if (!reconciled && dev.chungjungsoo.gptmobile.data.agent.GatewayOutcomeRecovery.requiresReconciliation(error)) return false
        return run.status == "FAILED" && reconciled || dao.localMessage(run.assistantMessageId)?.content?.isNotBlank() == true
    }

    internal suspend fun finishDelivery(lease: GatewaySubmissionRecord): Boolean = dao.finishQueuedDelivery(lease.commandId) == 1

    internal companion object {
        val TERMINAL_REMOTE_STATES = setOf("completed", "failed", "cancelled", "canceled", "interrupted", "expired")
    }
}
