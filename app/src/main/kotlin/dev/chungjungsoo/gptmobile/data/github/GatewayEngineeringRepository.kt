package dev.chungjungsoo.gptmobile.data.github

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class GatewayEngineeringRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingRepository,
    private val client: GatewayEngineeringClient,
    private val notifications: GitHubDecisionNotifications
) {
    private val synchronization = Mutex()
    private val lastPoll = mutableMapOf<String, Long>()

    suspend fun bindings(chatId: Int? = null): List<GatewayEngineeringBinding> = settings.getFeatureSettings().gatewayEngineeringJobs
        .filter { chatId == null || it.chatId == chatId }

    suspend fun options(): GatewayEngineeringSettings = settings.getFeatureSettings().githubEngineeringSettings

    private suspend fun config(binding: GatewayEngineeringBinding): ProviderRequestConfig {
        val profile = settings.fetchPlatformV2s().singleOrNull { it.uid == binding.profileUid && it.enabled }
            ?: error("The saved engineering AI profile is unavailable. The gateway job is retained.")
        check(profile.compatibleType == ClientType.LLAMA && profile.apiUrl.trimEnd('/') == binding.endpoint) { "The AI profile now points to another gateway. Restore the original connection to reconnect to this job." }
        return ProviderRequestConfig(profile.apiUrl, profile.token, profileUid = profile.uid)
    }

    suspend fun start(chatId: Int, context: GitHubConversationContext, base: String, branch: String, objective: String, options: GatewayEngineeringSettings) {
        require(objective.isNotBlank() && objective.length <= 16000 && branch != base)
        val profile = settings.fetchPlatformV2s().single { it.uid == context.mainProfileUid && it.enabled }
        require(profile.compatibleType == ClientType.LLAMA) { "Select a llama gateway AI profile to start an autonomous job." }
        val principal = client.principal(ProviderRequestConfig(profile.apiUrl, profile.token, profileUid = profile.uid))
        val binding = GatewayEngineeringBinding(
            profile.uid,
            profile.apiUrl.trimEnd('/'),
            GatewayEngineeringRequest(
                "${context.owner}/${context.repo}", branch, base, objective, profile.model, options.durationHours, options.repairAttempts, chatId, UUID.randomUUID().toString()
            ),
            principalId = principal
        )
        settings.mutateFeatureSettings {
            check(it.gatewayEngineeringJobs.size < 64) { "Saved engineering jobs reached capacity." }
            it.copy(githubEngineeringSettings = options, gatewayEngineeringJobs = it.gatewayEngineeringJobs + binding)
        }
        schedule()
        reconnect(binding.request.requestId)
    }

    fun schedule() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "gateway-engineering-sync",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<GatewayEngineeringSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        )
    }

    suspend fun reconnect(requestId: String): GatewayEngineeringBinding = synchronization.withLock {
        var binding = bindings().single { it.request.requestId == requestId }
        if (binding.job?.terminal == true && binding.syncedRevision >= requireNotNull(binding.job).revision) return@withLock binding
        val config = config(binding)
        if (binding.job != null && System.nanoTime() / 1_000_000 - (lastPoll[requestId] ?: Long.MIN_VALUE / 2) < 2000) return@withLock binding
        check(binding.principalId == client.principal(config)) { "The gateway account changed. Restore the original connection before reconnecting to this job." }
        if (binding.job == null) {
            // Reuse the persisted key after a lost create response. Never launch a duplicate job.
            val job = client.create(config, binding.request)
            binding = binding.copy(job = job)
            save(binding)
        }
        var more: Boolean
        do {
            val snapshot = client.get(config, requireNotNull(binding.job).id, binding.syncedRevision)
            check(snapshot.id == binding.job?.id && snapshot.repository == binding.request.repository && snapshot.branch == binding.request.branch && snapshot.chatId == binding.request.chatId) { "Engineering job destination changed." }
            check(snapshot.revision >= (binding.job?.revision ?: -1)) { "Gateway returned stale engineering state." }
            val cursor = snapshot.nextRevision ?: snapshot.revision
            check(cursor in binding.syncedRevision..snapshot.revision && (!snapshot.hasMoreUpdates || cursor > binding.syncedRevision)) { "Engineering update cursor did not advance." }
            val updates = (binding.updates + snapshot.updates).distinctBy { it.revision }.sortedBy { it.revision }.takeLast(10000)
            binding = binding.copy(job = snapshot.copy(updates = emptyList()), updates = updates, syncedRevision = cursor)
            save(binding)
            more = snapshot.hasMoreUpdates
        } while (more)
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
        for (binding in bindings().filter { it.job?.terminal != true }) {
            try {
                reconnect(binding.request.requestId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                success = false
            }
        }
        return success
    }

    /** This method is invoked by explicit UI buttons; it is never registered as an AI tool. */
    suspend fun control(requestId: String, expected: GatewayEngineeringJob, action: String) = synchronization.withLock {
        val binding = bindings().single { it.request.requestId == requestId }
        check(binding.job?.id == expected.id && binding.job?.revision == expected.revision) { "Refresh the job before confirming." }
        val config = config(binding)
        check(binding.principalId == client.principal(config)) { "The gateway account changed; approval was not sent." }
        val result = client.control(config, expected, action)
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
        settings.mutateFeatureSettings { current ->
            check(current.gatewayEngineeringJobs.any { it.request.requestId == binding.request.requestId }) { "Engineering binding was removed." }
            current.copy(gatewayEngineeringJobs = current.gatewayEngineeringJobs.map { if (it.request.requestId == binding.request.requestId) binding else it })
        }
    }
}
