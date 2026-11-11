package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.data.agent.tool.AgentToolResolver
import dev.chungjungsoo.gptmobile.data.agent.tool.McpBackoffException
import dev.chungjungsoo.gptmobile.data.agent.tool.McpClientManager
import dev.chungjungsoo.gptmobile.data.agent.tool.McpConnectionHealth
import dev.chungjungsoo.gptmobile.data.agent.tool.McpConnectionHealthState
import dev.chungjungsoo.gptmobile.data.agent.tool.McpDiscoveryContext
import dev.chungjungsoo.gptmobile.data.agent.tool.McpDiscoverySource
import dev.chungjungsoo.gptmobile.data.agent.tool.McpDiscoveryTrigger
import dev.chungjungsoo.gptmobile.data.agent.tool.mcpConfigurationRevision
import dev.chungjungsoo.gptmobile.data.agent.tool.mcpFailurePresentation
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStorageFailure
import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplaceCatalog
import dev.chungjungsoo.gptmobile.data.catalog.MarketplaceRuntime
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.github.GatewayAdmissionException
import dev.chungjungsoo.gptmobile.data.github.GatewayAdmissionReason
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringBinding
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringClient
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringStore
import dev.chungjungsoo.gptmobile.data.github.GatewayExecutionRejected
import dev.chungjungsoo.gptmobile.data.github.GatewayScopeIdentity
import dev.chungjungsoo.gptmobile.data.github.GitHubAutonomousEngineeringPolicy
import dev.chungjungsoo.gptmobile.data.github.engineeringConfig
import dev.chungjungsoo.gptmobile.data.github.engineeringGatewayProfile
import dev.chungjungsoo.gptmobile.data.github.withGatewayIdentity
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
    private val secretVault: SecretVault,
    private val engineeringGateway: GatewayEngineeringClient? = null,
    private val engineeringStore: GatewayEngineeringStore? = null
) {
    suspend fun inventory(selectedProfileIds: List<String>): List<AppAuditPluginTarget> = inventory(selectedProfileIds, null)

    suspend fun target(key: String): AppAuditPluginTarget? = inventory(
        listOfNotNull(AppAuditPluginProbeSource.selectedGatewayProfileId(key)),
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
        val savedJobs = if (selectedProfileIds.isEmpty() || targetKey?.startsWith(AppAuditPluginProbeSource.GATEWAY_TARGET_PREFIX) == true) {
            emptyList()
        } else {
            engineeringStore?.bindings(includeUpdates = false).orEmpty()
        }
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
                val authReference = connection.secretRef.takeUnless { isMcp && connection.authType == ToolConnectionAuthType.NONE }
                val configurationRevision = if (isMcp) mcpConfigurationRevision(connection, secretVault) else null
                val credentialIdentity = if (isMcp) configurationRevision else credentialIdentity(listOfNotNull(authReference, EndpointSecrets.protectedReference(connection)))
                val ready = (!isMcp || !connection.endpointUrl.isNullOrBlank()) && (connection.authType == ToolConnectionAuthType.NONE || connection.secretRef != null) && credentialIdentity != null
                add(
                    AppAuditPluginTarget(
                        "connection:${connection.connectionUid}",
                        if (isMcp) AppAuditPluginKind.MCP else AppAuditPluginKind.INTEGRATED,
                        if (isMcp) "mcp_connection" else "configured_connection",
                        ready,
                        enabled,
                        if (isMcp) appAuditTargetDigest("$configurationRevision:$enabled") else appAuditTargetDigest("${connection.copy(secretRef = authReference)}:$enabled:$credentialIdentity"),
                        unavailableReason = when {
                            (isMcp && connection.endpointUrl.isNullOrBlank()) || (connection.authType != ToolConnectionAuthType.NONE && connection.secretRef == null) -> "PLUGIN_NOT_CONFIGURED"
                            credentialIdentity == null -> "PLUGIN_CREDENTIAL_IDENTITY_UNAVAILABLE"
                            else -> "PLUGIN_NOT_CONFIGURED"
                        },
                        liveReadPrecheck = if (isMcp) recoveryBlock(connection, configurationRevision) else null,
                        liveRead = if (isMcp) ({ beforeDispatch -> discover(connection, configurationRevision, beforeDispatch) }) else null,
                        configurationRevision = configurationRevision
                    )
                )
            }
            selectedProfileIds.distinct().sorted().forEach { uid ->
                val profile = profiles.singleOrNull { it.uid == uid }
                val eligible = profile?.let(::appAuditGatewayProfileEligible) == true
                val gatewayUid = features.githubProfileOverrides[uid]?.engineeringGatewayProfileUid
                if (targetKey?.startsWith(AppAuditPluginProbeSource.GATEWAY_TARGET_PREFIX) != true) {
                    val bindings = savedJobs.filter { (it.executionSelection?.profileUid ?: it.request.profileId) == uid }
                    val boundTargets = bindings.associateBy(::savedJobAuditIdentity)
                    if (boundTargets.isNotEmpty()) {
                        boundTargets.toSortedMap().forEach { (identity, binding) ->
                            val key = "${AppAuditPluginProbeSource.ENGINEERING_GATEWAY_TARGET_PREFIX}$uid:$identity"
                            if (targetKey == null || targetKey == key) add(engineeringTarget(key, profiles, binding.profileUid, binding))
                        }
                    } else if (!gatewayUid.isNullOrBlank()) {
                        val key = AppAuditPluginProbeSource.ENGINEERING_GATEWAY_TARGET_PREFIX + uid
                        if (targetKey == null || targetKey == key) add(engineeringTarget(key, profiles, gatewayUid, null))
                    }
                }
                if (targetKey != null && targetKey != AppAuditPluginProbeSource.GATEWAY_TARGET_PREFIX + uid) return@forEach
                if (targetKey == null && !eligible) return@forEach
                val credentialIdentity = if (eligible) gatewayCredentialIdentity(requireNotNull(profile)) else "not-selected"
                // Credential plaintext is never serialized or hashed into an audit record.
                val fingerprint = appAuditTargetDigest("${profile?.copy(token = null)}:$credentialIdentity")
                val configurationRevision = appAuditConfigurationRevision(fingerprint)
                add(
                    AppAuditPluginTarget(
                        AppAuditPluginProbeSource.GATEWAY_TARGET_PREFIX + uid,
                        AppAuditPluginKind.GATEWAY,
                        "gateway_connection",
                        eligible && credentialIdentity != null,
                        eligible,
                        fingerprint,
                        unavailableReason = when {
                            profile == null -> "GATEWAY_PROFILE_UNAVAILABLE"
                            !eligible -> "GATEWAY_PROFILE_UNSUPPORTED"
                            else -> "PLUGIN_CREDENTIAL_IDENTITY_UNAVAILABLE"
                        },
                        liveRead = if (eligible) {
                            (
                                { beforeDispatch ->
                                    beforeDispatch()
                                    capabilities(requireNotNull(profile), configurationRevision)
                                }
                                )
                        } else {
                            null
                        },
                        configurationRevision = configurationRevision
                    )
                )
            }
        }.map { target -> target.copy(snapshotCurrent = { ReadinessInvalidation.epoch == epoch }) }
    }

    private fun savedJobAuditIdentity(binding: GatewayEngineeringBinding): String {
        val selection = binding.executionSelection
        val bindingId = binding.request.executionBindingId?.takeIf(SAVED_BINDING_ID::matches)
        val requestId = binding.request.requestId.takeIf {
            bindingId == null || selection == null || binding.gatewayId.isNullOrBlank() || binding.principalId.isNullOrBlank()
        }
        val fields = listOf(binding.profileUid, binding.endpoint, binding.gatewayId, binding.principalId, bindingId, selection?.profileRevision, requestId)
        return appAuditTargetDigest(JsonArray(fields.map { it?.let(::JsonPrimitive) ?: JsonNull }).toString())
    }

    private suspend fun engineeringTarget(key: String, profiles: List<PlatformV2>, gatewayUid: String?, binding: GatewayEngineeringBinding?): AppAuditPluginTarget {
        val profile = engineeringGatewayProfile(profiles, gatewayUid)?.takeIf { binding == null || it.apiUrl.trimEnd('/') == binding.endpoint }
        val credential = profile?.let { gatewayCredentialIdentity(it) }
        val fingerprint = appAuditTargetDigest("${profile?.copy(token = null)}:$key:$gatewayUid:$credential")
        val revision = appAuditConfigurationRevision(fingerprint).takeIf { credential != null }
        return AppAuditPluginTarget(
            key, AppAuditPluginKind.GATEWAY, AppAuditPluginProbeSource.ENGINEERING_GATEWAY_COMPONENT,
            configured = profile != null && revision != null,
            enabled = profile != null,
            fingerprint = fingerprint,
            unavailableReason = if (profile == null) "GATEWAY_ENGINEERING_PROFILE_REQUIRED" else "PLUGIN_CREDENTIAL_IDENTITY_UNAVAILABLE",
            liveRead = if (profile != null && revision != null) {
                { beforeDispatch ->
                    beforeDispatch()
                    engineeringCapabilities(profile, binding, revision)
                }
            } else {
                null
            },
            configurationRevision = revision
        )
    }

    private suspend fun engineeringCapabilities(profile: PlatformV2, binding: GatewayEngineeringBinding?, revision: String): AppAuditObservation {
        val client = requireNotNull(engineeringGateway)
        val config = profile.engineeringConfig().copy(requestRole = "app-audit-engineering")
        val capability = try {
            client.capabilities(config, forceRefresh = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val status = engineeringFailureStatus(failure)
            return AppAuditObservation(
                AppAuditOutcome.FAIL,
                engineeringFailureReason(failure),
                "gateway_capabilities",
                AppAuditEvidenceKind.LIVE_METADATA,
                AppAuditObservationSource.LIVE,
                if (status == null) AppAuditDispatchEvidence.UNKNOWN else AppAuditDispatchEvidence.CONFIRMED,
                httpStatus = status,
                configurationRevision = revision
            )
        }
        val installation = binding?.gatewayId?.takeIf(String::isNotBlank)
        val principal = binding?.principalId?.takeIf(String::isNotBlank)
        val identityMatches = binding == null ||
            installation != null &&
            principal != null &&
            capability.principalId == principal &&
            capability.gatewayId == installation
        val githubConfigured = capability.readiness?.checks?.hostGithubConnection?.let {
            when (it) {
                "configured" -> true
                "missing" -> false
                else -> null
            }
        }
        val selection = binding?.executionSelection
        val bindingId = binding?.request?.executionBindingId?.takeIf(SAVED_BINDING_ID::matches)
        val reason = when {
            binding != null && (installation == null || principal == null) -> "GATEWAY_SAVED_JOB_IDENTITY_UNAVAILABLE"
            !identityMatches -> "GATEWAY_ENGINEERING_IDENTITY_CHANGED"
            githubConfigured == false -> "GATEWAY_ENGINEERING_GITHUB_REQUIRED"
            capability.readiness == null -> "GATEWAY_ENGINEERING_READINESS_UNAVAILABLE"
            !capability.enabled || !capability.readiness.ready -> "GATEWAY_ENGINEERING_NOT_READY"
            githubConfigured != true -> "GATEWAY_ENGINEERING_GITHUB_UNVERIFIED"
            binding != null && (selection == null || bindingId == null) -> "GATEWAY_SAVED_BINDING_UNAVAILABLE"
            else -> null
        }
        fun observation(blocker: String? = null, modelReady: Boolean? = null, status: Int? = 200) = AppAuditObservation(
            if (blocker == null) AppAuditOutcome.PASS else AppAuditOutcome.BLOCKED,
            blocker ?: "GATEWAY_ENGINEERING_CAPABILITY_VERIFIED",
            "gateway_capabilities", AppAuditEvidenceKind.LIVE_METADATA, AppAuditObservationSource.LIVE, AppAuditDispatchEvidence.CONFIRMED,
            httpStatus = status, observedAtEpochMs = System.currentTimeMillis(), configurationRevision = revision,
            remoteIdentity = safeAuditRemoteIdentity(AppAuditRemoteIdentity(version = capability.gatewayVersion)),
            gatewayReadiness = AppAuditGatewayReadiness(binding != null, capability.readiness?.ready, githubConfigured, modelReady).takeIf { identityMatches }
        )
        if (reason != null || binding == null) return observation(reason)
        val boundConfig = config.withGatewayIdentity(GatewayScopeIdentity(requireNotNull(installation), requireNotNull(principal)))
        return try {
            client.bindingCapabilities(boundConfig, requireNotNull(selection), requireNotNull(bindingId))
            observation(modelReady = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val blocker = engineeringFailureReason(failure)
            observation(blocker, false.takeIf { blocker == "GATEWAY_SAVED_MODEL_NOT_READY" }, engineeringFailureStatus(failure))
        }
    }

    private fun engineeringFailureStatus(failure: Exception): Int? = when (failure) {
        is GatewayAdmissionException -> failure.httpStatus
        is GatewayExecutionRejected -> failure.httpStatus
        else -> null
    }

    private fun engineeringFailureReason(failure: Exception): String = when (failure) {
        is GatewayAdmissionException -> when {
            failure.reason in setOf(GatewayAdmissionReason.IDENTITY_MISMATCH, GatewayAdmissionReason.SOURCE_MISMATCH, GatewayAdmissionReason.TRANSPORT_IDENTITY_REQUIRED) -> "GATEWAY_ENGINEERING_IDENTITY_CHANGED"
            failure.httpStatus in setOf(401, 403) || failure.reason == GatewayAdmissionReason.MISSING_SCOPE -> "GATEWAY_ENGINEERING_AUTHENTICATION_REQUIRED"
            failure.reason in setOf(GatewayAdmissionReason.TRANSPORT_INTERRUPTED, GatewayAdmissionReason.INVALID_CONNECTION) -> "GATEWAY_ENGINEERING_TRANSPORT_UNAVAILABLE"
            failure.reason == GatewayAdmissionReason.UNSUPPORTED_CONTRACT -> "GATEWAY_ENGINEERING_ROUTE_UNAVAILABLE"
            else -> "GATEWAY_CAPABILITY_UNAVAILABLE"
        }
        is GatewayExecutionRejected -> when (failure.code) {
            "EXECUTION_BINDING_REVOKED", "EXECUTION_METADATA_DENIED" -> "GATEWAY_ENGINEERING_AUTHENTICATION_REQUIRED"
            "EXECUTION_CONFIGURATION_CHANGED", "EXECUTION_BINDING_CORRUPT", "EXECUTION_SELECTION_MISMATCH" -> "GATEWAY_ENGINEERING_IDENTITY_CHANGED"
            "EXECUTION_BACKEND_UNAVAILABLE", "EXECUTION_ENDPOINT_UNAVAILABLE", "EXECUTION_METADATA_DEADLINE" -> "GATEWAY_ENGINEERING_TRANSPORT_UNAVAILABLE"
            "EXECUTION_MODEL_UNAVAILABLE", "EXECUTION_MODEL_NOT_LOADED", "EXECUTION_MODEL_LOADING", "EXECUTION_MODEL_MISMATCH",
            "EXECUTION_MODEL_REVISION_CHANGED", "EXECUTION_MODEL_IDENTITY_INVALID" -> "GATEWAY_SAVED_MODEL_NOT_READY"
            else -> "GATEWAY_SAVED_BINDING_UNAVAILABLE"
        }
        else -> "GATEWAY_CAPABILITY_UNAVAILABLE"
    }

    private suspend fun gatewayCredentialIdentity(profile: PlatformV2): String? {
        // fetchPlatformV2s resolves the provider-owned reference. Check its owner too so a
        // stale cached profile cannot silently substitute another connection or credential.
        val owner = profile.providerConnectionUid?.let { uid -> safely { settings.getProviderConnection(uid) } ?: return null }
        if (owner != null && (owner.apiUrl != profile.apiUrl || owner.secretRef != profile.secretRef)) return null
        if (!profile.token.isNullOrBlank() && profile.secretRef == null) return null
        return credentialIdentity(listOfNotNull(profile.secretRef))
    }

    private suspend fun credentialIdentity(references: List<String>): String? {
        val revisions = references.distinct().sorted().map { reference ->
            val revision = safely { secretVault.recordRevision(reference) }?.takeIf { it.matches(RECORD_REVISION) } ?: return null
            "$reference:$revision"
        }
        return appAuditTargetDigest(revisions.joinToString("\n"))
    }

    private fun recoveryBlock(connection: ToolConnection, configurationRevision: String?): AppAuditObservation? {
        val previous = mcp.healthSnapshot(connection.connectionUid)
        // A retained failure for a different saved configuration cannot veto a new plan.
        // Legacy health has no revision proof: preserve its original time and unknown revision.
        if (previous.failureConfigurationRevision != null && configurationRevision != null && previous.failureConfigurationRevision != configurationRevision) return null
        // An explicitly selected audit read may recheck a repaired remote contract once
        // under the runner/client budgets. Credentials, TLS and recovery timers retain
        // their gates; the fresh request still performs the complete protocol validation.
        if (previous.lastErrorCode in FRESH_CONTRACT_RECHECK_CODES &&
            previous.state != McpConnectionHealthState.AUTHENTICATION_REQUIRED &&
            previous.connectionHealth !in setOf(ConnectionHealth.AUTHENTICATION_FAILED, ConnectionHealth.TLS_FAILED) &&
            previous.lastHttpStatus !in setOf(401, 403)
        ) {
            return null
        }
        val retryAt = previous.nextRetryAtMs
        if (retryAt != null && retryAt > System.currentTimeMillis()) {
            val presentation = mcpFailurePresentation(McpBackoffException(connection.connectionUid, retryAt), previous)
            return AppAuditObservation(
                AppAuditOutcome.BLOCKED,
                previous.lastErrorCode?.takeIf { it in AppAuditPluginProbeSource.reasonCodes } ?: if (retryAt == Long.MAX_VALUE) "MCP_RECONNECT_REQUIRED" else "MCP_BACKOFF_ACTIVE",
                stage = "mcp_discovery",
                evidenceKind = AppAuditEvidenceKind.LIVE_METADATA,
                observationSource = AppAuditObservationSource.CACHE,
                dispatchEvidence = AppAuditDispatchEvidence.NOT_DISPATCHED,
                retryable = previous.failureRetryable,
                httpStatus = presentation.httpStatus,
                failurePhase = safeAuditFailurePhase(presentation.phase),
                failureCategory = safeAuditFailureCategory(presentation.outcome),
                validationRule = safeAuditValidationRule(presentation.validationRule),
                validationField = safeAuditDiagnosticField(presentation.validationField),
                observedAtEpochMs = previous.lastFailureAtMs?.takeIf { it >= 0 },
                configurationRevision = safeAuditConfigurationRevision(previous.failureConfigurationRevision)
            )
        }
        return null
    }

    private suspend fun discover(connection: ToolConnection, configurationRevision: String?, beforeDispatch: suspend () -> Unit): AppAuditObservation {
        recoveryBlock(connection, configurationRevision)?.let { return it }
        val startedAt = System.currentTimeMillis()
        var source: McpDiscoverySource? = null
        var dispatched = false
        return try {
            val context = McpDiscoveryContext(McpDiscoveryTrigger.AUDIT_REFRESH, beforeOwnedDispatch = {
                beforeDispatch()
                dispatched = true
            }, onSource = { source = it })
            val receipt = withContext(context) {
                resolver.discoverMcpToolsWithReceipt(connection, forceRefresh = true, awaitCompletion = true)
            }
            val stableRevision = configurationRevision?.takeIf { it == mcpConfigurationRevision(connection, secretVault) }
            val fresh = receipt.source == McpDiscoverySource.FRESH
            AppAuditObservation(
                if (fresh) AppAuditOutcome.PASS else AppAuditOutcome.UNTESTED,
                when (receipt.source) {
                    McpDiscoverySource.FRESH -> if (receipt.tools.isEmpty()) "MCP_EMPTY_CATALOG" else "MCP_DISCOVERY_VERIFIED"
                    McpDiscoverySource.SHARED -> "MCP_DISCOVERY_SHARED"
                    McpDiscoverySource.CACHED -> "MCP_DISCOVERY_CACHED"
                },
                stage = "mcp_discovery",
                evidenceKind = AppAuditEvidenceKind.LIVE_METADATA,
                observationSource = when (receipt.source) {
                    McpDiscoverySource.FRESH -> AppAuditObservationSource.LIVE
                    McpDiscoverySource.SHARED -> AppAuditObservationSource.SHARED
                    McpDiscoverySource.CACHED -> AppAuditObservationSource.CACHE
                },
                dispatchEvidence = if (fresh) AppAuditDispatchEvidence.CONFIRMED else AppAuditDispatchEvidence.NOT_DISPATCHED,
                metrics = listOf(AppAuditMetric(AppAuditMetricName.TOOL_COUNT, receipt.tools.size.toDouble())),
                observedAtEpochMs = System.currentTimeMillis().takeIf { fresh },
                configurationRevision = stableRevision
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (failure is BenchmarkStorageFailure || failure is AppAuditAdmissionRejected || failure is AppAuditPluginConfigurationChanged || failure is AppAuditPluginReadBlocked) throw failure
            val cached = generateSequence<Throwable>(failure) { it.cause?.takeUnless { cause -> cause === it } }.take(8).any { it is McpBackoffException }
            val health = mcp.healthSnapshot(connection.connectionUid)
            // A new local rejection must not inherit an older server failure's HTTP status,
            // timestamp or revision merely because its connection UID still matches.
            val currentHealth = health.takeIf { cached || (it.lastFailureAtMs ?: -1) >= startedAt } ?: McpConnectionHealth()
            val presentation = mcpFailurePresentation(failure, currentHealth)
            val stableLocalRevision = configurationRevision?.takeIf {
                presentation.phase == "local_validation" && it == mcpConfigurationRevision(connection, secretVault)
            }
            val dispatch = when {
                !dispatched || cached || presentation.dispatchEvidence == "NOT_DISPATCHED" -> AppAuditDispatchEvidence.NOT_DISPATCHED
                presentation.dispatchEvidence == "CONFIRMED" -> AppAuditDispatchEvidence.CONFIRMED
                else -> AppAuditDispatchEvidence.UNKNOWN
            }
            val prerequisiteBlocked = dispatch == AppAuditDispatchEvidence.NOT_DISPATCHED &&
                presentation.errorCode in setOf("MCP_LOCAL_CREDENTIAL_MISSING", "MCP_LOCAL_CREDENTIAL_INVALID", "MCP_LOCAL_CREDENTIAL_REFRESH_REQUIRED")
            AppAuditObservation(
                if (cached || prerequisiteBlocked || !dispatched && source == McpDiscoverySource.SHARED) AppAuditOutcome.BLOCKED else AppAuditOutcome.FAIL,
                presentation.errorCode?.takeIf { it in AppAuditPluginProbeSource.reasonCodes } ?: "MCP_DISCOVERY_UNAVAILABLE",
                stage = "mcp_discovery",
                evidenceKind = AppAuditEvidenceKind.LIVE_METADATA,
                observationSource = when {
                    cached -> AppAuditObservationSource.CACHE
                    !dispatched && source == McpDiscoverySource.SHARED -> AppAuditObservationSource.SHARED
                    dispatch == AppAuditDispatchEvidence.NOT_DISPATCHED -> AppAuditObservationSource.NONE
                    else -> AppAuditObservationSource.LIVE
                },
                dispatchEvidence = dispatch,
                retryable = presentation.retryable,
                httpStatus = presentation.httpStatus,
                failurePhase = safeAuditFailurePhase(presentation.phase),
                failureCategory = safeAuditFailureCategory(presentation.outcome),
                validationRule = safeAuditValidationRule(presentation.validationRule),
                validationField = safeAuditDiagnosticField(presentation.validationField),
                observedAtEpochMs = presentation.observedAtEpochMs?.takeIf { it >= 0 } ?: System.currentTimeMillis().takeUnless { cached },
                // A null transport revision may mean the OAuth attempt crossed credential
                // revisions. Only a proven local rejection can use the stable saved revision.
                configurationRevision = safeAuditConfigurationRevision(presentation.configurationRevision) ?: stableLocalRevision.takeUnless { cached }
            )
        }
    }

    private suspend fun capabilities(profile: PlatformV2, configurationRevision: String?): AppAuditObservation = try {
        val capability = gateway.getCapabilities(
            ProviderRequestConfig(profile.apiUrl, profile.token, profileUid = profile.uid, requestRole = "app-audit"),
            forceRefresh = true
        )
        AppAuditObservation(
            if (capability == null) AppAuditOutcome.UNAVAILABLE else AppAuditOutcome.PASS,
            if (capability == null) "GATEWAY_NOT_SUPPORTED" else "GATEWAY_CAPABILITY_VERIFIED",
            stage = "gateway_capabilities",
            evidenceKind = AppAuditEvidenceKind.LIVE_METADATA,
            observationSource = AppAuditObservationSource.LIVE,
            dispatchEvidence = AppAuditDispatchEvidence.CONFIRMED,
            metrics = capability?.let { listOf(AppAuditMetric(AppAuditMetricName.RESULT_COUNT, 1.0)) }.orEmpty(),
            observedAtEpochMs = System.currentTimeMillis(),
            configurationRevision = configurationRevision,
            remoteIdentity = capability?.let {
                fun buildText(key: String) = (it.build?.get(key) as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull
                safeAuditRemoteIdentity(
                    AppAuditRemoteIdentity(
                        version = it.gatewayVersion ?: it.version,
                        instanceId = it.instanceId,
                        sourceCommit = buildText("sourceCommit"),
                        buildStatus = buildText("status")
                    )
                )
            }
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        val health = LocalServiceHealth.classify(failure)
        val discoveryFailure = failure as? GatewayCapabilityDiscoveryException
        val httpStatus = discoveryFailure?.httpStatus?.takeIf { it in 100..599 }
        val decodeReason = discoveryFailure?.decodeReason?.takeIf { it in AppAuditPluginProbeSource.gatewayDecodeReasons }
        val code = when {
            decodeReason != null -> "GATEWAY_CAPABILITY_DECODE_$decodeReason"
            httpStatus in setOf(401, 403) -> "AUTHENTICATION_FAILED"
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
            dispatchEvidence = if (decodeReason != null || httpStatus != null) AppAuditDispatchEvidence.CONFIRMED else AppAuditDispatchEvidence.UNKNOWN,
            retryable = if (httpStatus in setOf(401, 403) || health in setOf(ConnectionHealth.TLS_FAILED, ConnectionHealth.AUTHENTICATION_FAILED)) false else null,
            httpStatus = httpStatus,
            observedAtEpochMs = System.currentTimeMillis(),
            configurationRevision = configurationRevision
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
        val SAVED_BINDING_ID = Regex("bind_[a-f0-9]{32}")
        val FRESH_CONTRACT_RECHECK_CODES = setOf(
            "MCP_PROTOCOL_VERSION_UNSUPPORTED", "MCP_INITIALIZATION_SCHEMA_INVALID", "MCP_DISCOVERY_SCHEMA_INVALID", "MCP_DISCOVERY_CONFIGURATION",
            "MCP_CATALOG_SCHEMA_INVALID", "MCP_CATALOG_LIMIT_EXCEEDED", "MCP_RESPONSE_ID_MISMATCH", "MCP_RESPONSE_SCHEMA_INVALID", "MCP_ENDPOINT_CONFIGURATION"
        )
    }
}
