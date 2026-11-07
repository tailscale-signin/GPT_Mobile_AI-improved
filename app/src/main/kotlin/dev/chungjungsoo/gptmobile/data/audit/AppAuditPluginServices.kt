package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.data.agent.tool.AgentToolResolver
import dev.chungjungsoo.gptmobile.data.agent.tool.McpClientManager
import dev.chungjungsoo.gptmobile.data.agent.tool.mcpFailurePresentation
import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplaceCatalog
import dev.chungjungsoo.gptmobile.data.catalog.MarketplaceRuntime
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.github.GitHubAutonomousEngineeringPolicy
import dev.chungjungsoo.gptmobile.data.marketplace.MarketplacePackageStore
import dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceRegistry
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.model.ToolServiceCatalog
import dev.chungjungsoo.gptmobile.data.network.ConnectionHealth
import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAPI
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayCapabilityDiscoveryException
import dev.chungjungsoo.gptmobile.data.readiness.ReadinessInvalidation
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.security.EndpointSecrets
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** Reuse the app's explicit private-gateway eligibility, without port or name guesses. */
fun appAuditGatewayProfileEligible(profile: PlatformV2): Boolean = GitHubAutonomousEngineeringPolicy.supportsSavedJobs(profile)

@Singleton
internal class AppAuditPluginServices @Inject constructor(
    private val settings: SettingRepository,
    private val connections: ToolConnectionRepository,
    private val nativePlugins: NativeMarketplaceRegistry,
    private val packages: MarketplacePackageStore,
    private val resolver: AgentToolResolver,
    private val mcp: McpClientManager,
    private val gateway: GatewayAPI,
    private val secretVault: SecretVault
) {
    suspend fun inventory(selectedProfileIds: List<String>): List<AppAuditPluginTarget> = inventory(selectedProfileIds, null)

    suspend fun target(key: String): AppAuditPluginTarget? = inventory(
        if (key.startsWith(AppAuditPluginProbeSource.GATEWAY_TARGET_PREFIX)) listOf(key.removePrefix(AppAuditPluginProbeSource.GATEWAY_TARGET_PREFIX)) else emptyList(),
        key
    ).singleOrNull { it.key == key }

    private suspend fun inventory(selectedProfileIds: List<String>, targetKey: String?): List<AppAuditPluginTarget> {
        val epoch = ReadinessInvalidation.epoch
        val features = settings.getFeatureSettings()
        val nativeSelected = targetKey == null || targetKey.startsWith("native:")
        val native = if (nativeSelected) safely { nativePlugins.load() } else emptyMap()
        val verifiedPackages = if (!nativeSelected) {
            emptySet()
        } else if (targetKey == null) {
            safely { packages.downloadedIds() }
        } else {
            safely {
                val selectedPackage = requireNotNull(GitHubMarketplaceCatalog.find(targetKey.removePrefix("native:")))
                packages.exportBytes(selectedPackage)
                setOf(selectedPackage.id)
            }
        }
        val configuredConnections = if (targetKey == null || targetKey.startsWith("connection:")) connections.listConnections() else emptyList()
        // Do not resolve model credentials when the user selected no profile target.
        val profiles = if (selectedProfileIds.isEmpty()) emptyList() else settings.fetchPlatformV2s()
        return buildList {
            ToolServiceCatalog.integrated.filter { targetKey == null || targetKey == "integrated:${it.id}" }.forEach { service ->
                val enabled = features.isToolPluginEnabled(service.id)
                add(
                    AppAuditPluginTarget(
                        "integrated:${service.id}",
                        AppAuditPluginKind.INTEGRATED,
                        service.id,
                        configured = true,
                        enabled = enabled,
                        fingerprint = appAuditTargetDigest("integrated:${service.id}:$enabled")
                    )
                )
            }
            GitHubMarketplaceCatalog.packages.filter { it.runtime == MarketplaceRuntime.NATIVE && (targetKey == null || targetKey == "native:${it.id}") }.forEach { entry ->
                val installation = native?.get(entry.id)
                val packageVerified = verifiedPackages?.contains(entry.id) == true
                val credentialIdentity = credentialIdentity(listOfNotNull(installation?.credentialRef) + installation?.endpointConfigs?.values.orEmpty().mapNotNull { it.secretRef })
                val ready = installation?.ready(entry) == true && packageVerified && credentialIdentity != null
                val enabled = installation?.enabled == true && features.isToolPluginEnabled(entry.id) && features.isToolPluginEnabled(ToolServiceCatalog.forPackage(entry).id)
                add(
                    AppAuditPluginTarget(
                        "native:${entry.id}",
                        AppAuditPluginKind.NATIVE,
                        "marketplace:${entry.id}",
                        ready,
                        enabled,
                        appAuditTargetDigest("${entry.id}:${installation?.copy(usageDay = "", usageCount = 0, nextRequestAt = 0)}:$packageVerified:$enabled:$credentialIdentity"),
                        unavailableReason = when {
                            native == null || verifiedPackages == null -> "PLUGIN_REGISTRY_UNAVAILABLE"
                            installation == null -> "PLUGIN_NOT_INSTALLED"
                            !packageVerified -> "PLUGIN_PACKAGE_NOT_VERIFIED"
                            credentialIdentity == null -> "PLUGIN_CREDENTIAL_IDENTITY_UNAVAILABLE"
                            else -> "PLUGIN_NOT_CONFIGURED"
                        }
                    )
                )
            }
            configuredConnections.filter { targetKey == null || targetKey == "connection:${it.connectionUid}" }.sortedBy { it.connectionUid }.forEach { connection ->
                val enabled = features.isToolPluginEnabled(ToolServiceCatalog.forConnection(connection).id) &&
                    features.isToolPluginEnabled(ToolPluginId.connection(connection.connectionUid)) &&
                    (connection.type != ToolConnectionType.MCP || features.remoteMcpConnections)
                val isMcp = connection.type == ToolConnectionType.MCP
                val credentialIdentity = credentialIdentity(listOfNotNull(connection.secretRef, EndpointSecrets.protectedReference(connection)))
                val ready = (!isMcp || !connection.endpointUrl.isNullOrBlank()) && (connection.authType == ToolConnectionAuthType.NONE || connection.secretRef != null) && credentialIdentity != null
                add(
                    AppAuditPluginTarget(
                        "connection:${connection.connectionUid}",
                        if (isMcp) AppAuditPluginKind.MCP else AppAuditPluginKind.INTEGRATED,
                        if (isMcp) "mcp_connection" else "configured_connection",
                        ready,
                        enabled,
                        appAuditTargetDigest("$connection:$enabled:$credentialIdentity"),
                        unavailableReason = if (credentialIdentity == null) "PLUGIN_CREDENTIAL_IDENTITY_UNAVAILABLE" else "PLUGIN_NOT_CONFIGURED",
                        liveReadPrecheck = if (isMcp) recoveryBlock(connection) else null,
                        liveRead = if (isMcp) ({ discover(connection) }) else null
                    )
                )
            }
            selectedProfileIds.distinct().sorted().forEach { uid ->
                val profile = profiles.singleOrNull { it.uid == uid }
                val eligible = profile?.let(::appAuditGatewayProfileEligible) == true
                val credentialIdentity = if (eligible) gatewayCredentialIdentity(requireNotNull(profile)) else "not-selected"
                add(
                    AppAuditPluginTarget(
                        AppAuditPluginProbeSource.GATEWAY_TARGET_PREFIX + uid,
                        AppAuditPluginKind.GATEWAY,
                        "gateway_connection",
                        eligible && credentialIdentity != null,
                        eligible,
                        // Credential plaintext is never serialized or hashed into an audit record.
                        appAuditTargetDigest("${profile?.copy(token = null)}:$credentialIdentity"),
                        unavailableReason = when {
                            profile == null -> "GATEWAY_PROFILE_UNAVAILABLE"
                            !eligible -> "GATEWAY_PROFILE_UNSUPPORTED"
                            else -> "PLUGIN_CREDENTIAL_IDENTITY_UNAVAILABLE"
                        },
                        liveRead = if (eligible) ({ capabilities(requireNotNull(profile)) }) else null
                    )
                )
            }
        }.map { target -> target.copy(snapshotCurrent = { ReadinessInvalidation.epoch == epoch }) }
    }

    private suspend fun gatewayCredentialIdentity(profile: PlatformV2): String? {
        // fetchPlatformV2s resolves the provider-owned reference. Check its owner too so a
        // stale cached profile cannot silently substitute another connection or credential.
        val owner = profile.providerConnectionUid?.let { uid -> safely { settings.getProviderConnection(uid) } ?: return null }
        if (owner != null && (owner.apiUrl != profile.apiUrl || owner.secretRef != profile.secretRef)) return null
        if (profile.token != null && profile.secretRef == null) return null
        return credentialIdentity(listOfNotNull(profile.secretRef))
    }

    private suspend fun credentialIdentity(references: List<String>): String? {
        val revisions = references.distinct().sorted().map { reference ->
            val revision = safely { secretVault.recordRevision(reference) }?.takeIf { it.matches(RECORD_REVISION) } ?: return null
            "$reference:$revision"
        }
        return appAuditTargetDigest(revisions.joinToString("\n"))
    }

    private fun recoveryBlock(connection: ToolConnection): AppAuditObservation? {
        val previous = mcp.healthSnapshot(connection.connectionUid)
        val retryAt = previous.nextRetryAtMs
        if (retryAt != null && retryAt > System.currentTimeMillis()) {
            return AppAuditObservation(
                AppAuditOutcome.BLOCKED,
                previous.lastErrorCode?.takeIf { it in AppAuditPluginProbeSource.reasonCodes } ?: if (retryAt == Long.MAX_VALUE) "MCP_RECONNECT_REQUIRED" else "MCP_BACKOFF_ACTIVE",
                stage = "mcp_discovery",
                evidenceKind = AppAuditEvidenceKind.LIVE_METADATA,
                observationSource = AppAuditObservationSource.CACHE,
                retryable = previous.failureRetryable
            )
        }
        return null
    }

    private suspend fun discover(connection: ToolConnection): AppAuditObservation {
        recoveryBlock(connection)?.let { return it }
        return try {
            val tools = resolver.discoverMcpTools(connection, forceRefresh = true, awaitCompletion = true)
            AppAuditObservation(
                AppAuditOutcome.PASS,
                if (tools.isEmpty()) "MCP_EMPTY_CATALOG" else "MCP_DISCOVERY_VERIFIED",
                stage = "mcp_discovery",
                evidenceKind = AppAuditEvidenceKind.LIVE_METADATA,
                observationSource = AppAuditObservationSource.LIVE,
                dispatchEvidence = AppAuditDispatchEvidence.CONFIRMED,
                metrics = listOf(AppAuditMetric(AppAuditMetricName.TOOL_COUNT, tools.size.toDouble()))
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val presentation = mcpFailurePresentation(failure, mcp.healthSnapshot(connection.connectionUid))
            AppAuditObservation(
                AppAuditOutcome.FAIL,
                presentation.errorCode?.takeIf { it in AppAuditPluginProbeSource.reasonCodes } ?: "MCP_DISCOVERY_UNAVAILABLE",
                stage = "mcp_discovery",
                evidenceKind = AppAuditEvidenceKind.LIVE_METADATA,
                observationSource = AppAuditObservationSource.LIVE,
                dispatchEvidence = AppAuditDispatchEvidence.UNKNOWN,
                retryable = presentation.retryable
            )
        }
    }

    private suspend fun capabilities(profile: PlatformV2): AppAuditObservation = try {
        val capability = gateway.getCapabilities(ProviderRequestConfig(profile.apiUrl, profile.token, profileUid = profile.uid, requestRole = "app-audit"))
        AppAuditObservation(
            if (capability == null) AppAuditOutcome.UNAVAILABLE else AppAuditOutcome.PASS,
            if (capability == null) "GATEWAY_NOT_SUPPORTED" else "GATEWAY_CAPABILITY_VERIFIED",
            stage = "gateway_capabilities",
            evidenceKind = AppAuditEvidenceKind.LIVE_METADATA,
            observationSource = AppAuditObservationSource.LIVE,
            dispatchEvidence = AppAuditDispatchEvidence.CONFIRMED,
            metrics = capability?.let { listOf(AppAuditMetric(AppAuditMetricName.RESULT_COUNT, 1.0)) }.orEmpty()
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        val health = LocalServiceHealth.classify(failure)
        val decodeReason = (failure as? GatewayCapabilityDiscoveryException)?.decodeReason?.takeIf { it in AppAuditPluginProbeSource.gatewayDecodeReasons }
        val code = when {
            decodeReason != null -> "GATEWAY_CAPABILITY_DECODE_$decodeReason"
            health == ConnectionHealth.TLS_FAILED -> "TLS_FAILURE"
            health.name in AppAuditPluginProbeSource.reasonCodes -> health.name
            else -> "GATEWAY_CAPABILITY_UNAVAILABLE"
        }
        AppAuditObservation(
            AppAuditOutcome.FAIL,
            code,
            stage = "gateway_capabilities",
            evidenceKind = AppAuditEvidenceKind.LIVE_METADATA,
            observationSource = AppAuditObservationSource.LIVE,
            dispatchEvidence = if (decodeReason != null) AppAuditDispatchEvidence.CONFIRMED else AppAuditDispatchEvidence.UNKNOWN,
            retryable = if (health in setOf(ConnectionHealth.TLS_FAILED, ConnectionHealth.AUTHENTICATION_FAILED)) false else null
        )
    }

    private suspend fun <T> safely(read: suspend () -> T): T? = try {
        read()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private companion object {
        val RECORD_REVISION = Regex("[a-f0-9]{64}")
    }
}
