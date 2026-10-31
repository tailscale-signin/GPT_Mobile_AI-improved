package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.amazon.AmazonHtmlProvider
import dev.chungjungsoo.gptmobile.data.amazon.SerpApiAmazonClient
import dev.chungjungsoo.gptmobile.data.database.dao.AgentToolBindingWithConnection
import dev.chungjungsoo.gptmobile.data.database.entity.BuiltInAgentTool
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.github.GitHubChatPolicy
import dev.chungjungsoo.gptmobile.data.memory.MemoryGraphRepository
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.model.ToolServiceCatalog
import dev.chungjungsoo.gptmobile.data.model.delegationFor
import dev.chungjungsoo.gptmobile.data.model.isPrivateDestination
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.rag.FactVaultRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class ResolvedAgentTool(
    val tool: AgentTool,
    val connectionUid: String?,
    val connectionName: String?,
    val realToolName: String,
    val modelToolName: String,
    val shareableReadOnly: Boolean = false,
    val canReuseResult: (suspend () -> Boolean)? = null,
    val dynamicTools: (() -> List<ResolvedAgentTool>)? = null,
    val progressiveCatalog: dev.chungjungsoo.gptmobile.data.agent.ProgressiveToolCatalog? = null,
    val nativeGitHub: Boolean = false,
    val nativeGitHubAuthenticated: Boolean = false,
    val nativeMarketplace: Boolean = false
)

class AgentToolResolver @Inject constructor(
    private val toolConnectionRepository: ToolConnectionRepository,
    private val settingRepository: SettingRepository,
    private val secretVault: SecretVault,
    private val networkClient: NetworkClient,
    private val mcpClientManager: McpClientManager,
    private val mcpOAuthCoordinator: McpOAuthCoordinator,
    private val deviceLocationTool: DeviceLocationTool,
    private val factVault: FactVaultRepository? = null,
    private val memoryGraph: MemoryGraphRepository? = null,
    private val memoryDocuments: dev.chungjungsoo.gptmobile.data.knowledge.MemoryDocumentRepository? = null,
    private val freeModelToolConsentStore: dev.chungjungsoo.gptmobile.data.permissions.FreeModelToolConsentStore? = null,
    private val gitHubWorkspaceStore: dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceStore? = null,
    private val nativeMarketplaceRegistry: dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceRegistry? = null,
    private val nativeMarketplaceClient: dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceClient? = null,
    private val amazonFreeProvider: AmazonHtmlProvider? = null,
    private val amazonHistory: dev.chungjungsoo.gptmobile.data.amazon.AmazonHistoryRepository? = null,
    private val amazonAccess: dev.chungjungsoo.gptmobile.data.amazon.AmazonAccessPolicy? = null,
    private val amazonPublicHistory: dev.chungjungsoo.gptmobile.data.amazon.AmazonPublicHistoryProvider? = null,
    private val publicNews: PublicNewsClient? = null,
    private val publicAirbnb: PublicAirbnbClient? = null,
    private val academicClient: dev.chungjungsoo.gptmobile.data.research.academic.AcademicNativeClient? = null,
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val documentContext: android.content.Context? = null,
    private val healthReader: dev.chungjungsoo.gptmobile.data.health.HealthDataReader? = null,
    private val healthConsent: dev.chungjungsoo.gptmobile.data.health.HealthConnectConsentStore? = null,
    private val researchSessions: dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore? = null,
    private val statistics: dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolStatisticsRepository? = null,
    private val researchArchive: dev.chungjungsoo.gptmobile.data.research.feature.ResearchArchiveRepository? = null,
    private val articleExaminations: javax.inject.Provider<dev.chungjungsoo.gptmobile.data.research.feature.ArticleExaminationCoordinator>? = null,
    private val gitHubRepositoryState: dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore? = null,
    private val engineeringController: GitHubEngineeringController? = null,
    private val gitHubPublicAuth: dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthCoordinator? = null,
    private val gitHubCheckWatches: dev.chungjungsoo.gptmobile.data.github.GitHubCheckWatchRepository? = null,
    private val gitHubDecisions: dev.chungjungsoo.gptmobile.data.github.GitHubChatDecisions? = null,
    private val gitHubDecisionNotifications: dev.chungjungsoo.gptmobile.data.github.GitHubDecisionNotifications? = null
) {
    suspend fun warmSelectedCatalogues(profileUid: String, limit: Int = 4) {
        val features = settingRepository.getFeatureSettings()
        val profile = settingRepository.fetchPlatformV2s().firstOrNull { it.uid == profileUid } ?: return
        if (!features.remoteMcpConnections || !profile.enabled || profile.disableAllTools || profile.disableRemoteTools) return
        val connections = toolConnectionRepository.listBindingsWithConnections(profileUid)
            .filter { it.connection?.type == ToolConnectionType.MCP && pluginEnabledForBinding(features, profileUid, it) }
            .mapNotNull { it.connection }.distinctBy { it.connectionUid }.take(limit.coerceIn(0, 64))
        coroutineScope {
            connections.map { connection ->
                async {
                    // This coordinator path only reads or silently refreshes an already installed grant;
                    // missing/revoked OAuth raises a capability error and never opens an authorization UI.
                    if (connection.authType != ToolConnectionAuthType.OAUTH || connection.secretRef != null) {
                        try {
                            discoverMcpTools(connection)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) { }
                    }
                }
            }.awaitAll()
        }
    }

    suspend fun discoverMcpTools(connection: ToolConnection, forceRefresh: Boolean = false, awaitCompletion: Boolean = false): List<Tool> {
        if (!forceRefresh && !awaitCompletion) mcpClientManager.peekToolsForConnection(connection.connectionUid, connection.endpointUrl, connection.allowCleartext)?.let { return it }
        val config = mcpConfig(connection)
        return try {
            mcpClientManager.listTools(config, forceRefresh, awaitCompletion)
        } catch (error: Exception) {
            if (connection.authType != ToolConnectionAuthType.OAUTH || !error.isUnauthorized()) throw error
            mcpClientManager.listTools(
                mcpConfig(
                    connection,
                    forceOAuthRefresh = true,
                    rejectedAuthorizationHeader = config.authorizationHeader
                ),
                forceRefresh = true,
                awaitCompletion = awaitCompletion
            )
        }
    }

    suspend fun resolve(
        profileUid: String,
        chatToolConfig: ChatMcpToolConfig? = null,
        userMessage: MessageV2? = null,
        delegate: (suspend (PlatformV2, String, Int) -> String)? = null,
        onConnectionError: (String) -> Unit = {},
        examinationRunKey: String? = null,
        cachedCataloguesOnly: Boolean = false,
        requestedModel: String? = null
    ): List<ResolvedAgentTool> {
        val platforms = settingRepository.fetchPlatformV2s()
        val featureSettings = GitHubChatPolicy.features(settingRepository.getFeatureSettings(), profileUid, chatToolConfig?.github)
        val platform = platforms.firstOrNull { it.uid == profileUid }?.let { GitHubChatPolicy.platform(it, featureSettings, chatToolConfig?.github) }
        // Keep registered identity checks separate from the actual model receiving tool data.
        val consentPlatform = platform?.copy(model = requestedModel?.trim()?.takeIf(String::isNotBlank) ?: platform.model)

        // If master disableAllTools is toggled, return no tools immediately
        if (platform?.disableAllTools == true) {
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
                "Tools",
                "TOOL_RESOLUTION_BLOCKED · profileType=${platform.compatibleType.name} · reason=profile_disable_all_tools · authorizedTools=0"
            )
            return emptyList()
        }

        val freeProfile = consentPlatform?.let { it.compatibleType == ClientType.FREE || dev.chungjungsoo.gptmobile.data.permissions.ProviderPrivacyPolicy.forProfile(it) != null } == true
        val disableRemote = platform?.disableRemoteTools == true
        val disableLocal = platform?.disableLocalTools == true
        val researchSelection = chatToolConfig?.research ?: dev.chungjungsoo.gptmobile.data.model.MultiSearchResearchSelection(depth = featureSettings.delegationFor(profileUid).strategy)
        fun paperAccess(features: AppFeatureSettings, current: PlatformV2?, selection: dev.chungjungsoo.gptmobile.data.model.MultiSearchResearchSelection): Boolean = if (examinationRunKey == null) {
            dev.chungjungsoo.gptmobile.data.model.academicUnlockReason(selection, features, profileUid, current?.disableRemoteTools == false && !current.disableAllTools) == null
        } else {
            current != null && dev.chungjungsoo.gptmobile.data.research.feature.examinationAdmission(features, current, dev.chungjungsoo.gptmobile.data.research.feature.ExaminationRequest("", profileUid, userMessage?.chatId ?: 0, origin = dev.chungjungsoo.gptmobile.data.research.feature.ExaminationOrigin.USER_EXAMINE), chatToolConfig ?: ChatMcpToolConfig()) == null
        }
        val academicAllowed = paperAccess(featureSettings, platform, researchSelection) && chatToolConfig?.allToolsDisabled != true
        val allowRemoteMcp = !disableRemote && featureSettings.remoteMcpConnections
        val allowDeviceLocation =
            !disableLocal &&
                featureSettings.deviceLocationTool &&
                featureSettings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.DEVICE_LOCATION)
        val connections = toolConnectionRepository.listConnections().filter { connection ->
            featureSettings.isToolPluginEnabledForProfile(profileUid, ToolServiceCatalog.forConnection(connection).id)
        }
        val configuredNativeGitHubConnections = connections.filter { it.type == ToolConnectionType.GITHUB }
        val nativeGitHubConnections = configuredNativeGitHubConnections.filter { connection ->
            featureSettings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.GITHUB) &&
                (chatToolConfig?.github?.connectionUid?.let { it == connection.connectionUid } != false) &&
                featureSettings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.connection(connection.connectionUid))
        }

        // Baseline zero-config tools available out of the box to all models
        val defaultWebSearch = WebSearchTool(
            config = WebSearchProviderConfig(
                provider = WebSearchProvider.AUTO,
                bearerToken = "",
                endpointUrl = "",
                allowLocalSearch = false
            ),
            networkClient = networkClient
        )

        val resolved = mutableListOf<ResolvedAgentTool>()

        val decisionWorkspace = chatToolConfig?.github
        if (!disableLocal && chatToolConfig?.allToolsDisabled != true && platform?.enabled == true && decisionWorkspace?.mainProfileUid == profileUid && userMessage != null && userMessage.chatId > 0 && userMessage.id > 0 && gitHubDecisions != null) {
            val tool = GitHubDecisionTool(
                gitHubDecisions,
                userMessage.chatId,
                userMessage.id,
                profileUid,
                decisionWorkspace,
                persistent = factVault?.scopeForChat(userMessage.chatId)?.isTemporary == false,
                allowed = {
                    val live = settingRepository.getFeatureSettings()
                    val current = settingRepository.fetchPlatformV2s().firstOrNull { it.uid == profileUid }?.let { GitHubChatPolicy.platform(it, live, decisionWorkspace) }
                    val selected = live.conversationGitHub[userMessage.chatId]
                    current?.enabled == true && !current.disableAllTools && !current.disableLocalTools && selected?.sameWorkspace(decisionWorkspace) == true
                },
                offered = { gitHubDecisionNotifications?.offered(it) }
            )
            resolved += ResolvedAgentTool(tool, null, "GitHub decisions", tool.definition.name, tool.definition.name)
        }

        resolved += resolveHealthTools(profileUid, platform, featureSettings, chatToolConfig, disableLocal)

        if (!disableLocal) {
            // Keep explicitly enabled delegation available in small on-device context windows.
            if (
                featureSettings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.MODEL_DELEGATION) &&
                (chatToolConfig?.effectiveDelegation(featureSettings.delegationFor(profileUid)) ?: featureSettings.delegationFor(profileUid)).enabled &&
                delegate != null &&
                platform != null
            ) {
                val tool = ModelDelegationTool(
                    platform,
                    {
                        val defaults = settingRepository.getFeatureSettings().delegationFor(profileUid)
                        chatToolConfig?.effectiveDelegation(defaults) ?: defaults
                    },
                    { settingRepository.fetchPlatformV2s() },
                    generate = delegate,
                    hardware = { documentContext?.let { dev.chungjungsoo.gptmobile.data.localruntime.DeviceHardwareGovernor.inspectHardwareState(it) } ?: dev.chungjungsoo.gptmobile.data.localruntime.DeviceHardwareState() }
                )
                resolved += tool.resolved(null, "Model delegation", tool.definition.name)
            }
            if (
                featureSettings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.LOCAL_MEMORY) &&
                factVault != null &&
                userMessage != null &&
                platform != null
            ) {
                val memoryAvailable = try {
                    factVault.load()
                    factVault.state.value.enabled && !factVault.scopeForChat(userMessage.chatId).isTemporary
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    false
                }
                if (memoryAvailable) {
                    val tool = UnifiedMemoryTool(factVault, memoryGraph, memoryDocuments, userMessage, (consentPlatform ?: platform).isPrivateDestination())
                    resolved += tool.resolved(null, "Memory", tool.definition.name)
                }
            }
            if (featureSettings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.CURRENT_DATE)) {
                resolved += CurrentDateTool(java.time.Clock.system(runCatching { java.time.ZoneId.of(featureSettings.pluginExecution[ToolPluginId.CURRENT_DATE]?.timeZone.orEmpty()) }.getOrDefault(java.time.ZoneId.systemDefault()))).resolved(null, null, BuiltInAgentTool.CURRENT_DATE)
            }
            if (featureSettings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.CALCULATOR)) {
                resolved += CalculatorTool(featureSettings.pluginExecution[ToolPluginId.CALCULATOR]?.decimalPlaces ?: 8).resolved(null, null, BuiltInAgentTool.CALCULATE_EXPRESSION)
            }
            if (featureSettings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.READ_FILES)) {
                resolved += ReadFileSliceTool().resolved(null, null, BuiltInAgentTool.READ_FILE_SLICE)
            }
        }

        if (!disableLocal && amazonHistory != null && amazonAccess != null && platform?.enabled == true && featureSettings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.AMAZON_FREE)) {
            resolved += AmazonLocalTool(profileUid, amazonHistory, amazonAccess, { settingRepository.getFeatureSettings().pluginExecution[ToolPluginId.AMAZON_FREE] ?: PluginExecutionSettings() }, publicHistory = amazonPublicHistory)
                .resolved(null, "Amazon Research Free", "amazon_get_price_history").copy(shareableReadOnly = false)
        }

        resolved += resolveNativeMarketplace(profileUid, platform, featureSettings, chatToolConfig, disableLocal, disableRemote)

        if (!disableRemote) {
            if (publicAirbnb != null && featureSettings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.AIRBNB)) {
                resolved += nativeAirbnb(profileUid).resolved(null, "Airbnb", "airbnb")
                    .copy(shareableReadOnly = true, canReuseResult = { pluginMediaAllowed(profileUid, ToolPluginId.AIRBNB) })
            }
            if (publicNews != null && featureSettings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.NEWS)) {
                val config = (featureSettings.pluginExecution[ToolPluginId.NEWS] ?: PluginExecutionSettings()).normalized()
                resolved += NewsTool(publicNews, { pluginMediaAllowed(profileUid, ToolPluginId.NEWS) }, { config.newsCountry to config.newsLanguage })
                    .resolved(null, "News", "news").copy(shareableReadOnly = true, canReuseResult = { pluginMediaAllowed(profileUid, ToolPluginId.NEWS) })
            }
            if (amazonFreeProvider != null && platform?.enabled == true && featureSettings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.AMAZON_FREE)) {
                resolved += resolveAmazonFree(profileUid, userMessage?.chatId, (featureSettings.pluginExecution[ToolPluginId.AMAZON_FREE] ?: PluginExecutionSettings()).normalized().amazonMarketplace)
            }
            if (featureSettings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.AMAZON_SEARCH)) {
                connections.filter { it.type == ToolConnectionType.AMAZON_SERPAPI && featureSettings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.connection(it.connectionUid)) }
                    .forEach { connection -> resolved += resolveAmazon(connection, featureSettings) }
            }
            if (featureSettings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.READ_URL)) {
                resolved += ReadUrlTool(documentContext = documentContext).resolved(null, null, BuiltInAgentTool.READ_URL)
            }
            // Native GitHub is an integrated plugin. If an authenticated native
            // connection exists but is disabled, do not silently replace it with
            // anonymous GitHub access because that would bypass the plugin toggle.
            if (featureSettings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.GITHUB)) {
                if (configuredNativeGitHubConnections.isEmpty() && chatToolConfig?.github == null) {
                    resolved += resolveGitHub(null, profileUid, userMessage)
                }
                nativeGitHubConnections.forEach { connection -> resolved += resolveGitHub(connection, profileUid, userMessage, chatToolConfig?.github) }
            }
            if (featureSettings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.WEB_SEARCH)) {
                resolved += defaultWebSearch.resolved(null, null, WEB_SEARCH_TOOL)
            }
        }

        val bindings = toolConnectionRepository.listBindingsWithConnections(profileUid)
            .sortedWith(compareBy<AgentToolBindingWithConnection> { it.binding.toolName }.thenBy { it.binding.connectionUid ?: "" }.thenBy { it.binding.bindingUid })
        bindings
            .filterNot { it.connection?.type == ToolConnectionType.MCP }
            .filter { binding -> pluginEnabledForBinding(featureSettings, profileUid, binding) }
            .distinctBy { if (it.binding.toolName == WEB_SEARCH_TOOL) "${it.binding.toolName}:${it.binding.connectionUid}" else it.binding.toolName }
            .forEach { binding ->
                val isRemoteBinding = binding.binding.toolName in setOf(WEB_SEARCH_TOOL, BuiltInAgentTool.READ_URL, BuiltInAgentTool.GITHUB)
                val isLocalBinding = !isRemoteBinding
                if ((isRemoteBinding && !disableRemote) || (isLocalBinding && !disableLocal)) {
                    if (binding.binding.toolName == BuiltInAgentTool.DEVICE_LOCATION && !allowDeviceLocation) {
                        return@forEach
                    }
                    resolveBinding(binding, featureSettings, profileUid, userMessage)?.let { customResolvedTool ->
                        resolved.removeAll { it.modelToolName == customResolvedTool.modelToolName }
                        resolved += customResolvedTool
                    }
                }
            }

        if (allowRemoteMcp) {
            val mcpGroups = bindings
                .filter { it.connection?.type == ToolConnectionType.MCP }
                .filter { examinationRunKey == null || it.connection?.connectionUid == researchSelection.gatewayConnectionUid }
                .filter { pluginEnabledForBinding(featureSettings, profileUid, it) }
                .filter { it.connection?.alias != "deep_research" || academicAllowed && it.connection?.connectionUid == researchSelection.gatewayConnectionUid }
                .groupBy { requireNotNull(it.connection).connectionUid }
                .toSortedMap()
                .values
                .toList()
            val mcpResults = coroutineScope {
                mcpGroups.map { mcpBindings ->
                    async {
                        val connection = requireNotNull(mcpBindings.first().connection)
                        try {
                            resolveMcpTools(connection, mcpBindings, featureSettings, profileUid, cachedCataloguesOnly, userMessage) to null
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            val outcome = when {
                                error is McpBackoffException -> "WAITING_RECOVERY"
                                generateSequence<Throwable>(error) { current -> current.cause?.takeUnless { cause -> cause === current } }.take(8).any { it is McpAuthenticationRequiredException } -> "AUTHENTICATION_REQUIRED"
                                generateSequence<Throwable>(error) { current -> current.cause?.takeUnless { cause -> cause === current } }.take(8).any { it is McpEndpointConfigurationException } -> "ENDPOINT_CONFIGURATION"
                                generateSequence<Throwable>(error) { current -> current.cause?.takeUnless { cause -> cause === current } }.take(8).any { it is java.io.IOException } -> "NETWORK_UNAVAILABLE"
                                error is IllegalStateException -> "MCP_STATE_OR_CATALOG_INVALID"
                                else -> "DISCOVERY_FAILED"
                            }
                            val event = if (outcome == "WAITING_RECOVERY") "TOOL_RESOLUTION_WAITING_RECOVERY" else "TOOL_RESOLUTION_FAILED"
                            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("ToolCatalog", "$event · connection=${connection.connectionUid} · outcome=$outcome · reason=${error.javaClass.simpleName} · cachedOnly=$cachedCataloguesOnly · health=${mcpClientManager.healthSnapshot(connection.connectionUid).state}", if (outcome == "WAITING_RECOVERY") "I" else "W")
                            val message = when (outcome) {
                                "WAITING_RECOVERY" -> "${connection.name}: temporarily waiting for the MCP connection backoff to expire. The tool catalogue is retained and can be refreshed when recovery is available."
                                "AUTHENTICATION_REQUIRED" -> "${connection.name}: authentication is required. Reconnect or update this server's credentials."
                                "ENDPOINT_CONFIGURATION" -> "${connection.name}: the MCP endpoint path or server configuration is invalid. Check the configured URL and reconnect."
                                "NETWORK_UNAVAILABLE" -> "${connection.name}: network access to this MCP server is unavailable. The saved catalogue is retained for retry after connectivity returns."
                                "MCP_STATE_OR_CATALOG_INVALID" -> "${connection.name}: the MCP connection or tool catalogue changed during discovery. Refresh the catalogue after the connection settles."
                                else -> "${connection.name}: tool discovery failed. Check connection diagnostics and retry catalogue discovery."
                            }
                            emptyList<ResolvedAgentTool>() to message
                        }
                    }
                }.awaitAll()
            }
            mcpResults.forEach { (tools, error) ->
                resolved += tools
                error?.let(onConnectionError)
            }
        }

        // Expand native contracts before telemetry replaces the concrete backend
        // with ObservedTool. Otherwise the model only gets the hidden legacy tool.
        val hasGitHubRepositoryContext = hasSelectedGitHubRepository(resolved)
        val expandedGitHub = expandNativeGitHubTools(resolved)
        resolved.clear()
        resolved.addAll(expandedGitHub)
        if (statistics != null) {
            for (index in resolved.indices) {
                val item = resolved[index]
                resolved[index] = item.copy(tool = observedChild(item.tool, item.realToolName, item.connectionUid))
            }
        }

        val academicRemote = resolved.filter { tool -> DeepResearchTool.OPERATIONS.any { operation -> tool.realToolName == "deep_research_$operation" || tool.realToolName == "academic__deep_research_$operation" } }
        resolved.removeAll(academicRemote.toSet())
        if (academicAllowed && academicClient != null) {
            val access: suspend () -> Boolean = {
                val current = settingRepository.fetchPlatformV2s().firstOrNull { it.uid == profileUid }
                val features = settingRepository.getFeatureSettings()
                val selection = userMessage?.chatId?.let { features.conversationResearch[it] } ?: chatToolConfig?.research ?: researchSelection
                selection == researchSelection &&
                    current?.enabled == true &&
                    !current.disableAllTools &&
                    paperAccess(features, current, selection)
            }
            val persistentAcademicEvidence = userMessage?.chatId?.let { factVault?.scopeForChat(it)?.isTemporary == false } == true
            val academicPlan = dev.chungjungsoo.gptmobile.data.model.effectiveResearchPlan(featureSettings, profileUid, researchSelection)
            val academicOptions = academicPlan.settings
            val sharedTurnBudget = userMessage?.let { message ->
                researchSessions?.journal("academic:${examinationRunKey ?: "${message.chatId}:${message.id}"}", message.chatId, persistentAcademicEvidence, examinationRunKey ?: "${message.chatId}:${message.id}")?.goalBudget(academicPlan)
            }
            val academicBudget = AcademicRunBudget(shared = sharedTurnBudget ?: dev.chungjungsoo.gptmobile.data.agent.ResearchBudget(1, ledger = dev.chungjungsoo.gptmobile.data.agent.ResearchRunLedger(academicPlan.fingerprint, academicPlan.limits)), goalDriven = true)
            DeepResearchTool.OPERATIONS.forEach { operation ->
                val remote = academicRemote.singleOrNull {
                    it.connectionUid == researchSelection.gatewayConnectionUid && it.shareableReadOnly && it.realToolName.endsWith("deep_research_$operation")
                }
                val liveAccess: suspend () -> Boolean = {
                    access() &&
                        (
                            remote == null ||
                                (
                                    settingRepository.getFeatureSettings().remoteMcpConnections &&
                                        toolConnectionRepository.getConnection(remote.connectionUid!!)?.let { current ->
                                            current.authType != ToolConnectionAuthType.NONE &&
                                                remote.realToolName in current.approvedReadTools.lines().map(String::trim) &&
                                                toolConnectionRepository.listBindingsWithConnections(profileUid).any { it.binding.connectionUid == remote.connectionUid && it.binding.toolName == remote.realToolName }
                                        } == true
                                    )
                            )
                }
                val facade = DeepResearchTool(
                    operation,
                    academicClient,
                    researchSelection,
                    academicOptions,
                    liveAccess,
                    remote?.tool,
                    academicBudget,
                    persistentEvidence = persistentAcademicEvidence,
                    peerReviewedOnly = featureSettings.researchFeature.peerReviewedOnly,
                    runKey = examinationRunKey ?: userMessage?.let { "${it.chatId}:${it.id}" }.orEmpty(),
                    remoteCapability = {
                        val statusTool = academicRemote.singleOrNull { it.connectionUid == researchSelection.gatewayConnectionUid && it.realToolName.endsWith("deep_research_status") && it.shareableReadOnly }
                        val statusAllowed = statusTool?.connectionUid?.let { uid ->
                            toolConnectionRepository.getConnection(uid)?.let { connection ->
                                statusTool.realToolName in connection.approvedReadTools.lines().map(String::trim) &&
                                    toolConnectionRepository.listBindingsWithConnections(profileUid).any { it.binding.connectionUid == uid && it.binding.toolName == statusTool.realToolName }
                            }
                        } == true
                        if (statusTool == null || !statusAllowed || !liveAccess()) {
                            false
                        } else {
                            val result = statusTool.tool.execute("research-capability", JsonObject(emptyMap()))
                            val payload = (result.content as? dev.chungjungsoo.gptmobile.data.agent.ToolResultContent.Json)?.value as? JsonObject
                                ?: (result.content as? dev.chungjungsoo.gptmobile.data.agent.ToolResultContent.Text)?.text?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                            !result.isError && (payload?.get("researchPolicyVersions") as? kotlinx.serialization.json.JsonArray)?.any { it.toString() == "3" } == true
                        }
                    }
                )
                resolved += ResolvedAgentTool(facade, remote?.connectionUid, "Deep Research", remote?.realToolName ?: facade.definition.name, facade.definition.name, shareableReadOnly = false, canReuseResult = access)
            }
        }

        if (researchArchive != null && articleExaminations != null && platform?.enabled == true && !disableLocal && featureSettings.researchFeature.enabled && featureSettings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.DEEP_RESEARCH)) {
            val sourceChatId = userMessage?.chatId ?: 0
            val persistentResearch = sourceChatId == 0 || factVault?.scopeForChat(sourceChatId)?.isTemporary == false
            if (persistentResearch) {
                dev.chungjungsoo.gptmobile.data.research.feature.ResearchEvidenceTool.OPERATIONS.filter { it != "examine_article" || academicAllowed }.forEach { operation ->
                    val access: suspend () -> Boolean = {
                        val current = settingRepository.fetchPlatformV2s().firstOrNull { it.uid == profileUid }
                        val features = settingRepository.getFeatureSettings()
                        current != null &&
                            platform != null &&
                            dev.chungjungsoo.gptmobile.data.agent.sameRecoveryDestination(platform, current) &&
                            current.enabled &&
                            !current.disableAllTools &&
                            !current.disableLocalTools &&
                            features.researchFeature.enabled &&
                            features.isToolPluginEnabledForProfile(profileUid, ToolPluginId.DEEP_RESEARCH) &&
                            (chatToolConfig?.isToolEnabled(listOf("research_$operation", "deep_research")) != false) &&
                            (sourceChatId == 0 || factVault?.scopeForChat(sourceChatId)?.isTemporary == false)
                    }
                    val tool = dev.chungjungsoo.gptmobile.data.research.feature.ResearchEvidenceTool(operation, researchArchive, { articleExaminations.get() }, profileUid, sourceChatId, "public", chatToolConfig ?: ChatMcpToolConfig(), access, featureSettings.researchFeature.comparisonLimit, userMessage?.let { "${it.chatId}:${it.id}" })
                    resolved += ResolvedAgentTool(tool, null, "Research", tool.definition.name, tool.definition.name, shareableReadOnly = operation != "examine_article", canReuseResult = access)
                }
            }
        }

        val finalized = finalizeResolvedTools(resolved, profileUid, platform, featureSettings, bindings, chatToolConfig, freeProfile, disableRemote, disableLocal, onConnectionError, chatId = userMessage?.chatId, consentPlatform = consentPlatform)
        val catalogTask = githubCatalogTask(profileUid, scopedGitHubMessage(userMessage, chatToolConfig?.github), hasGitHubRepositoryContext)
        val coldBindings = if (cachedCataloguesOnly && allowRemoteMcp && userMessage != null) {
            bindings.filter { binding ->
                binding.connection?.type == ToolConnectionType.MCP &&
                    pluginEnabledForBinding(featureSettings, profileUid, binding) &&
                    finalized.none { it.connectionUid == binding.binding.connectionUid && it.realToolName == binding.binding.toolName } &&
                    chatToolConfig?.isToolEnabled(listOfNotNull(binding.binding.connectionUid, binding.binding.toolName, binding.binding.connectionUid?.let { "$it:${binding.binding.toolName}" })) != false
            }
        } else {
            emptyList()
        }
        if (coldBindings.isEmpty()) {
            if (finalized.none { it.nativeGitHub }) return attachCrawlers(finalized, profileUid, platform, featureSettings, userMessage, chatToolConfig, consentPlatform)
            val directory = dev.chungjungsoo.gptmobile.data.agent.ProgressiveToolCatalog(finalized.map { it.tool.definition }, catalogTask)
            return attachCrawlers(finalized, profileUid, platform, featureSettings, userMessage, chatToolConfig, consentPlatform) + ResolvedAgentTool(directory, null, "Authorized tool directory", directory.definition.name, directory.definition.name, progressiveCatalog = directory)
        }
        val discovered = java.util.concurrent.ConcurrentHashMap<String, ResolvedAgentTool>()
        val attemptedConnections = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val directory = dev.chungjungsoo.gptmobile.data.agent.ProgressiveToolCatalog(finalized.map { it.tool.definition }, catalogTask, acquireCold = { query, names ->
            val current = settingRepository.fetchPlatformV2s().firstOrNull { it.uid == profileUid }
            check(current == platform && current?.enabled == true) { "Selected profile changed; retry discovery." }
            val live = settingRepository.getFeatureSettings()
            check(live.remoteMcpConnections && !current.disableAllTools && !current.disableRemoteTools) { "Remote tools are disabled." }
            val currentBindings = toolConnectionRepository.listBindingsWithConnections(profileUid)
            val words = Regex("[\\p{L}\\p{N}]+").findAll(query.lowercase(java.util.Locale.ROOT)).map { it.value }.filter { it.length > 2 }.toSet() + if (isGitHubTask(query)) setOf("github", "git") else emptySet()
            val targets = coldBindings.filter { it in currentBindings && pluginEnabledForBinding(live, profileUid, it) }
                .groupBy { it.binding.connectionUid }
                .values.filter { group -> group.first().binding.connectionUid !in attemptedConnections || group.any { it.binding.toolName in names || names.any { name -> name.endsWith("__" + it.binding.toolName) } } }
                .sortedByDescending { group ->
                    group.count { item -> item.binding.toolName in names || names.any { it.endsWith("__" + item.binding.toolName) } } * 100 +
                        words.count { word -> group.any { word in (it.binding.toolName + " " + it.connection?.name).lowercase(java.util.Locale.ROOT) } }
                }.take(4)
            val loaded = coroutineScope {
                targets.map { group ->
                    async {
                        val connection = requireNotNull(group.first().connection)
                        try {
                            resolveMcpTools(connection, group, live, profileUid, userMessage = userMessage)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            onConnectionError("${connection.name}: discovery unavailable; other authorized tools remain available.")
                            emptyList()
                        } finally {
                            attemptedConnections.add(connection.connectionUid)
                        }
                    }
                }.awaitAll().flatten()
            }
            // Recheck after I/O. Old grants/credentials cannot publish tools to a newer turn authority.
            check(settingRepository.fetchPlatformV2s().firstOrNull { it.uid == profileUid } == platform && settingRepository.getFeatureSettings() == live && toolConnectionRepository.listBindingsWithConnections(profileUid) == currentBindings) { "Tool permissions changed during discovery." }
            val admitted = finalizeResolvedTools(loaded, profileUid, platform, live, currentBindings, chatToolConfig, freeProfile, disableRemote, disableLocal, onConnectionError, finalized.filter { it.nativeGitHub }, chatId = userMessage?.chatId, consentPlatform = consentPlatform)
            admitted.forEach { next ->
                val previous = finalized.firstOrNull { it.modelToolName == next.modelToolName } ?: discovered[next.modelToolName]
                check(previous == null || previous.connectionUid == next.connectionUid && previous.realToolName == next.realToolName) { "Ambiguous discovered tool alias; no action dispatched." }
                discovered[next.modelToolName] = next
            }
            aggregateWebSearch(finalized + discovered.values).map { it.tool.definition }
        })
        return attachCrawlers(finalized, profileUid, platform, featureSettings, userMessage, chatToolConfig, consentPlatform) + ResolvedAgentTool(directory, null, "Authorized tool directory", directory.definition.name, directory.definition.name, dynamicTools = { finalized + discovered.values }, progressiveCatalog = directory)
    }

    /** Keep the run-scoped reader and its live permission checks out of the resolution coroutine. */
    private fun resolveHealthTools(
        profileUid: String,
        platform: PlatformV2?,
        featureSettings: AppFeatureSettings,
        chatToolConfig: ChatMcpToolConfig?,
        disableLocal: Boolean
    ): List<ResolvedAgentTool> {
        if (disableLocal ||
            platform?.enabled != true ||
            healthReader == null ||
            healthConsent == null ||
            !featureSettings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.HEALTH_CONNECT)
        ) {
            return emptyList()
        }
        val runHealthReader = dev.chungjungsoo.gptmobile.data.health.RunScopedHealthReader(healthReader)
        return dev.chungjungsoo.gptmobile.data.health.HealthConnectTools.operations.keys.map { operation ->
            val tool = HealthConnectTool(
                operation,
                runHealthReader,
                accessAllowed = {
                    val current = settingRepository.fetchPlatformV2s().firstOrNull { it.uid == profileUid }
                    current != null &&
                        dev.chungjungsoo.gptmobile.data.agent.sameRecoveryDestination(platform, current) &&
                        current.enabled &&
                        !current.disableAllTools &&
                        !current.disableLocalTools &&
                        chatToolConfig?.allToolsDisabled != true &&
                        settingRepository.getFeatureSettings().isToolPluginEnabledForProfile(profileUid, ToolPluginId.HEALTH_CONNECT)
                },
                aiConsent = healthConsent::isAllowed
            )
            tool.resolved(null, "Smartwatch Health Connect", tool.definition.name).copy(shareableReadOnly = false)
        }
    }

    private suspend fun attachCrawlers(tools: List<ResolvedAgentTool>, profileUid: String, platform: PlatformV2?, features: AppFeatureSettings, message: MessageV2?, chat: ChatMcpToolConfig?, consentPlatform: PlatformV2? = platform): List<ResolvedAgentTool> {
        val context = documentContext ?: return tools
        if (message == null || platform == null || platform.disableAllTools || platform.disableRemoteTools || chat?.allToolsDisabled == true || chat?.isToolEnabled(listOf("crawler", ToolPluginId.CRAWLERS)) == false || !features.isToolPluginEnabledForProfile(profileUid, ToolPluginId.CRAWLERS)) return tools
        val scope = dev.chungjungsoo.gptmobile.data.crawl.CrawlScope(profileUid, message.chatId, "${message.chatId}:${message.id}:${message.createdAt}", dev.chungjungsoo.gptmobile.data.crawl.crawlHash(features.toString() + platform.toString()), factVault?.scopeForChat(message.chatId)?.isTemporary == false)
        val attached = dev.chungjungsoo.gptmobile.data.crawl.CrawlerIntegration.attach(context, scope, features, tools) {
            settingRepository.fetchPlatformV2s().firstOrNull { it.uid == profileUid } == platform && settingRepository.getFeatureSettings() == features
        }
        // Attachment replaces the native reader with a cache and introduces direct engines.
        // Guard the resulting surface too, before a derived URL reaches any of them.
        return attached.map { resolved ->
            if (resolved.connectionUid == null && resolved.realToolName in setOf("read_url", "crawler", "jina_crawler_fetch")) {
                resolved.copy(tool = dev.chungjungsoo.gptmobile.data.permissions.ProvenanceGuardedTool(resolved.tool, consentPlatform ?: platform, resolved.realToolName, personal = false, external = true) { freeModelToolConsentStore?.grantFor(consentPlatform ?: platform, resolved.realToolName, message.chatId) })
            } else {
                resolved
            }
        }
    }

    /** Keep registry I/O and native adapter construction out of the main resolver coroutine. */
    private suspend fun resolveNativeMarketplace(
        profileUid: String,
        platform: PlatformV2?,
        featureSettings: AppFeatureSettings,
        chatToolConfig: ChatMcpToolConfig?,
        disableLocal: Boolean,
        disableRemote: Boolean
    ): List<ResolvedAgentTool> {
        val resolved = mutableListOf<ResolvedAgentTool>()
        if (!disableLocal && nativeMarketplaceRegistry != null) {
            val entry = requireNotNull(dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplaceCatalog.find("optional-universal-marketplace"))
            val service = ToolServiceCatalog.forPackage(entry).id
            val operation = ToolPluginId.nativeOperation(entry.id, "search")
            val ids = listOf(entry.id, "${entry.id}:search", "universal_marketplace__search", "search")
            val allowed: suspend () -> Boolean = {
                val current = settingRepository.fetchPlatformV2s().firstOrNull { it.uid == profileUid }
                val live = settingRepository.getFeatureSettings()
                current?.enabled == true &&
                    !current.disableAllTools &&
                    !current.disableLocalTools &&
                    listOf(entry.id, service, operation).all { live.isToolPluginEnabledForProfile(profileUid, it) } &&
                    chatToolConfig?.isToolEnabled(ids) != false &&
                    nativeMarketplaceRegistry.load()[entry.id]?.enabled == true
            }
            if (allowed()) {
                val tool = UniversalMarketplaceTool({ nativeMarketplaceRegistry.configuration(entry).installation.universalMarketplace }, allowed)
                resolved += ResolvedAgentTool(tool, entry.id, entry.preset.name, "search", tool.definition.name, shareableReadOnly = false, canReuseResult = allowed)
            }
        }

        if (!disableRemote) {
            if (nativeMarketplaceRegistry != null && nativeMarketplaceClient != null) {
                val nativeInstallations = try {
                    nativeMarketplaceRegistry.load()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyMap()
                }
                nativeInstallations.filterValues { it.enabled }.forEach { (id, installation) ->
                    val entry = dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplaceCatalog.find(id) ?: return@forEach
                    if (entry.provider != "universal-marketplace" &&
                        installation.ready(entry) &&
                        featureSettings.isToolPluginEnabledForProfile(profileUid, id) &&
                        featureSettings.isToolPluginEnabledForProfile(profileUid, ToolServiceCatalog.forPackage(entry).id)
                    ) {
                        dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceCatalog.definitions(entry).filter { definition ->
                            featureSettings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.nativeOperation(id, definition.name.substringAfterLast("__"))) &&
                                (entry.provider != "openstreetmap" || (definition.name.substringAfterLast("__") !in installation.disabledOperations && dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceCatalog.validEndpoint(installation.endpoints[definition.name.substringAfterLast("__")].orEmpty())))
                        }.forEach { definition ->
                            resolved += ResolvedAgentTool(
                                tool = NativeMarketplaceTool(entry, definition, nativeMarketplaceRegistry, nativeMarketplaceClient::fetch),
                                connectionUid = id,
                                connectionName = entry.preset.name,
                                realToolName = definition.name.substringAfterLast("__"),
                                modelToolName = definition.name,
                                shareableReadOnly = true,
                                nativeMarketplace = true,
                                canReuseResult = nativeMarketplaceRecoveryAccess(profileUid, platform, entry, definition.name.substringAfterLast("__"), installation, nativeMarketplaceRegistry, settingRepository)
                            )
                        }
                    }
                }
            }
        }
        return resolved
    }

    /** Catalog normalization has its own method instead of inflating the resolution coroutine. */
    private fun finalizeResolvedTools(
        resolved: List<ResolvedAgentTool>,
        profileUid: String,
        platform: PlatformV2?,
        featureSettings: AppFeatureSettings,
        bindings: List<AgentToolBindingWithConnection>,
        chatToolConfig: ChatMcpToolConfig?,
        freeProfile: Boolean,
        disableRemote: Boolean,
        disableLocal: Boolean,
        onConnectionError: (String) -> Unit,
        nativeGitHubAlternatives: List<ResolvedAgentTool> = emptyList(),
        chatId: Int? = null,
        consentPlatform: PlatformV2? = platform
    ): List<ResolvedAgentTool> {
        fun implicitlyPublic(tool: ResolvedAgentTool): Boolean {
            if (tool.nativeGitHub) return !tool.nativeGitHubAuthenticated
            if (tool.connectionUid == null) return !dev.chungjungsoo.gptmobile.data.permissions.FreeToolDataPolicy.requiresPersonalPermission(tool.realToolName)
            // A remote schema or a tool named "web_search" is not a public-data grant.
            return tool.isWebSearchEngine() &&
                bindings.any { binding ->
                    binding.binding.connectionUid == tool.connectionUid && binding.connection?.type in ToolConnectionType.WEB_SEARCH_TYPES
                }
        }
        val collisions = resolved.groupBy { it.modelToolName }.filterValues { group -> group.map { it.connectionUid to it.realToolName }.distinct().size > 1 }.keys
        if (collisions.isNotEmpty()) onConnectionError("Ambiguous tool aliases excluded: " + collisions.sorted().joinToString(", "))
        val callable = resolved.filterForToolPolicy("AMBIGUOUS_MODEL_ALIAS") { it.modelToolName !in collisions }.distinctBy { it.modelToolName }
        if (callable.isEmpty()) {
            val reason = when {
                platform == null -> "profile_missing"
                !platform.enabled -> "profile_disabled"
                chatToolConfig?.allToolsDisabled == true -> "chat_tools_disabled"
                disableRemote && disableLocal -> "profile_local_and_remote_tools_disabled"
                disableRemote -> "profile_remote_tools_disabled_or_no_local_tools_available"
                disableLocal -> "profile_local_tools_disabled_or_no_remote_tools_available"
                else -> "no_enabled_or_available_tools"
            }
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
                "Tools",
                "TOOL_RESOLUTION_EMPTY · profileType=${platform?.compatibleType?.name ?: "UNKNOWN"} · reason=$reason · authorizedTools=0"
            )
        }
        val authorized = callable
            .filterForToolPolicy("PLUGIN_DISABLED") { tool ->
                if (tool.modelToolName in AmazonNativeTool.names + AmazonLocalTool.names) {
                    featureSettings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.AMAZON_FREE)
                } else {
                    !tool.isAmazonProductTool() || featureSettings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.AMAZON_SEARCH)
                }
            }
            .filterForToolPolicy("FREE_PROFILE_CONSENT_REQUIRED") { tool ->
                val publicTool = implicitlyPublic(tool)
                !freeProfile ||
                    publicTool ||
                    consentPlatform != null &&
                    freeModelToolConsentStore?.isGranted(consentPlatform, tool.connectionUid?.let { "$it:${tool.realToolName}" } ?: tool.realToolName, chatId) == true
            }
            .filterForToolPolicy("CHAT_TOOL_SELECTION") { tool ->
                if (chatToolConfig == null) {
                    true
                } else {
                    val candidateIds = listOfNotNull(
                        tool.connectionUid?.let { "$it:${tool.realToolName}" },
                        tool.modelToolName,
                        tool.realToolName,
                        tool.connectionUid,
                        "web_search".takeIf { tool.isWebSearchEngine() },
                        "github".takeIf { tool.nativeGitHub },
                        tool.connectionUid?.let { "$it:github" }.takeIf { tool.nativeGitHub }
                    )
                    val legacyIds = if (tool.connectionUid == "optional-openstreetmap") {
                        when (tool.realToolName) {
                            "geocode" -> listOf("optional-nominatim", "optional-nominatim:geocode", "places_nominatim__geocode")
                            "restrooms" -> listOf("optional-overpass", "optional-overpass:restrooms", "places_overpass__restrooms")
                            else -> emptyList()
                        }
                    } else {
                        emptyList()
                    }
                    chatToolConfig.isToolEnabled(candidateIds + legacyIds)
                }
            }
            .map { resolved ->
                // Native Amazon reads load their current settings and check live permissions themselves.
                if (resolved.modelToolName in AmazonNativeTool.names + AmazonLocalTool.names) return@map resolved
                val id = resolved.connectionUid?.let(ToolPluginId::connection) ?: when (resolved.realToolName) {
                    "current_date" -> ToolPluginId.CURRENT_DATE
                    "calculate_expression" -> ToolPluginId.CALCULATOR
                    "read_file_slice" -> ToolPluginId.READ_FILES
                    "read_url" -> ToolPluginId.READ_URL
                    "device_location" -> ToolPluginId.DEVICE_LOCATION
                    "web_search" -> ToolPluginId.WEB_SEARCH
                    "news" -> ToolPluginId.NEWS
                    "airbnb" -> ToolPluginId.AIRBNB
                    "github" -> ToolPluginId.GITHUB
                    else -> if (resolved.realToolName.startsWith("deep_research_")) ToolPluginId.DEEP_RESEARCH else ""
                }
                val fallback = when {
                    resolved.isWebSearchEngine() -> ToolPluginId.WEB_SEARCH
                    resolved.isAmazonProductTool() -> ToolPluginId.AMAZON_SEARCH
                    resolved.nativeGitHub -> ToolPluginId.GITHUB
                    else -> id
                }
                (featureSettings.pluginExecution[id] ?: featureSettings.pluginExecution[fallback])?.let { config -> resolved.copy(tool = ConfiguredPluginTool(resolved.tool, config)) } ?: resolved
            }
            .map { resolved ->
                if (resolved.nativeMarketplace) return@map resolved
                if (resolved.isWebSearchEngine()) {
                    val snapshot = bindings.filter { it.binding.connectionUid == resolved.connectionUid && it.binding.toolName == resolved.realToolName }
                    val previousCheck = resolved.canReuseResult
                    return@map resolved.copy(canReuseResult = {
                        val features = GitHubChatPolicy.features(settingRepository.getFeatureSettings(), profileUid, chatToolConfig?.github)
                        val current = settingRepository.fetchPlatformV2s().firstOrNull { it.uid == profileUid }?.let { GitHubChatPolicy.platform(it, features, chatToolConfig?.github) }
                        current != null &&
                            platform != null &&
                            dev.chungjungsoo.gptmobile.data.agent.sameRecoveryDestination(platform, current) &&
                            current.enabled &&
                            !current.disableAllTools &&
                            !current.disableRemoteTools &&
                            previousCheck?.invoke() != false &&
                            (
                                resolved.connectionUid == null &&
                                    features.isToolPluginEnabledForProfile(profileUid, ToolPluginId.WEB_SEARCH) ||
                                    (
                                        snapshot.isNotEmpty() &&
                                            snapshot.all { pluginEnabledForBinding(features, profileUid, it) } &&
                                            recoveryBindingGrants(toolConnectionRepository.listBindingsWithConnections(profileUid).filter { it.binding.connectionUid == resolved.connectionUid && it.binding.toolName == resolved.realToolName }) == recoveryBindingGrants(snapshot) &&
                                            snapshot.all { it.connection?.type != ToolConnectionType.MCP || resolved.realToolName in it.connection?.approvedReadTools.orEmpty().lines().map(String::trim) }
                                        )
                                )
                    })
                }
                if (!resolved.isAmazonProductTool()) return@map resolved
                val plugin = if (resolved.modelToolName in AmazonNativeTool.names) ToolPluginId.AMAZON_FREE else ToolPluginId.AMAZON_SEARCH
                resolved.copy(canReuseResult = {
                    val current = settingRepository.fetchPlatformV2s().firstOrNull { it.uid == profileUid }?.let { GitHubChatPolicy.platform(it, settingRepository.getFeatureSettings(), chatToolConfig?.github) }
                    val features = settingRepository.getFeatureSettings()
                    current?.enabled == true &&
                        !current.disableAllTools &&
                        !current.disableRemoteTools &&
                        features.isToolPluginEnabledForProfile(profileUid, plugin) &&
                        (resolved.connectionUid == null || features.isToolPluginEnabledForProfile(profileUid, ToolPluginId.connection(resolved.connectionUid)))
                })
            }
            .map { resolved ->
                val connection = resolved.connectionUid ?: return@map resolved
                if (connection == "optional-universal-marketplace" || resolved.nativeGitHub || resolved.nativeMarketplace) return@map resolved
                val admittedBindings = bindings.filter { it.binding.connectionUid == connection }.let { rows -> rows.filter { it.binding.toolName in setOf(resolved.realToolName, "*") }.ifEmpty { rows } }
                val admittedNames = admittedBindings.map { it.binding.toolName }.toSet()
                val previousCheck = resolved.canReuseResult
                resolved.copy(canReuseResult = {
                    val current = settingRepository.fetchPlatformV2s().firstOrNull { it.uid == profileUid }?.let { GitHubChatPolicy.platform(it, settingRepository.getFeatureSettings(), chatToolConfig?.github) }
                    current != null &&
                        platform != null &&
                        dev.chungjungsoo.gptmobile.data.agent.sameRecoveryDestination(platform, current) &&
                        current?.enabled == true &&
                        admittedBindings.isNotEmpty() &&
                        recoveryBindingGrants(toolConnectionRepository.listBindingsWithConnections(profileUid).filter { it.binding.connectionUid == connection && it.binding.toolName in admittedNames }) == recoveryBindingGrants(admittedBindings) &&
                        settingRepository.getFeatureSettings().isToolPluginEnabledForProfile(profileUid, ToolPluginId.connection(connection)) &&
                        previousCheck?.invoke() != false
                })
            }
            .map { resolved ->
                if (!freeProfile ||
                    platform == null ||
                    consentPlatform == null ||
                    implicitlyPublic(resolved)
                ) {
                    return@map resolved
                }
                val id = resolved.connectionUid?.let { "$it:${resolved.realToolName}" } ?: resolved.realToolName
                val previous = resolved.canReuseResult
                val permitted: suspend () -> Boolean = {
                    val current = settingRepository.fetchPlatformV2s().firstOrNull { it.uid == profileUid }?.let { GitHubChatPolicy.platform(it, settingRepository.getFeatureSettings(), chatToolConfig?.github) }
                    current != null &&
                        current.enabled &&
                        dev.chungjungsoo.gptmobile.data.agent.sameRecoveryDestination(platform, current) &&
                        freeModelToolConsentStore?.isGranted(current.copy(model = consentPlatform.model), id, chatId) == true
                }
                resolved.copy(
                    tool = dev.chungjungsoo.gptmobile.data.permissions.PersonalDataGuardedTool(resolved.tool, permitted, grantLookup = { freeModelToolConsentStore?.grantFor(consentPlatform, id, chatId) }),
                    canReuseResult = { permitted() && previous?.invoke() != false }
                )
            }
            .map { resolved ->
                if (consentPlatform == null) return@map resolved
                val id = resolved.connectionUid?.let { "$it:${resolved.realToolName}" } ?: resolved.realToolName
                val control = resolved.realToolName in setOf("delegate_to_model", "read_recovery_context", "discover_tools")
                val personal = !control && !implicitlyPublic(resolved)
                val external = !control && (resolved.connectionUid != null || resolved.nativeGitHub || resolved.nativeMarketplace || resolved.tool.definition.capabilities.any { it in setOf(dev.chungjungsoo.gptmobile.data.agent.AgentToolCapability.WEB_SEARCH, dev.chungjungsoo.gptmobile.data.agent.AgentToolCapability.PAGE_READER) } || resolved.realToolName in setOf("web_search", "read_url", "news", "deep_research_search_papers", "deep_research_get_paper", "deep_research_read_paper", "deep_research_citations"))
                resolved.copy(tool = dev.chungjungsoo.gptmobile.data.permissions.ProvenanceGuardedTool(resolved.tool, consentPlatform, id, personal, external) { freeModelToolConsentStore?.grantFor(consentPlatform, id, chatId) })
            }
            .map { resolved -> if (statistics == null) resolved else resolved.copy(tool = observedChild(resolved.tool, resolved.realToolName, resolved.connectionUid)) }
            .sortedBy { it.modelToolName }
        return GitHubProviderPolicy.select(authorized, nativeGitHubAlternatives, bindings.mapNotNull { it.connection }.distinctBy { it.connectionUid })
    }

    /** Old saved histories have no labels. Derive conservative categories from their actual tool bindings. */
    internal suspend fun retainedPersonalDataSources(sources: List<dev.chungjungsoo.gptmobile.data.permissions.PersonalDataToolSource>, chatId: Int): List<dev.chungjungsoo.gptmobile.data.permissions.PersonalDataProvenance> {
        check(sources.size <= 2048) { "PERSONAL_DATA_SCOPE_TOO_LARGE: Review a smaller saved conversation before sharing it." }
        val bindings = sources.filter { it.connectionUid != null }.map { it.profileUid }.distinct().associateWith { toolConnectionRepository.listBindingsWithConnections(it) }
        return sources.mapNotNull { source ->
            val id = source.connectionUid?.let { "$it:${source.toolName}" } ?: source.toolName
            val control = source.toolName in setOf("delegate_to_model", "read_recovery_context", "discover_tools")
            val public = if (source.connectionUid == null) !dev.chungjungsoo.gptmobile.data.permissions.FreeToolDataPolicy.requiresPersonalPermission(source.toolName) else bindings[source.profileUid].orEmpty().any { it.binding.connectionUid == source.connectionUid && it.connection?.type in ToolConnectionType.WEB_SEARCH_TYPES }
            if (control || public) return@mapNotNull null
            dev.chungjungsoo.gptmobile.data.permissions.PersonalDataProvenance(sourceToolId = id, sourceProfileUid = source.profileUid, sourceRef = dev.chungjungsoo.gptmobile.data.research.researchHash("legacy:$chatId:${source.profileUid}:$id"), categories = setOf(dev.chungjungsoo.gptmobile.data.permissions.personalDataCategory(id)), retentionChatId = chatId)
        }
    }

    private fun observedChild(tool: AgentTool, name: String = tool.definition.name, connection: String? = null): AgentTool =
        if (statistics == null) tool else dev.chungjungsoo.gptmobile.data.telemetry.tool.ObservedTool(tool, dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolIdentity.of(name, connection))

    private fun observedUi(tool: AgentTool, profile: String, connection: String? = null): AgentTool =
        dev.chungjungsoo.gptmobile.data.agent.ToolExecutionBudget(
            dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits(),
            statistics = statistics,
            statisticsProfile = profile,
            statisticsRoot = java.util.UUID.randomUUID().toString()
        ).bind(tool, statisticsIdentity = dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolIdentity.of(tool.definition.name, connection))

    private fun pluginEnabledForBinding(
        settings: AppFeatureSettings,
        profileUid: String,
        binding: AgentToolBindingWithConnection
    ): Boolean {
        val connection = binding.connection
        if (connection != null && !settings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.connection(connection.connectionUid))) {
            return false
        }
        if (connection != null && !settings.isToolPluginEnabledForProfile(profileUid, ToolServiceCatalog.forConnection(connection).id)) return false
        if (connection?.type == ToolConnectionType.MCP) return true
        return when (connection?.type) {
            ToolConnectionType.AMAZON_SERPAPI -> settings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.AMAZON_SEARCH)
            ToolConnectionType.GITHUB -> settings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.GITHUB)
            ToolConnectionType.FIRECRAWL,
            ToolConnectionType.PERPLEXITY,
            ToolConnectionType.EXA,
            ToolConnectionType.BRAVE -> true
            else -> when (binding.binding.toolName) {
                BuiltInAgentTool.CURRENT_DATE -> settings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.CURRENT_DATE)
                BuiltInAgentTool.CALCULATE_EXPRESSION -> settings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.CALCULATOR)
                BuiltInAgentTool.READ_FILE_SLICE -> settings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.READ_FILES)
                BuiltInAgentTool.READ_URL -> settings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.READ_URL)
                BuiltInAgentTool.DEVICE_LOCATION -> settings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.DEVICE_LOCATION)
                BuiltInAgentTool.GITHUB -> settings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.GITHUB)
                WEB_SEARCH_TOOL -> settings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.WEB_SEARCH)
                else -> true
            }
        }
    }

    private suspend fun resolveBinding(binding: AgentToolBindingWithConnection, featureSettings: AppFeatureSettings, profileUid: String, userMessage: MessageV2?): ResolvedAgentTool? = when (binding.binding.toolName) {
        WEB_SEARCH_TOOL -> resolveWebSearch(binding.connection)

        BuiltInAgentTool.READ_URL -> if (binding.binding.connectionUid == null) {
            ReadUrlTool(documentContext = documentContext).resolved(null, null, BuiltInAgentTool.READ_URL)
        } else {
            null
        }

        BuiltInAgentTool.READ_FILE_SLICE -> if (binding.binding.connectionUid == null) {
            ReadFileSliceTool().resolved(null, null, BuiltInAgentTool.READ_FILE_SLICE)
        } else {
            null
        }

        BuiltInAgentTool.DEVICE_LOCATION -> if (binding.binding.connectionUid == null) {
            deviceLocationTool.resolved(null, null, BuiltInAgentTool.DEVICE_LOCATION)
        } else {
            null
        }

        BuiltInAgentTool.CALCULATE_EXPRESSION -> if (binding.binding.connectionUid == null) {
            CalculatorTool(featureSettings.pluginExecution[ToolPluginId.CALCULATOR]?.decimalPlaces ?: 8).resolved(null, null, BuiltInAgentTool.CALCULATE_EXPRESSION)
        } else {
            null
        }

        BuiltInAgentTool.GITHUB -> resolveGitHub(binding.connection, profileUid, userMessage)

        else -> null
    }

    private fun resolveAmazonFree(profileUid: String, chatId: Int?, marketplace: String): List<ResolvedAgentTool> {
        val provider = requireNotNull(amazonFreeProvider)
        val permissions = combine(settingRepository.observeFeatureSettings(), settingRepository.observePlatformV2ByUid(profileUid)) { features, profile ->
            profile?.enabled == true &&
                !profile.disableAllTools &&
                !profile.disableRemoteTools &&
                features.isToolPluginEnabledForProfile(profileUid, ToolPluginId.AMAZON_FREE)
        }.distinctUntilChanged()
        return listOf(false, true).map { details ->
            AmazonNativeTool(
                provider,
                details,
                settings = { settingRepository.getFeatureSettings().pluginExecution[ToolPluginId.AMAZON_FREE] ?: PluginExecutionSettings() },
                isAllowed = {
                    val current = settingRepository.fetchPlatformV2s().firstOrNull { it.uid == profileUid }
                    current?.enabled == true &&
                        !current.disableAllTools &&
                        !current.disableRemoteTools &&
                        settingRepository.getFeatureSettings().isToolPluginEnabledForProfile(profileUid, ToolPluginId.AMAZON_FREE)
                },
                permissionChanges = permissions,
                configuredMarketplace = marketplace,
                onFetched = if (amazonHistory != null && chatId != null) {
                    { requestId, market, fetched ->
                        amazonHistory.record(profileUid, requestId, fetched, {
                            val current = settingRepository.fetchPlatformV2s().firstOrNull { it.uid == profileUid }
                            current?.enabled == true && !current.disableAllTools && !current.disableRemoteTools && settingRepository.getFeatureSettings().isToolPluginEnabledForProfile(profileUid, ToolPluginId.AMAZON_FREE)
                        }, chatId, market)
                    }
                } else {
                    null
                }
            ).resolved(null, "Amazon Research Free", if (details) AmazonSearchTool.GET_PRODUCTS else AmazonSearchTool.SEARCH)
                // Shared turn caches must recheck every consumer's live profile grant before reuse.
                .copy(shareableReadOnly = false)
        }
    }

    suspend fun pluginMediaAllowed(profileUid: String?, serviceId: String): Boolean {
        val profile = settingRepository.fetchPlatformV2s().firstOrNull { it.uid == profileUid } ?: return false
        return profile.enabled &&
            !profile.disableAllTools &&
            !profile.disableRemoteTools &&
            settingRepository.getFeatureSettings().isToolPluginEnabledForProfile(profile.uid, serviceId)
    }

    private fun nativeAirbnb(profileUid: String): AirbnbNativeTool = AirbnbNativeTool(
        requireNotNull(publicAirbnb),
        { pluginMediaAllowed(profileUid, ToolPluginId.AIRBNB) },
        { query ->
            val tool = observedChild(WebSearchTool(WebSearchProviderConfig(WebSearchProvider.AUTO, "", ""), networkClient))
            val arguments = kotlinx.serialization.json.buildJsonObject {
                put("query", kotlinx.serialization.json.JsonPrimitive(query))
                put("maxResults", kotlinx.serialization.json.JsonPrimitive(10))
            }
            (tool.execute("airbnb-index-${java.util.UUID.randomUUID()}", arguments).content as? ToolResultContent.Json)?.value as? JsonObject
        }
    )

    suspend fun airbnbListingDetails(profileUid: String, listing: dev.chungjungsoo.gptmobile.data.airbnb.AirbnbListing): dev.chungjungsoo.gptmobile.data.airbnb.AirbnbListing? {
        if (!pluginMediaAllowed(profileUid, ToolPluginId.AIRBNB)) return null
        if (publicAirbnb != null) {
            val arguments = kotlinx.serialization.json.buildJsonObject {
                put("action", kotlinx.serialization.json.JsonPrimitive("details"))
                put("id", kotlinx.serialization.json.JsonPrimitive(listing.id))
                listing.checkin?.let { put("checkin", kotlinx.serialization.json.JsonPrimitive(it)) }
                listing.checkout?.let { put("checkout", kotlinx.serialization.json.JsonPrimitive(it)) }
                listing.adults?.let { put("adults", kotlinx.serialization.json.JsonPrimitive(it)) }
                listing.children?.let { put("children", kotlinx.serialization.json.JsonPrimitive(it)) }
                listing.infants?.let { put("infants", kotlinx.serialization.json.JsonPrimitive(it)) }
                listing.pets?.let { put("pets", kotlinx.serialization.json.JsonPrimitive(it)) }
            }
            val result = observedUi(nativeAirbnb(profileUid), profileUid).execute("airbnb-details-${java.util.UUID.randomUUID()}", arguments)
            val payload = (result.content as? ToolResultContent.Json)?.value
            if (!result.isError && payload != null && pluginMediaAllowed(profileUid, ToolPluginId.AIRBNB)) {
                dev.chungjungsoo.gptmobile.data.airbnb.AirbnbListings.normalize(payload, arguments).firstOrNull { it.id == listing.id }?.let { return it }
            }
        }
        val features = settingRepository.getFeatureSettings()
        if (!features.remoteMcpConnections) return null
        val bindings = toolConnectionRepository.listBindingsWithConnections(profileUid)
            .filter { it.connection?.type == ToolConnectionType.MCP && it.connection?.let(ToolServiceCatalog::forConnection)?.id == ToolPluginId.AIRBNB }
            .filter { pluginEnabledForBinding(features, profileUid, it) }
            .groupBy { requireNotNull(it.connection).connectionUid }
        val available = bindings.values.flatMap { group ->
            try {
                resolveMcpTools(requireNotNull(group.first().connection), group, features, profileUid)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
        }
        val tool = available.firstOrNull { resolved ->
            val name = resolved.realToolName.lowercase(java.util.Locale.ROOT)
            name.contains("listing") &&
                (name.contains("detail") || name.startsWith("get_"))
        }?.tool ?: return null
        val fields = (tool.definition.inputSchema["properties"] as? JsonObject).orEmpty()
        val idKey = listOf("id", "listing_id", "listingId").firstOrNull { it in fields } ?: return null
        val arguments = kotlinx.serialization.json.buildJsonObject {
            put(idKey, kotlinx.serialization.json.JsonPrimitive(listing.id))
            listing.checkin?.let { if ("checkin" in fields) put("checkin", kotlinx.serialization.json.JsonPrimitive(it)) }
            listing.checkout?.let { if ("checkout" in fields) put("checkout", kotlinx.serialization.json.JsonPrimitive(it)) }
            listing.adults?.let { if ("adults" in fields) put("adults", kotlinx.serialization.json.JsonPrimitive(it)) }
            listing.children?.let { if ("children" in fields) put("children", kotlinx.serialization.json.JsonPrimitive(it)) }
            listing.infants?.let { if ("infants" in fields) put("infants", kotlinx.serialization.json.JsonPrimitive(it)) }
            listing.pets?.let { if ("pets" in fields) put("pets", kotlinx.serialization.json.JsonPrimitive(it)) }
        }
        val result = observedUi(tool, profileUid).execute("airbnb-details-${java.util.UUID.randomUUID()}", arguments)
        if (result.isError || !pluginMediaAllowed(profileUid, ToolPluginId.AIRBNB)) return null
        val payload = (result.content as? ToolResultContent.Json)?.value ?: return null
        return dev.chungjungsoo.gptmobile.data.airbnb.AirbnbListings.normalize(payload, arguments).firstOrNull { it.id == listing.id }
    }

    /** An explicit one-request UI action, independent of AI-profile assignment. */
    suspend fun testAmazonFreeSearch(): AgentToolResult {
        val tool = AmazonNativeTool(
            requireNotNull(amazonFreeProvider),
            false,
            settings = { settingRepository.getFeatureSettings().pluginExecution[ToolPluginId.AMAZON_FREE] ?: PluginExecutionSettings() },
            isAllowed = { settingRepository.getFeatureSettings().isToolPluginEnabled(ToolPluginId.AMAZON_FREE) },
            permissionChanges = settingRepository.observeFeatureSettings().map { it.isToolPluginEnabled(ToolPluginId.AMAZON_FREE) }
        )
        return observedUi(tool, "settings").execute(
            java.util.UUID.randomUUID().toString(),
            kotlinx.serialization.json.buildJsonObject {
                put("query", kotlinx.serialization.json.JsonPrimitive("headphones"))
                put("maxResults", kotlinx.serialization.json.JsonPrimitive(1))
            }
        )
    }

    private suspend fun resolveAmazon(connection: ToolConnection, features: AppFeatureSettings): List<ResolvedAgentTool> {
        val token = connection.secretRef?.let { secretVault.read(it) }?.let { bytes ->
            try {
                bytes.decodeToString().trim()
            } finally {
                bytes.fill(0)
            }
        }.orEmpty()
        val client = SerpApiAmazonClient(networkClient, token)
        val settings = features.pluginExecution[ToolPluginId.connection(connection.connectionUid)]
            ?: features.pluginExecution[ToolPluginId.AMAZON_SEARCH] ?: PluginExecutionSettings()
        return listOf(false, true).map { details ->
            val name = if (details) AmazonSearchTool.GET_PRODUCTS else AmazonSearchTool.SEARCH
            AmazonSearchTool(client::fetch, settings, details, "${name}__${connection.alias}")
                .resolved(connection.connectionUid, connection.name, name)
        }
    }

    /** A product-card click spends at most one configured SerpApi detail request, without MCP discovery. */
    suspend fun amazonProductDetails(profileUid: String, marketplace: String, asin: String): JsonObject? {
        suspend fun allowed(connection: ToolConnection): Boolean {
            val profile = settingRepository.fetchPlatformV2s().firstOrNull { it.uid == profileUid }
            val features = settingRepository.getFeatureSettings()
            return profile?.enabled == true &&
                !profile.disableAllTools &&
                !profile.disableRemoteTools &&
                features.isToolPluginEnabledForProfile(profileUid, ToolPluginId.AMAZON_SEARCH) &&
                features.isToolPluginEnabledForProfile(profileUid, ToolPluginId.connection(connection.connectionUid)) &&
                features.isToolPluginEnabledForProfile(profileUid, ToolServiceCatalog.forConnection(connection).id)
        }
        val connection = toolConnectionRepository.listConnections().firstOrNull { it.type == ToolConnectionType.AMAZON_SERPAPI && allowed(it) } ?: return null
        val tool = resolveAmazon(connection, settingRepository.getFeatureSettings()).first { it.realToolName == AmazonSearchTool.GET_PRODUCTS }.tool
        val result = observedUi(tool, profileUid, connection.connectionUid).execute(
            "amazon-product-card-${java.util.UUID.randomUUID()}",
            kotlinx.serialization.json.buildJsonObject {
                put("marketplace", kotlinx.serialization.json.JsonPrimitive(marketplace))
                put("asins", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(asin))))
            }
        )
        if (result.isError || !allowed(connection)) return null
        val payload = (result.content as? ToolResultContent.Json)?.value as? JsonObject ?: return null
        return (payload["products"] as? kotlinx.serialization.json.JsonArray)?.filterIsInstance<JsonObject>()?.firstOrNull()
    }

    /** Explicit settings test only: it spends one search request and never runs during discovery. */
    suspend fun testAmazonConnection(connection: ToolConnection): AgentToolResult {
        require(connection.type == ToolConnectionType.AMAZON_SERPAPI)
        val tool = resolveAmazon(connection, settingRepository.getFeatureSettings()).first().tool
        return observedUi(tool, "settings", connection.connectionUid).execute(
            java.util.UUID.randomUUID().toString(),
            kotlinx.serialization.json.buildJsonObject {
                put("query", kotlinx.serialization.json.JsonPrimitive("headphones"))
                put("maxResults", kotlinx.serialization.json.JsonPrimitive(1))
            }
        )
    }

    private fun expandNativeGitHubTools(tools: List<ResolvedAgentTool>): List<ResolvedAgentTool> = tools.flatMap { base ->
        val github = base.tool as? GitHubTool
        if (github == null) {
            listOf(base)
        } else {
            listOf(base) + github.operations().map { operation ->
                base.copy(tool = operation, realToolName = operation.operation, modelToolName = operation.definition.name, shareableReadOnly = operation.operation in setOf("github_read", "github_search"))
            }
        }
    }

    private suspend fun resolveGitHub(connection: ToolConnection?, profileUid: String, userMessage: MessageV2?, workspaceContext: dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext? = null): ResolvedAgentTool {
        val actualConnection = connection
        var authorizationFailed = false
        if (actualConnection?.secretRef != null && gitHubPublicAuth != null) {
            try {
                gitHubPublicAuth.accessToken(actualConnection)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                authorizationFailed = true
            }
        }
        val token = actualConnection?.secretRef?.let { secretRef ->
            secretVault.read(secretRef)?.let { bytes ->
                try {
                    String(bytes, StandardCharsets.UTF_8).trim()
                } finally {
                    bytes.fill(0)
                }
            }
        }.orEmpty()

        val features = GitHubChatPolicy.features(settingRepository.getFeatureSettings(), profileUid, workspaceContext)
        val engineeringPrepare: (suspend (String, JsonObject) -> JsonObject)? = if (actualConnection != null && engineeringController != null) {
            { root, args ->
                require(userMessage?.chatId?.let { factVault?.scopeForChat(it)?.isTemporary != true } != false) {
                    "Engineering review stores the exact patch. Use a saved conversation or open GitHub review explicitly."
                }
                val engineeringTaskId = engineeringController.prepareFromTool(actualConnection.connectionUid, root, args.toString())
                userMessage?.takeIf { it.chatId > 0 && it.id > 0 }?.let {
                    gitHubDecisionNotifications?.prompt("github-approval-$engineeringTaskId", it.chatId, it.id, "Your repository changes are ready for review and approval.")
                }
                kotlinx.serialization.json.buildJsonObject {
                    put("engineering_task_id", engineeringTaskId)
                    put("status", "AWAITING_APPROVAL")
                    put("committed", false)
                    put("next_action", "The exact patch is awaiting native approval in the GitHub chat card. Stop polling or restating the plan. Approve & Commit resumes the chat automatically; no remote write has occurred.")
                }
            }
        } else {
            null
        }
        val tool = GitHubTool(
            apiToken = token,
            featureSettings = features,
            modelToolName = actualConnection?.let { "github__${it.alias}" } ?: BuiltInAgentTool.GITHUB,
            accountName = actualConnection?.name,
            repositoryContext = workspaceContext?.repository ?: actualConnection?.let { gitHubWorkspaceStore?.get(it.connectionUid) },
            connectionUid = actualConnection?.connectionUid.orEmpty(), conversationId = userMessage?.chatId?.toString().orEmpty(), taskId = userMessage?.let { "${it.chatId}:${it.id}" }.orEmpty(),
            persistentState = userMessage?.chatId?.let { factVault?.scopeForChat(it)?.isTemporary != true } ?: true,
            stateStore = gitHubRepositoryState,
            engineeringPrepare = engineeringPrepare,
            repositoryScope = workspaceContext,
            credentialAdmissionFailed = authorizationFailed,
            watchChecks = if (actualConnection != null && userMessage != null && gitHubCheckWatches != null) {
                { args -> gitHubCheckWatches.subscribe(actualConnection.connectionUid, profileUid, userMessage.chatId, args) }
            } else {
                null
            }
        )
        val admittedProfile = settingRepository.fetchPlatformV2s().firstOrNull { it.uid == profileUid }?.let { GitHubChatPolicy.platform(it, features, workspaceContext) }
        // resolved() normally wraps telemetry immediately; this backend must stay
        // concrete until its compact operations have been expanded above.
        return tool.resolved(actualConnection?.connectionUid, actualConnection?.name, BuiltInAgentTool.GITHUB).copy(tool = tool, nativeGitHub = true, nativeGitHubAuthenticated = tool.hasCredential, canReuseResult = {
            val live = GitHubChatPolicy.features(settingRepository.getFeatureSettings(), profileUid, workspaceContext)
            val current = settingRepository.fetchPlatformV2s().firstOrNull { it.uid == profileUid }?.let { GitHubChatPolicy.platform(it, live, workspaceContext) }
            val credentialUnchanged = if (actualConnection == null) {
                true
            } else {
                actualConnection.secretRef?.let { reference ->
                    val bytes = secretVault.read(reference)
                    try {
                        bytes != null && bytes.decodeToString().trim() == token
                    } finally {
                        bytes?.fill(0)
                    }
                } ?: token.isEmpty()
            }
            credentialUnchanged &&
                current != null &&
                admittedProfile != null &&
                dev.chungjungsoo.gptmobile.data.agent.sameRecoveryDestination(admittedProfile, current) &&
                current.enabled &&
                !current.disableAllTools &&
                !current.disableRemoteTools &&
                live.isToolPluginEnabledForProfile(profileUid, ToolPluginId.GITHUB) &&
                (actualConnection == null || (toolConnectionRepository.getConnection(actualConnection.connectionUid) == actualConnection && live.isToolPluginEnabledForProfile(profileUid, ToolPluginId.connection(actualConnection.connectionUid))))
        })
    }

    private fun hasSelectedGitHubRepository(tools: List<ResolvedAgentTool>): Boolean =
        tools.any { (it.tool as? GitHubTool)?.hasRepositoryContext == true }

    /** Keep scope assembly outside the large suspending resolver's DEX method. */
    private fun scopedGitHubMessage(message: MessageV2?, context: dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext?): MessageV2? {
        if (message == null || context == null) return message
        return message.copy(content = context.promptContext() + "\n\n" + message.content)
    }

    private suspend fun githubCatalogTask(profileUid: String, userMessage: MessageV2?, selectedRepository: Boolean): String {
        val task = userMessage?.content.orEmpty()
        if (dev.chungjungsoo.gptmobile.data.agent.CurrentRequestContract.from(task).intent != "SUBSTANTIVE") return task
        val store = gitHubRepositoryState
        val scope = "intent:$profileUid:${userMessage?.chatId ?: 0}"
        val temporary = userMessage?.chatId?.let { factVault?.scopeForChat(it)?.isTemporary == true } == true
        val previous = if (temporary) null else store?.read(scope, "github")?.get("task")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        val explicit = GitHubOperationTool.taskTools(task).isNotEmpty()
        val followup = Regex("(?i)\\b(continue|resume|fix(?:es|ed|ing)?|repair(?:s|ed|ing)?|correct(?:ions?|ed|ing)?|changes?|patch|push|pushing|commit|committing|branch|release|review|test|build|write|writing)\\b").containsMatchIn(task)
        val selectedTask = when {
            explicit -> task
            followup && (selectedRepository || previous != null) -> "repository " + task + " " + previous.orEmpty()
            else -> task
        }
        // Catalogue selection must inspect the whole request before compacting its
        // durable context. Otherwise a write instruction after a long diagnostic
        // report disappears and both this turn and its continuation become read-only.
        // These are schemas, never permissions: execution still validates grants,
        // approval, repository scope, expected SHAs and GitHub endpoint access.
        val capabilities = GitHubOperationTool.taskTools(selectedTask)
        val selected = if (capabilities.isEmpty()) {
            selectedTask.take(4096)
        } else {
            (capabilities.joinToString(" ") + "\n" + selectedTask).take(4096)
        }
        if (!temporary && GitHubOperationTool.taskTools(selected).isNotEmpty()) store?.write(scope, "github", kotlinx.serialization.json.buildJsonObject { put("task", kotlinx.serialization.json.JsonPrimitive(selected)) })
        return selected
    }

    suspend fun testSearchConnection(connection: ToolConnection): dev.chungjungsoo.gptmobile.data.agent.AgentToolResult {
        val tool = requireNotNull(resolveWebSearch(connection, resetAccess = true)) { "Not a search connection" }
        return tool.tool.execute(
            "connection-test-${java.util.UUID.randomUUID()}",
            kotlinx.serialization.json.buildJsonObject {
                put("query", kotlinx.serialization.json.JsonPrimitive("search connection test"))
                put("maxResults", kotlinx.serialization.json.JsonPrimitive(1))
            }
        )
    }

    suspend fun effectiveSearchInventory(profileUid: String): List<EffectiveSearchEngine> {
        val profile = settingRepository.fetchPlatformV2s().firstOrNull { it.uid == profileUid }
        val features = settingRepository.getFeatureSettings()
        val connections = toolConnectionRepository.listConnections()
        val bindings = toolConnectionRepository.listBindingsByProfile(profileUid)
        val resolved = resolve(profileUid, cachedCataloguesOnly = true).filter { it.isWebSearchEngine() }
        fun reason(connection: ToolConnection?, authorized: Boolean): String = when {
            profile?.disableAllTools == true -> "Excluded: all tools disabled for this profile"
            profile?.disableRemoteTools == true -> "Excluded: remote tools disabled for this profile"
            connection != null && (!features.isToolPluginEnabledForProfile(profileUid, ToolServiceCatalog.forConnection(connection).id) || !features.isToolPluginEnabledForProfile(profileUid, ToolPluginId.connection(connection.connectionUid))) -> "Excluded: service or connection disabled for this profile"
            connection != null && bindings.none { it.connectionUid == connection.connectionUid } -> "Excluded: connection not assigned to this profile"
            connection != null && connection.type in SEARCH_PROVIDERS && connection.secretRef == null -> "Requires connection repair: missing credential"
            authorized -> "Authorized · operational status shown below"
            connection?.type == ToolConnectionType.MCP -> "Excluded: cached discovery or profile sharing consent unavailable"
            else -> "Excluded: search service is not enabled for this profile"
        }
        val builtIn = resolved.any { it.connectionUid == null }
        return listOf(EffectiveSearchEngine("builtin-duckduckgo", "Built-in DuckDuckGo", builtIn, reason(null, builtIn))) +
            connections.filter { connection -> connection.type in SEARCH_PROVIDERS || resolved.any { it.connectionUid == connection.connectionUid } || connection.type == ToolConnectionType.MCP && bindings.any { it.connectionUid == connection.connectionUid && it.toolName.contains("search", true) } }.map { connection ->
                val authorized = resolved.any { it.connectionUid == connection.connectionUid } && (connection.type !in SEARCH_PROVIDERS || connection.secretRef != null)
                EffectiveSearchEngine(connection.connectionUid, connection.name, authorized, reason(connection, authorized))
            }
    }

    private suspend fun resolveWebSearch(connection: ToolConnection?, resetAccess: Boolean = false): ResolvedAgentTool? {
        val actualConnection = connection ?: return null
        val provider = SEARCH_PROVIDERS[actualConnection.type] ?: return null
        val endpointUrl = provider.defaultEndpointUrl
        val modelToolName = "web_search_" + actualConnection.connectionUid.replace("-", "_")
        val definition = WebSearchTool(
            config = WebSearchProviderConfig(provider.provider, "", endpointUrl),
            modelToolName = modelToolName,
            networkClient = networkClient
        ).definition
        val credential = actualConnection.secretRef?.let { secretRef ->
            secretVault.read(secretRef)
        }
        val tool = credential?.let { bytes ->
            try {
                val token = String(bytes, StandardCharsets.UTF_8)
                if (token.isBlank()) {
                    MissingCredentialTool(definition)
                } else {
                    WebSearchTool(
                        config = WebSearchProviderConfig(
                            provider = provider.provider,
                            bearerToken = token,
                            endpointUrl = endpointUrl,
                            connectionUid = actualConnection.connectionUid
                        ),
                        modelToolName = modelToolName,
                        networkClient = networkClient
                    )
                }
            } finally {
                bytes.fill(0)
            }
        } ?: MissingCredentialTool(definition)
        if (resetAccess) (tool as? WebSearchTool)?.resetAccessFailureAfterRepair()
        return tool.resolved(actualConnection.connectionUid, actualConnection.name, WEB_SEARCH_TOOL)
    }

    private suspend fun resolveMcpTools(
        connection: ToolConnection,
        bindings: List<AgentToolBindingWithConnection>,
        features: AppFeatureSettings,
        profileUid: String,
        cachedOnly: Boolean = false,
        userMessage: MessageV2? = null
    ): List<ResolvedAgentTool> {
        val selectedNames = bindings.map { it.binding.toolName }.toSet()
        val remoteTools = if (cachedOnly) mcpClientManager.peekToolsForConnection(connection.connectionUid, connection.endpointUrl, connection.allowCleartext).orEmpty() else discoverMcpTools(connection)
        (selectedNames - remoteTools.map { it.name }.toSet()).forEach { name ->
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("ToolCatalog", "TOOL_OMITTED · stage=resolution · connection=${connection.connectionUid} · name=$name · reason=${if (cachedOnly) "CACHED_CATALOG_MISS_DISCOVERY_REQUIRED" else "REMOTE_TOOL_NOT_LISTED"}")
        }
        val janNafta = AmazonJanNaftaTool.recognizes(connection, remoteTools.map { it.name }.toSet())
        return remoteTools
            .filter { it.name in selectedNames }
            .map { remoteTool ->
                val tool = McpAgentTool(
                    definition = mcpToolDefinition(connection.alias, remoteTool),
                    authType = connection.authType,
                    config = { forceRefresh, rejectedHeader ->
                        val current = toolConnectionRepository.getConnection(connection.connectionUid)
                            ?: error("MCP connection was uninstalled.")
                        val latest = settingRepository.getFeatureSettings()
                        check(
                            latest.remoteMcpConnections &&
                                latest.isToolPluginEnabledForProfile(profileUid, ToolPluginId.connection(current.connectionUid)) &&
                                latest.isToolPluginEnabledForProfile(profileUid, ToolServiceCatalog.forConnection(current).id)
                        ) { "MCP plugin is disabled." }
                        if (dev.chungjungsoo.gptmobile.data.health.HealthExternalStoragePolicy.requiresConsent(remoteTool.name) &&
                            latest.isToolPluginEnabledForProfile(profileUid, ToolPluginId.HEALTH_CONNECT)
                        ) {
                            check(healthConsent?.isExternalStorageAllowed() == true) {
                                "HEALTH_EXTERNAL_STORAGE_CONSENT_REQUIRED: External memory writes are blocked for this health-enabled profile. Review Smartwatch Health > Access; health read permissions do not authorize remote storage."
                            }
                        }
                        mcpConfig(current, forceRefresh, rejectedHeader)
                    },
                    remoteToolName = remoteTool.name,
                    outputSchema = remoteTool.outputSchema,
                    clientManager = mcpClientManager,
                    metadata = if (dev.chungjungsoo.gptmobile.data.crawl.CrawlerCompanionGrant.recognizes(connection)) {
                        (
                            {
                                check(settingRepository.getFeatureSettings() == features && toolConnectionRepository.getConnection(connection.connectionUid) == connection) { "Crawler authority changed" }
                                val limit = (features.pluginExecution[ToolPluginId.CRAWLERS] ?: PluginExecutionSettings()).normalized().crawlerMaxPages * 6
                                dev.chungjungsoo.gptmobile.data.crawl.CrawlerCompanionGrant.metadata(secretVault, connection, profileUid, userMessage, dev.chungjungsoo.gptmobile.data.crawl.crawlHash(features.toString()), userMessage?.let { factVault?.scopeForChat(it.chatId)?.isTemporary == false } == true, limit)
                            }
                            )
                    } else {
                        null
                    }
                )
                ResolvedAgentTool(
                    tool = if (ToolServiceCatalog.forConnection(connection).id == ToolPluginId.AIRBNB && remoteTool.name.contains(Regex("(?i)search|listing|details"))) {
                        AirbnbMcpResultTool(tool)
                    } else if (janNafta) {
                        AmazonJanNaftaTool(tool, remoteTool.name, features.pluginExecution[ToolPluginId.connection(connection.connectionUid)] ?: features.pluginExecution[ToolPluginId.AMAZON_SEARCH] ?: PluginExecutionSettings())
                    } else {
                        AmazonMcpResultTool.wrap(tool, connection.endpointUrl.orEmpty(), remoteTool.name, features.pluginExecution[ToolPluginId.connection(connection.connectionUid)] ?: features.pluginExecution[ToolPluginId.AMAZON_SEARCH] ?: PluginExecutionSettings())
                    },
                    connectionUid = connection.connectionUid,
                    connectionName = connection.name,
                    realToolName = remoteTool.name,
                    modelToolName = tool.definition.name,
                    shareableReadOnly = remoteTool.name in connection.approvedReadTools.lines().map(String::trim),
                    canReuseResult = if (remoteTool.name in connection.approvedReadTools.lines().map(String::trim)) {
                        (
                            {
                                val currentProfile = settingRepository.fetchPlatformV2s().firstOrNull { it.uid == profileUid }
                                currentProfile?.enabled == true &&
                                    !currentProfile.disableAllTools &&
                                    !currentProfile.disableRemoteTools &&
                                    settingRepository.getFeatureSettings() == features &&
                                    toolConnectionRepository.getConnection(connection.connectionUid) == connection &&
                                    recoveryBindingGrants(toolConnectionRepository.listBindingsWithConnections(profileUid).filter { it.binding.connectionUid == connection.connectionUid && it.binding.toolName == remoteTool.name }) == recoveryBindingGrants(bindings.filter { it.binding.toolName == remoteTool.name })
                            }
                            )
                    } else {
                        null
                    }
                )
            }
    }

    suspend fun mcpConfig(
        connection: ToolConnection,
        forceOAuthRefresh: Boolean = false,
        rejectedAuthorizationHeader: String? = null
    ): McpConnectionConfig {
        val authorization = when (connection.authType) {
            ToolConnectionAuthType.NONE -> null

            ToolConnectionAuthType.BEARER, ToolConnectionAuthType.API_KEY -> readBearerHeader(connection)

            ToolConnectionAuthType.OAUTH -> mcpOAuthCoordinator.authorizationHeader(
                connection,
                forceOAuthRefresh,
                rejectedAuthorizationHeader
            )

            else -> throw IllegalArgumentException("Unsupported MCP authentication type.")
        }
        val googleKey = connection.authType == ToolConnectionAuthType.API_KEY && runCatching { java.net.URI(connection.endpointUrl.orEmpty()).host == "mapstools.googleapis.com" }.getOrDefault(false)
        return McpConnectionConfig(
            connectionUid = connection.connectionUid,
            endpointUrl = dev.chungjungsoo.gptmobile.data.security.EndpointSecrets.resolve(connection, secretVault),
            allowCleartext = connection.allowCleartext,
            authorizationHeader = authorization.takeUnless { googleKey },
            googleApiKey = authorization?.removePrefix("Bearer ").takeIf { googleKey }
        )
    }

    private suspend fun readBearerHeader(connection: ToolConnection): String {
        val secretRef = connection.secretRef ?: throw IllegalArgumentException("MCP bearer credential is missing.")
        val bytes = secretVault.read(secretRef) ?: throw IllegalArgumentException("MCP bearer credential is missing.")
        return try {
            val token = bytes.decodeToString().trim()
            require(token.isNotEmpty()) { "MCP bearer credential is missing." }
            require('\r' !in token && '\n' !in token) { "MCP bearer credential is invalid." }
            "Bearer $token"
        } finally {
            bytes.fill(0)
        }
    }

    private fun AgentTool.resolved(
        connectionUid: String?,
        connectionName: String?,
        realToolName: String
    ) = ResolvedAgentTool(
        tool = observedChild(this, realToolName, connectionUid),
        connectionUid = connectionUid,
        connectionName = connectionName,
        realToolName = realToolName,
        modelToolName = definition.name,
        shareableReadOnly = realToolName in SHAREABLE_BUILT_IN_TOOLS
    )

    private companion object {
        const val WEB_SEARCH_TOOL = "web_search"
        val SHAREABLE_BUILT_IN_TOOLS = setOf(
            BuiltInAgentTool.CURRENT_DATE,
            BuiltInAgentTool.CALCULATE_EXPRESSION,
            BuiltInAgentTool.READ_FILE_SLICE,
            BuiltInAgentTool.READ_URL,
            BuiltInAgentTool.DEVICE_LOCATION,
            AmazonSearchTool.SEARCH,
            AmazonSearchTool.GET_PRODUCTS,
            WEB_SEARCH_TOOL
        )
        val SEARCH_PROVIDERS = mapOf(
            ToolConnectionType.FIRECRAWL to SearchProvider(WebSearchProvider.FIRECRAWL, "https://api.firecrawl.dev/v2/search"),
            ToolConnectionType.PERPLEXITY to SearchProvider(WebSearchProvider.PERPLEXITY, "https://api.perplexity.ai/search"),
            ToolConnectionType.EXA to SearchProvider(WebSearchProvider.EXA, "https://api.exa.ai/search"),
            ToolConnectionType.BRAVE to SearchProvider(WebSearchProvider.BRAVE, "https://api.search.brave.com/res/v1/web/search")
        )
    }
}

private class McpAgentTool(
    override val definition: AgentToolDefinition,
    private val authType: String,
    private val config: suspend (Boolean, String?) -> McpConnectionConfig,
    private val remoteToolName: String,
    private val clientManager: McpClientManager,
    private val outputSchema: io.modelcontextprotocol.kotlin.sdk.types.ToolSchema? = null,
    private val metadata: (suspend () -> JsonObject)? = null
) : AgentTool {
    private val unknownCatalogRefreshed = java.util.concurrent.atomic.AtomicBoolean()
    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        val isFileTool = isFileReadingTool(remoteToolName)
        val startLine = if (isFileTool) {
            arguments["start_line"]?.jsonPrimitive?.intOrNull
        } else {
            null
        }
        val endLine = if (isFileTool) {
            arguments["end_line"]?.jsonPrimitive?.intOrNull
        } else {
            null
        }

        val remoteArguments = if (isFileTool && (startLine != null || endLine != null)) {
            JsonObject(arguments.filterKeys { it != "start_line" && it != "end_line" })
        } else {
            arguments
        }

        val initialConfig = try {
            config(false, null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return AgentToolResult(callId, ToolResultContent.Text("MCP connection unavailable. Check installation, enable state and credentials in Tool connections."), true)
        }
        val trustedMetadata = metadata?.invoke()
        val result = try {
            clientManager.callTool(initialConfig, remoteToolName, remoteArguments, callId, trustedMetadata)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (authType != ToolConnectionAuthType.OAUTH || !error.isUnauthorized()) throw error
            clientManager.callTool(
                config(true, initialConfig.authorizationHeader),
                remoteToolName,
                remoteArguments,
                callId,
                trustedMetadata
            )
        }
        if (result.isError == true && result.content.toString().let { it.contains("unknown tool", true) || it.contains("tool not found", true) }) {
            if (!unknownCatalogRefreshed.compareAndSet(false, true)) return dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope.error(callId, "UNKNOWN_TOOL", "Tool remains unavailable after one catalog refresh. Stop repeating this call.", dispatched = true)
            val catalog = clientManager.listTools(initialConfig, forceRefresh = true)
            // Discovery refresh is read-only. Do not replay an action whose dispatch outcome is uncertain.
            return dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope.error(callId, "UNKNOWN_TOOL", if (catalog.any { it.name == remoteToolName }) "Catalog refreshed; tool exists but execution was rejected. Check arguments and connection." else "Catalog refreshed once; tool is absent. Stop repeating this call.", dispatched = true)
        }
        if (outputSchema != null && result.isError != true) {
            val schema = kotlinx.serialization.json.Json.encodeToJsonElement(io.modelcontextprotocol.kotlin.sdk.types.ToolSchema.serializer(), outputSchema) as JsonObject
            val invalid = result.structuredContent?.let { schemaError(schema, it) } ?: "The tool did not return its declared structured output."
            if (invalid.isNotEmpty() && result.structuredContent == null) return AgentToolResult(callId, ToolResultContent.Text(invalid), true)
            result.structuredContent?.let { schemaError(schema, it) }?.let { error -> return AgentToolResult(callId, ToolResultContent.Text(error), true) }
        }
        return mapMcpToolResult(callId, result, startLine, endLine)
    }
}

private fun Tool.isSafelyShareableReadOnly(): Boolean {
    if (annotations?.readOnlyHint != true) return false

    val tokens = name.lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter(String::isNotBlank)
        .toSet()
    if (tokens.any { it in MUTATING_TOOL_TOKENS }) return false
    return tokens.any { it in READ_ONLY_TOOL_TOKENS }
}

private val MUTATING_TOOL_TOKENS = setOf(
    "add",
    "book",
    "buy",
    "cancel",
    "commit",
    "create",
    "delete",
    "edit",
    "execute",
    "install",
    "move",
    "order",
    "patch",
    "post",
    "publish",
    "purchase",
    "remove",
    "rename",
    "report",
    "restore",
    "run",
    "send",
    "set",
    "submit",
    "trigger",
    "update",
    "upload",
    "write"
)

private val READ_ONLY_TOOL_TOKENS = setOf(
    "check",
    "current",
    "date",
    "describe",
    "fetch",
    "find",
    "get",
    "inspect",
    "list",
    "location",
    "lookup",
    "query",
    "read",
    "retrieve",
    "search",
    "status",
    "time",
    "view",
    "weather"
)

private fun Throwable.isUnauthorized(): Boolean = generateSequence(this) { it.cause }
    .any { error -> error is StreamableHttpError && error.code == 401 }

private fun ToolConnection.isGitHubMcpEndpoint(): Boolean {
    val endpoint = endpointUrl?.lowercase().orEmpty()
    return "api.githubcopilot.com/mcp" in endpoint ||
        "github-mcp-server" in endpoint
}

private data class SearchProvider(
    val provider: WebSearchProvider,
    val defaultEndpointUrl: String
)

private class MissingCredentialTool(
    override val definition: AgentToolDefinition
) : AgentTool {

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = AgentToolResult(
        callId = callId,
        content = ToolResultContent.Text("Tool web_search is unavailable: missing credential."),
        isError = true
    )
}
