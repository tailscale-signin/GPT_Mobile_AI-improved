package dev.chungjungsoo.gptmobile.data.github

import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayJobRecord
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class GatewayJobRepository @Inject constructor(
    private val database: ChatDatabaseV2,
    private val settings: SettingRepository,
    private val client: GatewayJobClient,
    private val engineeringStore: GatewayEngineeringStore
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val dao get() = database.gatewayJobDao()

    fun observe(profileUid: String): Flow<List<GatewayJobRecord>> = flow {
        val route = route(profileUid, requireEnabled = false)
        emitAll(dao.observeJobs(route.profileUid, route.endpoint))
    }

    suspend fun refresh(profileUid: String, cursor: String? = null): GatewayJobCatalog {
        val route = route(profileUid)
        val page = client.catalog(route.config, cursor)
        require(page.items.map { it.jobKind to it.jobId }.distinct().size == page.items.size)
        database.withTransaction {
            val rows = page.items.map { summary ->
                require(summary.jobId.isNotBlank() && summary.jobKind in setOf("engineering", "generation", "ordinary"))
                require(summary.acceptedAt.isFinite() && summary.acceptedAt >= 0 && summary.revision >= 0)
                val key = gatewayIdentityKey(page.gatewayId, page.principalId, summary.jobKind, summary.jobId)
                GatewayJobRecord(
                    key = key,
                    gatewayId = page.gatewayId,
                    principalId = page.principalId,
                    kind = summary.jobKind,
                    remoteJobId = summary.jobId,
                    conversationKey = summary.conversationId?.let { gatewayIdentityKey(page.gatewayId, page.principalId, it) },
                    connectionProfileUid = route.profileUid,
                    endpoint = route.endpoint,
                    executionProfileUid = summary.profileId,
                    snapshot = json.encodeToString(summary.copy(serverNow = summary.serverNow ?: page.serverNow)),
                    observedAtMillis = System.currentTimeMillis(),
                    seenRevision = dao.job(key)?.seenRevision ?: -1
                )
            }
            dao.saveJobs(rows)
        }
        return page
    }

    /** The menu shows the server inventory, including conversations never opened on this device. */
    suspend fun listConversations(profileUid: String, cursor: String? = null): GatewayConversationPage =
        client.conversations(route(profileUid, useEngineeringGateway = false).config, cursor)

    suspend fun configured(profileUid: String?): Boolean {
        if (profileUid == null) return false
        val profiles = settings.fetchPlatformV2s()
        val selected = profiles.singleOrNull { it.uid == profileUid } ?: return false
        val configured = settings.getFeatureSettings().githubProfileOverrides[profileUid]?.engineeringGatewayProfileUid
        return configured != null || GitHubAutonomousEngineeringPolicy.supportsSavedJobs(selected.copy(enabled = true))
    }

    /** Explicit legacy cache, never represented as a complete server inventory. */
    suspend fun legacyJobs(profileUid: String): List<GatewayJobRecord> {
        val route = route(profileUid, requireEnabled = false)
        return engineeringStore.bindings().filter { it.profileUid == route.profileUid && it.endpoint == route.endpoint && it.job != null }.map { binding ->
            val job = requireNotNull(binding.job)
            val gatewayId = job.gatewayId ?: "legacy:${gatewayIdentityKey(binding.endpoint)}"
            val principal = binding.principalId ?: "unknown"
            val summary = GatewayJobSummary(
                jobId = job.id,
                jobKind = "engineering",
                conversationId = job.conversationId,
                title = binding.request.objective.lineSequence().firstOrNull().orEmpty().take(120),
                profileId = binding.request.profileId,
                model = binding.request.model,
                repository = job.repository,
                branch = job.branch,
                base = binding.request.base,
                state = job.state,
                phase = job.message,
                revision = job.revision.toLong(),
                acceptedAt = job.acceptedAt ?: job.createdAt,
                finishedAt = job.finishedAt,
                serverNow = job.serverNow ?: (job.finishedAt ?: job.createdAt),
                resultAvailable = job.finalReport != null
            )
            GatewayJobRecord(
                gatewayIdentityKey(gatewayId, principal, "engineering", job.id), gatewayId, principal, "engineering", job.id,
                job.conversationId?.let { gatewayIdentityKey(gatewayId, principal, it) }, route.profileUid, route.endpoint,
                binding.request.profileId, json.encodeToString(summary), 0, binding.seenRevision.toLong()
            )
        }
    }

    suspend fun legacyBinding(remoteJobId: String): GatewayEngineeringBinding? = engineeringStore.bindings().singleOrNull { it.job?.id == remoteJobId }

    fun summary(record: GatewayJobRecord): GatewayJobSummary = json.decodeFromString(record.snapshot)

    internal suspend fun route(profileUid: String, requireEnabled: Boolean = true, useEngineeringGateway: Boolean = true): GatewayJobRoute {
        val profiles = settings.fetchPlatformV2s()
        val configured = if (useEngineeringGateway) settings.getFeatureSettings().githubProfileOverrides[profileUid]?.engineeringGatewayProfileUid else null
        val profile = profiles.singleOrNull { it.uid == (configured ?: profileUid) }
            ?: error("Restore the original gateway profile to reconnect. Its saved work is retained.")
        check(!requireEnabled || profile.enabled) { "The gateway profile is disabled. Enable its original connection to reconnect." }
        check(profile.apiUrl.isNotBlank()) { "The original gateway address is unavailable." }
        return GatewayJobRoute(profile.uid, profile.apiUrl.trimEnd('/'), ProviderRequestConfig(profile.apiUrl, profile.token, profileUid = profile.uid))
    }

    internal suspend fun originalRoute(profileUid: String, endpoint: String): GatewayJobRoute = route(profileUid, useEngineeringGateway = false).also {
        check(it.profileUid == profileUid && it.endpoint == endpoint.trimEnd('/')) { "The gateway connection changed. Restore the saved address before reconnecting." }
    }
}

internal data class GatewayJobRoute(val profileUid: String, val endpoint: String, val config: ProviderRequestConfig)
