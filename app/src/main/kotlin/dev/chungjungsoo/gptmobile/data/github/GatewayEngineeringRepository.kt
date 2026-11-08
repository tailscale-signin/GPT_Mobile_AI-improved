package dev.chungjungsoo.gptmobile.data.github

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
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

@Singleton
class GatewayEngineeringRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingRepository,
    private val client: GatewayEngineeringClient,
    private val notifications: GitHubDecisionNotifications,
    private val store: GatewayWorkStore,
    private val conversations: GatewayConversationRepository
) {
    private val synchronization = Mutex()
    private val scheduling = Mutex()
    private val lastPoll = mutableMapOf<String, Long>()

    suspend fun bindings(chatId: Int? = null): List<GatewayEngineeringBinding> = store.bindings(chatId)

    fun observeConversation(chatId: Int) = conversations.observeConversation(chatId)

    suspend fun ownsRequest(requestId: String): Boolean = conversations.ownsRequest(requestId)

    suspend fun ownsConversation(chatId: Int): Boolean = conversations.ownsConversation(chatId)

    suspend fun conversationWriteBlocker(chatId: Int): String? = conversations.conversationWriteBlocker(chatId)

    suspend fun reconnectConversation(chatId: Int) {
        conversations.reconnectConversation(chatId)
        for (binding in bindings(chatId).filter { it.job != null && it.conversationId == null }) observe(binding.request.requestId)
    }

    suspend fun stopConversation(chatId: Int) = conversations.stopConversation(chatId)

    suspend fun stopRequest(requestId: String) = conversations.stopRequest(requestId)

    suspend fun submitNormalTurn(
        chatId: Int,
        context: GitHubConversationContext,
        profile: dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2,
        requestId: String,
        content: String,
        scope: String,
        userMessageId: Int,
        assistantMessageId: Int,
        base: String = context.ref,
        localRunId: String? = null,
        admissionAllowed: () -> Boolean = { true }
    ): GatewayEngineeringBinding = try {
        conversations.submitNormalTurn(chatId, context, profile, requestId, content, scope, userMessageId, assistantMessageId, base, localRunId, admissionAllowed)
    } finally {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) { schedule() }
    }

    suspend fun options(): GatewayEngineeringSettings = settings.getFeatureSettings().githubEngineeringSettings

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

    suspend fun start(chatId: Int, context: GitHubConversationContext, base: String, branch: String, objective: String, options: GatewayEngineeringSettings) {
        require(objective.isNotBlank() && objective.length <= 16000 && branch != base)
        val profile = settings.fetchPlatformV2s().single { it.uid == context.mainProfileUid && it.enabled }
        require(GitHubAutonomousEngineeringPolicy.supportsSavedJobs(profile)) { "Autonomous jobs require a local Llama or Ollama engineering profile. Remote profiles cannot start them." }
        check(GatewayOwnedTurnPolicy.configured(profile, context, settings.getFeatureSettings())) { "Remote GitHub tools are disabled for this profile." }
        val preferences = settings.getFeatureSettings().githubProfileOverrides[profile.uid] ?: GitHubChatProfileOverride()
        val gateway = settings.engineeringGateway(profile.uid)
        val principal = client.principal(gateway.engineeringConfig())
        val identity = conversations.verifiedIdentity(gateway.engineeringConfig())
        check(identity.principalId == principal) { "The gateway account changed before binding admission." }
        val connection = profile.providerConnectionUid?.let { settings.getProviderConnection(it) }
        val selection = executionSelection(profile, connection, "$principal/chat/$chatId/repository/${context.owner}/${context.repo}")
        check(settings.engineeringGateway(profile.uid).engineeringConfig() == gateway.engineeringConfig()) { "The gateway configuration changed before binding admission." }
        val admission = client.prepareBinding(gateway.engineeringConfig().withGatewayIdentity(identity), selection)
        // Profile edits racing a network preflight apply to a new explicit
        // selection, never silently to the snapshot we are about to persist.
        val current = settings.fetchPlatformV2s().singleOrNull { it.uid == profile.uid && it.enabled }
            ?: error("The selected profile was disabled during admission.")
        check(GatewayOwnedTurnPolicy.configured(current, context, settings.getFeatureSettings())) { "Remote GitHub tool consent changed during admission." }
        check(executionSelection(current, current.providerConnectionUid?.let { settings.getProviderConnection(it) }, selection.privacyScope) == selection) { "The selected profile changed during admission. Review it before starting." }
        check(settings.engineeringGateway(profile.uid).let { it.uid == gateway.uid && it.apiUrl == gateway.apiUrl }) { "The engineering gateway changed during admission." }
        val binding = GatewayEngineeringBinding(
            gateway.uid,
            gateway.apiUrl.trimEnd('/'),
            GatewayEngineeringRequest(
                "${context.owner}/${context.repo}", branch, base, objective, profile.model, options.durationHours, options.repairAttempts, chatId, UUID.randomUUID().toString(), options.acceptance, profile.uid, preferences.engineeringMemory, admission.bindingId,
                profileName = profile.name, sourceBranch = context.ref, sourceSha = context.commitSha
            ),
            principalId = principal,
            executionSelection = selection,
            executionAdmission = admission,
            gatewayId = identity.gatewayId,
            identityScope = identity.identityScope,
            originalContext = context
        )
        settings.mutateFeatureSettings { it.copy(githubEngineeringSettings = options.copy(acceptance = emptyList())) }
        store.prepare(binding)
        schedule()
        conversations.submitPrepared(binding)
    }

    suspend fun schedule() = scheduling.withLock {
        // A missing/cancelled observer is not evidence that server execution
        // ended. Conversely, cancelled jobs do not justify recreating it.
        if (observableBindings().isEmpty()) return@withLock
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

    suspend fun reconnect(requestId: String): GatewayEngineeringBinding = observe(requestId).also { schedule() }

    /** Safe for foreground/background observers: never submits or resumes inference. */
    suspend fun observe(requestId: String): GatewayEngineeringBinding {
        val binding = requireNotNull(store.binding(requestId))
        if (binding.gatewayId != null || binding.conversationId != null || binding.job == null) {
            val observed = conversations.observeRequest(requestId)
            val job = observed.job
            if (job != null && job.revision > observed.notifiedRevision && job.state in setOf("MERGE_READY", "NEEDS_INPUT", "COMPLETED")) {
                notifications.prompt(job.id, observed.chatId, 0, "${job.repository} · ${job.message}")
                return observed.copy(notifiedRevision = job.revision).also { save(it) }
            }
            return observed
        }
        return poll(requestId, maxPages = 2)
    }

    private suspend fun poll(requestId: String, maxPages: Int): GatewayEngineeringBinding = synchronization.withLock {
        var binding = bindings().single { it.request.requestId == requestId }
        if (binding.job == null) return@withLock binding
        if (binding.job?.terminal == true && binding.syncedRevision >= requireNotNull(binding.job).revision) return@withLock binding
        val config = config(binding)
        if (binding.job != null && System.nanoTime() / 1_000_000 - (lastPoll[requestId] ?: Long.MIN_VALUE / 2) < 2000) return@withLock binding
        check(binding.principalId == client.principal(config, requireReady = false)) { "The gateway account changed. Restore the original connection before reconnecting to this job." }
        var more: Boolean
        var pages = 0
        do {
            val snapshot = client.get(config, requireNotNull(binding.job).id, binding.syncedRevision)
            check(snapshot.id == binding.job?.id && snapshot.repository == binding.request.repository && snapshot.branch == binding.request.branch && snapshot.chatId == binding.request.chatId) { "Engineering job destination changed." }
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
        binding
    }

    suspend fun syncAll(): Boolean {
        var success = true
        for (binding in observableBindings().sortedBy { lastPoll[it.request.requestId] ?: Long.MIN_VALUE }.take(16)) {
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

    private suspend fun observableBindings(): List<GatewayEngineeringBinding> = bindings().filter { binding ->
        if (binding.job == null) store.dao.command(binding.request.requestId)?.state != "REJECTED" else !binding.job.terminal || binding.syncedRevision < binding.job.revision
    }

    /** This method is invoked by explicit UI buttons; it is never registered as an AI tool. */
    suspend fun control(requestId: String, expected: GatewayEngineeringJob, action: String) = synchronization.withLock {
        val binding = bindings().single { it.request.requestId == requestId }
        check(binding.job?.id == expected.id && binding.job?.revision == expected.revision) { "Refresh the job before confirming." }
        val config = config(binding)
        check(binding.principalId == client.principal(config, requireReady = action == "approve")) { "The gateway account changed; the control request was not sent." }
        val verified = conversations.mutationConfig(binding)
        check(verified.copy(extraHeaders = config.extraHeaders) == config) { "The original gateway configuration changed before the control request." }
        val result = client.control(verified, expected, action)
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
        store.saveBinding(binding)
    }

    companion object {
        const val OBSERVER_WORK_NAME = "gateway-engineering-sync"
        const val OBSERVER_GENERATION_KEY = "observer_generation"
    }
}
