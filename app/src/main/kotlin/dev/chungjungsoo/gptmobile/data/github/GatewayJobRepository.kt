package dev.chungjungsoo.gptmobile.data.github

import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.context.RouteCapabilityCache
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayJobRecord
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewaySurfacePolicy
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class GatewayJobRepository @Inject constructor(
    private val database: ChatDatabaseV2,
    private val settings: SettingRepository,
    private val client: GatewayJobClient,
    private val engineeringStore: GatewayEngineeringStore,
    private val verifiedScopes: GatewayVerifiedScopeStore
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val dao get() = database.gatewayJobDao()
    private val savedWorkContracts = RouteCapabilityCache<GatewaySavedWorkContract>({ System.nanoTime() / 1_000_000 }, positiveTtl = 30_000)
    private val savedWorkRouteIdentities = linkedSetOf<String>()

    fun observe(profileUid: String): Flow<List<GatewayJobRecord>> = flow {
        val route = route(profileUid, requireEnabled = false)
        emitAll(dao.observeJobs(route.profileUid, route.endpoint))
    }

    internal fun observe(route: GatewayJobRoute): Flow<List<GatewayJobRecord>> = dao.observeJobs(route.profileUid, route.endpoint)

    /** Saved credential/endpoint changes invalidate the displayed account before another poll. */
    internal fun observeConfiguration(profileUid: String): Flow<GatewayJobRoute?> =
        combine(settings.observePlatformV2s(), settings.observeFeatureSettings()) { profiles, features ->
            try {
                val selected = profiles.singleOrNull { it.uid == profileUid }
                val bound = features.githubProfileOverrides[profileUid]?.engineeringGatewayProfileUid
                if (selected == null || bound.isNullOrBlank()) {
                    null
                } else {
                    resolveRoute(profileUid, profiles, features, requireEnabled = false, useEngineeringGateway = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }.distinctUntilChanged().combine(verifiedScopes.changes) { route, _ -> route }

    suspend fun refresh(profileUid: String, cursor: String? = null): GatewayJobCatalog {
        val route = route(profileUid)
        val surfaceIdentity = GatewaySurfacePolicy.identity(route.config)
        synchronized(savedWorkRouteIdentities) {
            if (savedWorkRouteIdentities.size >= 128) savedWorkRouteIdentities.remove(savedWorkRouteIdentities.first())
            savedWorkRouteIdentities.add(surfaceIdentity)
        }
        val contract = savedWorkContracts.getOrLoad("$surfaceIdentity|${route.configurationRevision}") {
            client.verifySavedWorkContract(route.config, verifyHistorySurfaces = false)
        } ?: throw GatewayHistoryUnsupported(GatewayAdmissionSurface.CAPABILITIES)
        requireRouteUnchanged(profileUid, route)
        val page = client.catalog(route.config, cursor)
        if (page.gatewayId != contract.identity.gatewayId || page.principalId != contract.identity.principalId) {
            savedWorkContracts.invalidate()
            throw GatewayAdmissionException(GatewayAdmissionReason.IDENTITY_MISMATCH, 200, null, GatewayAdmissionSurface.JOB_CATALOG)
        }
        require(page.items.map { it.jobKind to it.jobId }.distinct().size == page.items.size)
        requireRouteUnchanged(profileUid, route)
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
        requireRouteUnchanged(profileUid, route)
        verifiedScopes.remember(route.profileUid, route.configurationRevision, page.gatewayId, page.principalId)
        return page
    }

    /** Manual repair rechecks the gateway contract without submitting or replaying any work. */
    fun invalidateSavedWorkContract() {
        savedWorkContracts.invalidate()
        synchronized(savedWorkRouteIdentities) {
            savedWorkRouteIdentities.forEach { GatewaySurfacePolicy.shared.invalidate(it, GatewaySurfacePolicy.Surface.CAPABILITIES) }
        }
    }

    /** The menu shows the server inventory, including conversations never opened on this device. */
    suspend fun listConversations(profileUid: String, cursor: String? = null): GatewayConversationPage {
        val route = route(profileUid, useEngineeringGateway = false)
        val page = client.conversations(route.config, cursor)
        requireRouteUnchanged(profileUid, route, useEngineeringGateway = false)
        verifiedScopes.remember(route.profileUid, route.configurationRevision, page.gatewayId, page.principalId)
        return page
    }

    internal suspend fun cachedScope(route: GatewayJobRoute): GatewayVerifiedScope? =
        verifiedScopes.read(route.profileUid, route.configurationRevision)

    /** Only previously retained local transcripts; this is not a complete server inventory. */
    internal suspend fun cachedConversations(profileUid: String): GatewayCachedConversations? {
        val route = route(profileUid, requireEnabled = false, useEngineeringGateway = false)
        val scope = cachedScope(route) ?: return null
        val records = dao.cachedConversations(route.profileUid, route.endpoint, scope.gatewayId, scope.principalId)
        val items = records.mapNotNull { record ->
            runCatching { json.decodeFromString<GatewayConversationSnapshot>(record.snapshot) }.getOrNull()
                ?.takeIf { it.gatewayId == scope.gatewayId && it.principalId == scope.principalId && it.conversationId == record.conversationId }
        }
        requireRouteUnchanged(profileUid, route, requireEnabled = false, useEngineeringGateway = false)
        check(cachedScope(route) == scope) { "The verified gateway account changed while reading its saved history." }
        return GatewayCachedConversations(
            GatewayConversationPage(gatewayId = scope.gatewayId, principalId = scope.principalId, items = items),
            records.maxOfOrNull { it.syncedAtMillis }?.takeIf { it > 0 }
        )
    }

    suspend fun configured(profileUid: String?): Boolean {
        if (profileUid == null) return false
        val profiles = settings.fetchPlatformV2s()
        if (profiles.singleOrNull { it.uid == profileUid } == null) return false
        val configured = settings.getFeatureSettings().githubProfileOverrides[profileUid]?.engineeringGatewayProfileUid
        val gatewayProfile = profiles.singleOrNull { it.uid == configured } ?: return false
        return gatewayProfile.apiUrl.isNotBlank() && GitHubAutonomousEngineeringPolicy.supportsSavedJobs(gatewayProfile.copy(enabled = true))
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
        return resolveRoute(profileUid, profiles, settings.getFeatureSettings(), requireEnabled, useEngineeringGateway)
    }

    private suspend fun resolveRoute(profileUid: String, profiles: List<PlatformV2>, features: AppFeatureSettings, requireEnabled: Boolean, useEngineeringGateway: Boolean): GatewayJobRoute {
        val configured = if (useEngineeringGateway) features.githubProfileOverrides[profileUid]?.engineeringGatewayProfileUid else null
        check(!useEngineeringGateway || !configured.isNullOrBlank()) { "Select the engineering gateway connection in GitHub settings. The model server address is not a fallback for saved jobs." }
        val profile = profiles.singleOrNull { it.uid == (configured ?: profileUid) }
            ?: error("Restore the original gateway profile to reconnect. Its saved work is retained.")
        check(!requireEnabled || profile.enabled) { "The gateway profile is disabled. Enable its original connection to reconnect." }
        check(profile.apiUrl.isNotBlank()) { "The original gateway address is unavailable." }
        check(GitHubAutonomousEngineeringPolicy.supportsSavedJobs(profile.copy(enabled = true))) { "Select a private Llama or Ollama gateway connection for saved jobs." }
        return GatewayJobRoute(profile.uid, profile.apiUrl.trimEnd('/'), ProviderRequestConfig(profile.apiUrl, profile.token, profileUid = profile.uid), verifiedScopes.configurationRevision(profile))
    }

    internal suspend fun originalRoute(profileUid: String, endpoint: String): GatewayJobRoute = route(profileUid, useEngineeringGateway = false).also {
        check(it.profileUid == profileUid && it.endpoint == endpoint.trimEnd('/')) { "The gateway connection changed. Restore the saved address before reconnecting." }
    }

    private suspend fun requireRouteUnchanged(profileUid: String, expected: GatewayJobRoute, requireEnabled: Boolean = true, useEngineeringGateway: Boolean = true) {
        check(route(profileUid, requireEnabled, useEngineeringGateway) == expected) { "The gateway account or connection changed while reading its history. Reconnect the current saved configuration." }
    }
}

internal data class GatewayJobRoute(val profileUid: String, val endpoint: String, val config: ProviderRequestConfig, val configurationRevision: String? = null)
internal data class GatewayCachedConversations(val page: GatewayConversationPage, val lastSyncedAtMillis: Long?)
