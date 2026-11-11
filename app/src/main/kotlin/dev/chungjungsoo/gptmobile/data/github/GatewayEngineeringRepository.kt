package dev.chungjungsoo.gptmobile.data.github

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayConversationRecord
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

@Singleton
class GatewayEngineeringRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingRepository,
    private val client: GatewayEngineeringClient,
    private val notifications: GitHubDecisionNotifications,
    private val store: GatewayEngineeringStore,
    private val conversations: GatewayConversationRepository,
    private val history: GatewayJobClient
) {
    private val synchronization = Mutex()
    private val scheduling = Mutex()
    private val submissionLock = Mutex()
    private val lastPoll = mutableMapOf<String, Long>()

    suspend fun bindings(chatId: Int? = null): List<GatewayEngineeringBinding> = store.bindings(chatId)

    suspend fun ownsConversation(chatId: Int): Boolean {
        store.migrateLegacy()
        return conversations.ownsConversation(chatId)
    }

    suspend fun conversationWriteBlocker(chatId: Int): String? {
        store.migrateLegacy()
        return conversations.conversationWriteBlocker(chatId)
    }

    suspend fun options(): GatewayEngineeringSettings = settings.getFeatureSettings().githubEngineeringSettings

    /** Composer guard: checking uncertainty never creates or resumes gateway execution. */
    suspend fun hasUnconfirmedAdmission(chatId: Int, profileUid: String): Boolean = store.hasMigratedAdmission(chatId, profileUid) || bindings(chatId).any { it.request.profileId == profileUid && it.job == null && store.admissionState(it.request.requestId)?.startsWith("REJECTED:") != true }

    suspend fun requireNoUnconfirmedAdmission(chatId: Int, profileUid: String, excludingCommandId: String? = null) {
        for (pending in bindings(chatId).filter { it.request.profileId == profileUid && it.request.requestId != excludingCommandId && it.job == null }) {
            if (store.admissionState(pending.request.requestId)?.startsWith("REJECTED:") == true) continue
            val retained = observe(pending.request.requestId)
            check(retained.job != null && !retained.job.conversationId.isNullOrBlank()) { "The previous saved gateway submission is still unconfirmed. Reconnect to its original command before sending another turn." }
        }
        check(!store.hasMigratedAdmission(chatId, profileUid)) { "A saved gateway submission from the earlier app still needs receipt reconciliation. Reconnect to its original job before sending another turn." }
    }

    suspend fun evidence(requestId: String, receiptId: String): String {
        val binding = bindings().single { it.request.requestId == requestId }
        val config = config(binding)
        check(binding.principalId == client.principal(config, requireReady = false)) { "The gateway account changed; evidence is unavailable." }
        return client.evidence(config, requireNotNull(binding.job).id, receiptId)
    }

    private suspend fun config(binding: GatewayEngineeringBinding): ProviderRequestConfig {
        val profile = settings.fetchPlatformV2s().singleOrNull { it.uid == binding.profileUid && it.enabled }
            ?: error("The saved engineering AI profile is unavailable. The gateway job is retained.")
        check(GitHubAutonomousEngineeringPolicy.supportsSavedJobs(profile) && profile.apiUrl.trimEnd('/') == binding.endpoint) { "The saved job requires its original local Llama or Ollama engineering connection. Restore that profile to reconnect." }
        return ProviderRequestConfig(profile.apiUrl, profile.token, profileUid = profile.uid)
    }

    /** False means an explicitly unsupported protocol, never temporary capacity or transport failure. */
    suspend fun supportsDurableChat(profileUid: String): Boolean {
        val gateway = settings.engineeringGateway(profileUid)
        val capability = try {
            client.capabilities(gateway.engineeringConfig())
        } catch (_: UnsupportedEngineeringGateway) {
            return false
        }
        return capability.supportsDurableChat
    }

    suspend fun start(chatId: Int, context: GitHubConversationContext, base: String, branch: String, objective: String, options: GatewayEngineeringSettings) {
        submit(chatId, requireNotNull(context.mainProfileUid), "${context.owner}/${context.repo}", base, branch, objective, UUID.randomUUID().toString(), null, null, null, "implement", null, options, sourceBranch = context.ref, sourceSha = context.commitSha, originalContext = context)
    }

    /** Adopts an already persisted Chat turn; reconnect always retains these exact message IDs. */
    suspend fun submitTurn(
        chatId: Int,
        context: GitHubConversationContext,
        base: String,
        branch: String,
        objective: String,
        commandId: String,
        userMessageId: Int,
        assistantMessageId: Int,
        runId: String,
        taskScope: String,
        selectedModel: String? = null,
        options: GatewayEngineeringSettings? = null,
        draftRepair: GitHubDraftRepair? = null,
        admissionAllowed: () -> Boolean = { true }
    ): GatewayEngineeringBinding {
        val profileUid = requireNotNull(context.mainProfileUid)
        val model = selectedModel ?: settings.fetchPlatformV2s().single { it.uid == profileUid }.model
        val bound = conversations.boundSnapshot(chatId, profileUid, model)
        if (draftRepair != null) {
            draftRepair.requireSource(context)
            check(bound == null && base == draftRepair.baseBranch && branch == draftRepair.branch && taskScope == "implement") { "A draft repair needs a separate saved conversation and its verified branches." }
        } else if (bound != null) {
            val snapshot = bound.second
            check(snapshot.repository == "${context.owner}/${context.repo}" && (snapshot.sourceBranch ?: snapshot.branch) == branch && snapshot.base == base) { "This saved conversation retains its original repository and branches. Start a separate conversation to change them." }
        }
        return submit(chatId, profileUid, "${context.owner}/${context.repo}", base, bound?.second?.workingBranch ?: bound?.second?.branch ?: branch, objective, commandId, userMessageId, assistantMessageId, runId, taskScope, model, options ?: this.options(), bound?.let { it.first.copy(revision = it.second.revision) }, bound?.second?.sourceBranch ?: context.ref, bound?.second?.sourceSha ?: context.commitSha, admissionAllowed, context, draftRepair)
    }

    /** Continue a server conversation on a new device without inventing a phone GitHub workspace. */
    suspend fun submitBoundTurn(
        chatId: Int,
        profileUid: String,
        selectedModel: String,
        objective: String,
        commandId: String,
        userMessageId: Int,
        assistantMessageId: Int,
        runId: String,
        taskScope: String,
        admissionAllowed: () -> Boolean = { true }
    ): GatewayEngineeringBinding? {
        val (local, snapshot) = conversations.boundSnapshot(chatId, profileUid, selectedModel) ?: return null
        val repository = snapshot.repository ?: return null
        val base = requireNotNull(snapshot.base) { "This saved repository conversation has no base branch. Its transcript remains available." }
        val branch = requireNotNull(snapshot.workingBranch ?: snapshot.branch) { "This saved repository conversation has no working branch. Its transcript remains available." }
        return submit(chatId, profileUid, repository, base, branch, objective, commandId, userMessageId, assistantMessageId, runId, taskScope, selectedModel, options(), local.copy(revision = snapshot.revision), snapshot.sourceBranch, snapshot.sourceSha, admissionAllowed, bindings(chatId).lastOrNull { it.request.profileId == profileUid }?.originalContext)
    }

    private suspend fun submit(
        chatId: Int,
        profileUid: String,
        repository: String,
        base: String,
        branch: String,
        objective: String,
        commandId: String,
        userMessageId: Int?,
        assistantMessageId: Int?,
        runId: String?,
        taskScope: String,
        selectedModel: String?,
        options: GatewayEngineeringSettings,
        savedBinding: GatewayConversationRecord? = null,
        sourceBranch: String? = null,
        sourceSha: String? = null,
        admissionAllowed: () -> Boolean = { true },
        originalContext: GitHubConversationContext? = null,
        draftRepair: GitHubDraftRepair? = null
    ): GatewayEngineeringBinding = submissionLock.withLock {
        require(objective.isNotBlank() && objective.length <= 16000 && taskScope in setOf("read", "plan", "implement"))
        requireGatewayCommitPermission(objective, taskScope)
        require(taskScope != "implement" || branch != base || sourceBranch == branch && sourceSha?.matches(Regex("(?:[a-fA-F0-9]{40}|[a-fA-F0-9]{64})")) == true) { "Implementing from the base branch requires its pinned source commit so the gateway can create a separate owned working branch." }
        require(commandId.isNotBlank() && commandId.length <= 128)
        bindings().singleOrNull { it.request.requestId == commandId }?.let { existing ->
            check(existing.request.draftRepair == draftRepair) { "This command belongs to a different repair target." }
            check(existing.chatId == chatId && existing.request.objective == objective && existing.request.taskScope == taskScope && existing.request.repository == repository && existing.request.base == base && existing.request.branch == branch && existing.localUserMessageId == userMessageId && existing.localAssistantMessageId == assistantMessageId && existing.localRunId == runId && (selectedModel == null || existing.request.model == selectedModel)) { "This command already belongs to a different saved turn." }
            return@withLock reconnect(commandId, admissionAllowed)
        }
        requireNoUnconfirmedAdmission(chatId, profileUid, commandId)
        if (!admissionAllowed()) throw GatewayEngineeringRequestRejected(403, "Remote tool permission changed before gateway admission. The draft was not submitted.")
        val storedProfile = settings.fetchPlatformV2s().single { it.uid == profileUid && it.enabled }
        val featuresAtAdmission = settings.getFeatureSettings()
        val profile = storedProfile.copy(model = selectedModel ?: storedProfile.model, reasoning = featuresAtAdmission.conversationReasoning[chatId] ?: storedProfile.reasoning)
        require(profile.model.isNotBlank())
        require(GitHubAutonomousEngineeringPolicy.supportsSavedJobs(profile)) { "Autonomous jobs require a local Llama or Ollama engineering profile. Remote profiles cannot start them." }
        val preferences = settings.getFeatureSettings().githubProfileOverrides[profile.uid] ?: GitHubChatProfileOverride()
        val gateway = if (savedBinding == null) {
            settings.engineeringGateway(profile.uid)
        } else {
            settings.fetchPlatformV2s().singleOrNull { it.uid == savedBinding.connectionProfileUid && it.enabled && it.apiUrl.trimEnd('/') == savedBinding.endpoint }
                ?: error("Restore the original gateway connection before replying to this saved conversation.")
        }
        val capability = client.capabilities(gateway.engineeringConfig())
        if (runId != null) check(capability.supportsDurableChat) { "This gateway does not support durable chat admission. Update it before sending this saved turn." }
        capability.requireJobReadiness()
        val budgetSelection = options.resolveBudget(capability, preferences.engineeringMode, objective, featuresAtAdmission.tokenBudget.totalRunTokens)
        val principal = requireNotNull(capability.principalId)
        val frozenConfig = gateway.engineeringConfig()
        val identity = if (capability.conversationSync) history.identity(frozenConfig) else null
        check(identity == null || identity.principalId == principal) { "The authenticated gateway account changed before binding admission." }
        if (savedBinding != null) check(savedBinding.principalId == principal && savedBinding.gatewayId == identity?.gatewayId) { "The original gateway or account changed. The saved conversation was retained." }
        val connection = profile.providerConnectionUid?.let { settings.getProviderConnection(it) }
        val originalExecution = savedBinding?.snapshot?.let { Json { ignoreUnknownKeys = true }.decodeFromString<GatewayConversationSnapshot>(it).executionBinding }
        val privacyScope = originalExecution?.get("privacy_scope")?.jsonPrimitive?.content ?: "$principal/chat/$chatId/repository/$repository"
        val selection = executionSelection(profile, connection, privacyScope)
        check(settings.fetchPlatformV2s().singleOrNull { it.uid == gateway.uid && it.enabled }?.engineeringConfig() == frozenConfig) { "The gateway configuration changed before binding admission." }
        val admission = client.prepareBinding(identity?.let { frozenConfig.withGatewayIdentity(it) } ?: frozenConfig, selection)
        originalExecution?.let { GatewayExecutionAdmission(it, admission.capabilities).verify(selection) }
        val current = settings.fetchPlatformV2s().singleOrNull { it.uid == profile.uid && it.enabled }
            ?: error("The selected profile was disabled during admission.")
        check(executionSelection(current.copy(model = profile.model, reasoning = profile.reasoning), current.providerConnectionUid?.let { settings.getProviderConnection(it) }, selection.privacyScope) == selection) { "The selected profile changed during admission. Review it before starting." }
        val currentGateway = if (savedBinding == null) settings.engineeringGateway(profile.uid) else settings.fetchPlatformV2s().singleOrNull { it.uid == gateway.uid && it.enabled }
        check(currentGateway?.let { it.uid == gateway.uid && it.apiUrl == gateway.apiUrl } == true) { "The engineering gateway changed during admission." }
        val remote = savedBinding ?: if (capability.supportsDurableChat) conversations.savedConversation(chatId, gateway.uid, gateway.apiUrl.trimEnd('/'), principal, profile.uid) else null
        val binding = GatewayEngineeringBinding(
            gateway.uid,
            gateway.apiUrl.trimEnd('/'),
            GatewayEngineeringRequest(
                repository = repository, branch = branch, base = base, objective = objective,
                model = profile.model, durationHours = budgetSelection.effectiveDurationHours, repairAttempts = options.repairAttempts,
                chatId = chatId, requestId = commandId, acceptance = options.acceptance, profileId = profile.uid,
                memoryMode = preferences.engineeringMemory, executionBindingId = admission.bindingId, taskScope = taskScope,
                clientConversationId = if (capability.supportsDurableChat) conversations.clientConversationId(chatId) else null, conversationId = remote?.conversationId,
                expectedRevision = remote?.revision?.takeIf { it >= 0 }, sourceBranch = sourceBranch, sourceSha = sourceSha,
                executionBudget = budgetSelection.effectiveBudget, draftRepair = draftRepair
            ).let { request ->
                if (capability.admissionPreflightProtocol == 1) {
                    val preflight = client.preflight(identity?.let { frozenConfig.withGatewayIdentity(it) } ?: frozenConfig, request)
                    request.copy(acceptance = preflight.acceptance)
                } else {
                    check(taskScope != "implement" || request.acceptance.isNotEmpty()) { "Update the gateway to version 16 or configure explicit acceptance requirements before implementation. No job was submitted." }
                    request
                }
            },
            principalId = principal,
            executionSelection = selection,
            executionAdmission = admission,
            localUserMessageId = userMessageId,
            localAssistantMessageId = assistantMessageId,
            localRunId = runId,
            gatewayId = identity?.gatewayId,
            originalContext = originalContext,
            budgetSelection = budgetSelection
        )
        store.add(binding)
        settings.mutateFeatureSettings { it.copy(githubEngineeringSettings = options.copy(acceptance = emptyList())) }
        schedule()
        reconnect(commandId, admissionAllowed)
    }

    suspend fun schedule() = scheduling.withLock {
        // A missing/cancelled observer is not evidence that server execution
        // ended. Conversely, cancelled jobs do not justify recreating it.
        if (bindings().none { store.admissionState(it.request.requestId)?.startsWith("REJECTED:") != true && (it.job == null || it.job.terminal == false || it.syncedRevision < it.job.revision || conversations.needsEngineeringRefresh(it)) }) return@withLock
        val manager = WorkManager.getInstance(context)
        val current = manager.getWorkInfosForUniqueWorkFlow(OBSERVER_WORK_NAME).first()
        if (current.any { !it.state.isFinished }) return@withLock
        manager.enqueueUniquePeriodicWork(
            OBSERVER_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<GatewayEngineeringSyncWorker>(15, TimeUnit.MINUTES)
                .setInputData(workDataOf(OBSERVER_GENERATION_KEY to UUID.randomUUID().toString()))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        )
    }

    suspend fun reconnect(requestId: String, admissionAllowed: () -> Boolean = { true }): GatewayEngineeringBinding = poll(requestId, allowCreate = true, maxPages = 4, admissionAllowed = admissionAllowed).also { schedule() }

    /** Safe for foreground/background observers: never submits or resumes inference. */
    suspend fun observe(requestId: String): GatewayEngineeringBinding = poll(requestId, allowCreate = false, maxPages = 2)

    private suspend fun poll(requestId: String, allowCreate: Boolean, maxPages: Int, admissionAllowed: () -> Boolean = { true }): GatewayEngineeringBinding = synchronization.withLock {
        var binding = bindings().single { it.request.requestId == requestId }
        val admissionState = store.admissionState(requestId)
        if (binding.job == null && admissionState?.startsWith("REJECTED:") == true) {
            if (allowCreate) throw GatewayEngineeringRequestRejected(admissionState.substringAfter(':').toIntOrNull() ?: 400, "The gateway rejected this saved command. Refresh the conversation and review a new command before submitting.")
            return@withLock binding
        }
        if (binding.job?.terminal == true && binding.syncedRevision >= requireNotNull(binding.job).revision) {
            mirror(binding)
            return@withLock binding
        }
        val config = config(binding)
        if (System.nanoTime() / 1_000_000 - (lastPoll[requestId] ?: Long.MIN_VALUE / 2) < 2000 && !allowCreate) return@withLock binding
        check(binding.principalId == client.principal(config, requireReady = false)) { "The gateway account changed. Restore the original connection before reconnecting to this job." }
        if (binding.job == null) {
            val capability = client.capabilities(config)
            val identity = if (capability.conversationSync) history.identity(config) else null
            check(identity == null || identity.principalId == binding.principalId && (binding.gatewayId == null || identity.gatewayId == binding.gatewayId)) { "The original gateway installation or account changed." }
            val receipt = if (capability.conversationSync) history.command(config, requestId) else null
            if (receipt != null) {
                check(receipt.commandId == requestId && receipt.jobKind == "engineering" && receipt.principalId == binding.principalId && receipt.gatewayId == identity?.gatewayId && (binding.gatewayId == null || receipt.gatewayId == binding.gatewayId)) { "The saved command receipt belongs to a different gateway or account." }
                val recovered = client.get(config, receipt.jobId, binding.syncedRevision)
                verifyReceipt(binding, recovered)
                check(recovered.conversationId == receipt.conversationId && recovered.commandId == requestId) { "The saved command conversation changed." }
                binding = binding.copy(job = recovered.copy(updates = emptyList()))
                save(binding)
            } else if (!allowCreate) {
                lastPoll[requestId] = System.nanoTime() / 1_000_000
                return@withLock binding
            } else {
                check(admissionState != "MIGRATED_REVIEW") { "The earlier gateway submission is retained for receipt reconciliation. Its payload must not be replayed in a different protocol." }
                val selection = requireNotNull(binding.executionSelection) { "This saved request predates strict execution binding. Select a profile explicitly before starting new work." }
                val accepted = requireNotNull(binding.executionAdmission)
                accepted.verify(selection)
                check(binding.request.executionBindingId == accepted.bindingId)
                client.bindingCapabilities(config, selection, accepted.bindingId)
                val current = settings.fetchPlatformV2s().singleOrNull { it.uid == binding.request.profileId && it.enabled }
                val features = settings.getFeatureSettings()
                if (!admissionAllowed() || current == null || current.disableAllTools || current.disableRemoteTools || features.githubProfileOverrides[current.uid]?.githubTools != true) {
                    val uncertain = store.admissionState(requestId) != "PENDING"
                    if (!uncertain) store.markAdmission(requestId, "REJECTED:403")
                    throw GatewayEngineeringRequestRejected(403, "Remote GitHub tool permission changed before gateway dispatch. The saved command was not sent.", commandOutcomeUnknown = uncertain)
                }
                try {
                    conversations.requireSavedSelection(binding)
                    check(config(binding) == config) { "The gateway credentials changed before submission." }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    val uncertain = store.admissionState(requestId) != "PENDING"
                    if (!uncertain) store.markAdmission(requestId, "REJECTED:403")
                    throw GatewayEngineeringRequestRejected(403, failure.message.orEmpty(), commandOutcomeUnknown = uncertain)
                }
                val verifiedConfig = identity?.let { config.withGatewayIdentity(it) } ?: config
                // Persist dispatch intent; a later rejected retry cannot erase an earlier uncertain attempt.
                val attemptedBefore = store.admissionState(requestId) != "PENDING"
                store.markAdmission(requestId, "DISPATCHING")
                val job = try {
                    if (!admissionAllowed()) throw GatewayEngineeringRequestRejected(403, "Remote tool permission changed before dispatch. The saved command was not sent.")
                    client.create(verifiedConfig, binding.request)
                } catch (rejected: GatewayEngineeringRequestRejected) {
                    store.markAdmission(requestId, if (attemptedBefore) "OUTCOME_UNKNOWN" else "REJECTED:${rejected.httpStatus}")
                    throw GatewayEngineeringRequestRejected(rejected.httpStatus, rejected.message.orEmpty(), commandOutcomeUnknown = attemptedBefore)
                } catch (failure: Exception) {
                    store.markAdmission(requestId, "OUTCOME_UNKNOWN")
                    throw failure
                }
                verifyReceipt(binding, job)
                binding = binding.copy(job = job)
                save(binding)
            }
        }
        var more: Boolean
        var pages = 0
        do {
            val snapshot = client.get(config, requireNotNull(binding.job).id, binding.syncedRevision)
            check(snapshot.id == binding.job?.id) { "Engineering job identity changed." }
            verifyReceipt(binding, snapshot)
            if (binding.request.executionBindingId != null) {
                check(snapshot.executionBindingId == binding.request.executionBindingId && snapshot.executionBinding == binding.executionAdmission?.binding) { "The retained execution identity changed." }
            }
            check(snapshot.revision >= (binding.job?.revision ?: -1)) { "Gateway returned stale engineering state." }
            val cursor = snapshot.nextRevision ?: snapshot.revision
            check(cursor in binding.syncedRevision..snapshot.revision && (!snapshot.hasMoreUpdates || cursor > binding.syncedRevision)) { "Engineering update cursor did not advance." }
            val updates = (binding.updates + snapshot.updates).distinctBy { it.revision }.sortedBy { it.revision }.takeLast(10000)
            binding = binding.copy(job = snapshot.copy(updates = emptyList()), updates = updates, syncedRevision = cursor)
            save(binding)
            more = snapshot.hasMoreUpdates
            pages++
        } while (more && pages < maxPages)
        lastPoll[requestId] = System.nanoTime() / 1_000_000
        val job = requireNotNull(binding.job)
        if (job.revision > binding.notifiedRevision && job.state in setOf("MERGE_READY", "NEEDS_INPUT", "COMPLETED")) {
            notifications.prompt(job.id, binding.chatId, 0, "${job.repository} · ${job.message}")
            binding = binding.copy(notifiedRevision = job.revision)
            save(binding)
        }
        mirror(binding)
        binding
    }

    private fun verifyReceipt(binding: GatewayEngineeringBinding, job: GatewayEngineeringJob) {
        check(job.id.matches(Regex("eng_[a-f0-9]{32}")) && job.repository == binding.request.repository && gatewayWorkingBranchMatches(binding.request, job.branch, job.sourceBranch, job.sourceSha, job.workingBranch) && job.chatId == binding.request.chatId && job.executionBindingId == binding.request.executionBindingId && job.executionBinding == binding.executionAdmission?.binding) { "Engineering receipt does not match the saved request. Its original identity was retained." }
        check((job.sourceBranch == null || binding.request.sourceBranch == null || job.sourceBranch == binding.request.sourceBranch) && (job.sourceSha == null || binding.request.sourceSha == null || job.sourceSha == binding.request.sourceSha) && (job.base == null || job.base == binding.request.base)) { "The gateway changed the original source or base of this conversation." }
    }

    private suspend fun mirror(binding: GatewayEngineeringBinding) {
        try {
            conversations.recordEngineering(binding)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The retained job and transcript cursor allow the next read-only observer to retry.
        }
    }

    suspend fun syncAll(): Boolean {
        var success = true
        val candidates = bindings().filter { store.admissionState(it.request.requestId)?.startsWith("REJECTED:") != true && (it.job == null || it.job.terminal != true || it.syncedRevision < it.job.revision || conversations.needsEngineeringRefresh(it)) }
        for (binding in candidates.sortedBy { lastPoll[it.request.requestId] ?: Long.MIN_VALUE }.take(16)) {
            try {
                observe(binding.request.requestId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                success = false
            }
        }
        return success
    }

    suspend fun refreshChat(chatId: Int) {
        for (binding in bindings(chatId)) observe(binding.request.requestId)
        conversations.refreshChat(chatId)
    }

    /** This method is invoked by explicit UI buttons; it is never registered as an AI tool. */
    suspend fun control(requestId: String, expected: GatewayEngineeringJob, action: String) = synchronization.withLock {
        val binding = bindings().single { it.request.requestId == requestId }
        check(binding.job?.id == expected.id && binding.job?.revision == expected.revision) { "Refresh the job before confirming." }
        val config = config(binding)
        check(binding.principalId == client.principal(config, requireReady = action == "approve")) { "The gateway account changed; the control request was not sent." }
        val identity = if (binding.gatewayId != null || client.capabilities(config).conversationSync) history.identity(config) else null
        check(identity == null || identity.principalId == binding.principalId && (binding.gatewayId == null || binding.gatewayId == identity.gatewayId)) { "The original gateway or account changed; the control request was not sent." }
        check(config(binding) == config) { "The gateway credentials changed before control." }
        val result = client.control(identity?.let { config.withGatewayIdentity(it) } ?: config, expected, action)
        check(result.id == expected.id)
        save(binding.copy(job = result))
        lastPoll.remove(requestId)
        notifications.dismiss(expected.id)
    }

    suspend fun markSeen(requestId: String) = synchronization.withLock {
        val binding = bindings().single { it.request.requestId == requestId }
        save(binding.copy(seenRevision = binding.syncedRevision))
    }

    private suspend fun save(binding: GatewayEngineeringBinding) {
        store.save(binding)
    }

    companion object {
        const val OBSERVER_WORK_NAME = "gateway-engineering-sync"
        const val OBSERVER_GENERATION_KEY = "observer_generation"
    }
}
