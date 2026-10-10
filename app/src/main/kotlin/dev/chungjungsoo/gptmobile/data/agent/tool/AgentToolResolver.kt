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

data class ResolvedAgentTool(
    val tool: AgentTool,
    val connectionUid: String?,
    val connectionName: String?,
    val realToolName: String,
    val modelToolName: String,
    val shareableReadOnly: Boolean = false,
    val canReuseResult: (suspend () -> Boolean)? = null
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
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val documentContext: android.content.Context? = null
) {
    suspend fun discoverMcpTools(connection: ToolConnection, forceRefresh: Boolean = false): List<Tool> {
        val config = mcpConfig(connection)
        return try {
            mcpClientManager.listTools(config, forceRefresh)
        } catch (error: Exception) {
            if (connection.authType != ToolConnectionAuthType.OAUTH || !error.isUnauthorized()) throw error
            mcpClientManager.listTools(
                mcpConfig(
                    connection,
                    forceOAuthRefresh = true,
                    rejectedAuthorizationHeader = config.authorizationHeader
                ),
                forceRefresh = true
            )
        }
    }

    suspend fun resolve(
        profileUid: String,
        chatToolConfig: ChatMcpToolConfig? = null,
        userMessage: MessageV2? = null,
        delegate: (suspend (PlatformV2, String, Int) -> String)? = null,
        onConnectionError: (String) -> Unit = {}
    ): List<ResolvedAgentTool> {
        val platforms = settingRepository.fetchPlatformV2s()
        val platform = platforms.firstOrNull { it.uid == profileUid }

        // If master disableAllTools is toggled, return no tools immediately
        if (platform?.disableAllTools == true) {
            return emptyList()
        }

        val freeProfile = platform?.compatibleType == ClientType.FREE
        val disableRemote = platform?.disableRemoteTools == true
        val disableLocal = platform?.disableLocalTools == true
        val featureSettings = settingRepository.getFeatureSettings()
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
                    delegate
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
                    val tool = UnifiedMemoryTool(factVault, memoryGraph, memoryDocuments, userMessage, platform.isPrivateDestination())
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
                    if (installation.ready(entry) &&
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
                                shareableReadOnly = true
                            )
                        }
                    }
                }
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
                if (configuredNativeGitHubConnections.isEmpty()) {
                    resolved += GitHubTool(featureSettings = featureSettings).resolved(null, null, BuiltInAgentTool.GITHUB)
                }
                nativeGitHubConnections.forEach { connection -> resolved += resolveGitHub(connection) }
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
                    resolveBinding(binding, featureSettings)?.let { customResolvedTool ->
                        resolved.removeAll { it.modelToolName == customResolvedTool.modelToolName }
                        resolved += customResolvedTool
                    }
                }
            }

        if (allowRemoteMcp) {
            val mcpGroups = bindings
                .filter { it.connection?.type == ToolConnectionType.MCP }
                .filter { pluginEnabledForBinding(featureSettings, profileUid, it) }
                .filterNot { binding ->
                    // Prefer the native GitHub API surface when it is connected.
                    // Keeping GitHub Official MCP visible at the same time causes
                    // agents to repeat searches/PR reads through both providers.
                    nativeGitHubConnections.isNotEmpty() && binding.connection?.isGitHubMcpEndpoint() == true
                }
                .groupBy { requireNotNull(it.connection).connectionUid }
                .toSortedMap()
                .values
                .toList()
            val mcpResults = coroutineScope {
                mcpGroups.map { mcpBindings ->
                    async {
                        val connection = requireNotNull(mcpBindings.first().connection)
                        try {
                            resolveMcpTools(connection, mcpBindings, featureSettings, profileUid) to null
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            emptyList<ResolvedAgentTool>() to "${connection.name}: tools unavailable. Check authentication and connection diagnostics."
                        }
                    }
                }.awaitAll()
            }
            mcpResults.forEach { (tools, error) ->
                resolved += tools
                error?.let(onConnectionError)
            }
        }

        val collisions = resolved.groupBy { it.modelToolName }.filterValues { group -> group.map { it.connectionUid to it.realToolName }.distinct().size > 1 }.keys
        if (collisions.isNotEmpty()) onConnectionError("Ambiguous tool aliases excluded: " + collisions.sorted().joinToString(", "))
        return resolved.filterNot { it.modelToolName in collisions }.distinctBy { it.modelToolName }
            .filter { tool ->
                if (tool.modelToolName in AmazonNativeTool.names + AmazonLocalTool.names) {
                    featureSettings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.AMAZON_FREE)
                } else {
                    !tool.isAmazonProductTool() || featureSettings.isToolPluginEnabledForProfile(profileUid, ToolPluginId.AMAZON_SEARCH)
                }
            }
            .filter { tool ->
                // Discovery stays visible, but runtime consent cannot be bypassed by
                // Select all, imported chat options or a restored pending prompt.
                !freeProfile ||
                    chatToolConfig == null ||
                    tool.connectionUid == null ||
                    bindings.none { it.connection?.type == ToolConnectionType.MCP && it.connection?.connectionUid == tool.connectionUid } ||
                    freeModelToolConsentStore?.isGranted(profileUid, "${tool.connectionUid}:${tool.realToolName}") == true
            }
            .filter { tool ->
                if (chatToolConfig == null) {
                    true
                } else {
                    val candidateIds = listOfNotNull(
                        tool.connectionUid?.let { "$it:${tool.realToolName}" },
                        tool.modelToolName,
                        tool.realToolName,
                        tool.connectionUid,
                        "web_search".takeIf { tool.isWebSearchEngine() }
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
                    else -> ""
                }
                val fallback = when {
                    resolved.isWebSearchEngine() -> ToolPluginId.WEB_SEARCH
                    resolved.isAmazonProductTool() -> ToolPluginId.AMAZON_SEARCH
                    resolved.realToolName == "github" -> ToolPluginId.GITHUB
                    else -> id
                }
                (featureSettings.pluginExecution[id] ?: featureSettings.pluginExecution[fallback])?.let { config -> resolved.copy(tool = ConfiguredPluginTool(resolved.tool, config)) } ?: resolved
            }
            .map { resolved ->
                if (resolved.isWebSearchEngine()) {
                    val snapshot = bindings.filter { it.binding.connectionUid == resolved.connectionUid && it.binding.toolName == resolved.realToolName }
                    return@map resolved.copy(canReuseResult = {
                        val current = settingRepository.fetchPlatformV2s().firstOrNull { it.uid == profileUid }
                        val features = settingRepository.getFeatureSettings()
                        current == platform &&
                            current?.enabled == true &&
                            !current.disableAllTools &&
                            !current.disableRemoteTools &&
                            features == featureSettings &&
                            features.isToolPluginEnabledForProfile(profileUid, ToolPluginId.WEB_SEARCH) &&
                            (
                                resolved.connectionUid == null ||
                                    (
                                        snapshot.isNotEmpty() &&
                                            toolConnectionRepository.listBindingsWithConnections(profileUid).filter { it.binding.connectionUid == resolved.connectionUid && it.binding.toolName == resolved.realToolName } == snapshot &&
                                            snapshot.all { it.connection?.type != ToolConnectionType.MCP || resolved.realToolName in it.connection?.approvedReadTools.orEmpty().lines().map(String::trim) }
                                        )
                                )
                    })
                }
                if (!resolved.isAmazonProductTool()) return@map resolved
                val plugin = if (resolved.modelToolName in AmazonNativeTool.names) ToolPluginId.AMAZON_FREE else ToolPluginId.AMAZON_SEARCH
                resolved.copy(canReuseResult = {
                    val current = settingRepository.fetchPlatformV2s().firstOrNull { it.uid == profileUid }
                    val features = settingRepository.getFeatureSettings()
                    current?.enabled == true &&
                        !current.disableAllTools &&
                        !current.disableRemoteTools &&
                        features.isToolPluginEnabledForProfile(profileUid, plugin) &&
                        (resolved.connectionUid == null || features.isToolPluginEnabledForProfile(profileUid, ToolPluginId.connection(resolved.connectionUid)))
                })
            }
            .sortedBy { it.modelToolName }
    }

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

    private suspend fun resolveBinding(binding: AgentToolBindingWithConnection, featureSettings: AppFeatureSettings): ResolvedAgentTool? = when (binding.binding.toolName) {
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

        BuiltInAgentTool.GITHUB -> resolveGitHub(binding.connection)

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
            val tool = WebSearchTool(WebSearchProviderConfig(WebSearchProvider.AUTO, "", ""), networkClient)
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
            val result = nativeAirbnb(profileUid).execute("airbnb-details-${java.util.UUID.randomUUID()}", arguments)
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
        val result = tool.execute("airbnb-details-${java.util.UUID.randomUUID()}", arguments)
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
        return tool.execute(
            "amazon-free-settings-test",
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
        val result = tool.execute(
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
        return tool.execute(
            "amazon-connection-test",
            kotlinx.serialization.json.buildJsonObject {
                put("query", kotlinx.serialization.json.JsonPrimitive("headphones"))
                put("maxResults", kotlinx.serialization.json.JsonPrimitive(1))
            }
        )
    }

    private suspend fun resolveGitHub(connection: ToolConnection?): ResolvedAgentTool {
        val actualConnection = connection
        val token = actualConnection?.secretRef?.let { secretRef ->
            secretVault.read(secretRef)?.let { bytes ->
                try {
                    String(bytes, StandardCharsets.UTF_8).trim()
                } finally {
                    bytes.fill(0)
                }
            }
        }.orEmpty()

        val features = settingRepository.getFeatureSettings()
        val tool = GitHubTool(
            apiToken = token,
            featureSettings = features,
            modelToolName = actualConnection?.let { "github__${it.alias}" } ?: BuiltInAgentTool.GITHUB,
            accountName = actualConnection?.name,
            repositoryContext = actualConnection?.let { gitHubWorkspaceStore?.get(it.connectionUid) }
        )
        return tool.resolved(actualConnection?.connectionUid, actualConnection?.name, BuiltInAgentTool.GITHUB)
    }

    private suspend fun resolveWebSearch(connection: ToolConnection?): ResolvedAgentTool? {
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
                            endpointUrl = endpointUrl
                        ),
                        modelToolName = modelToolName,
                        networkClient = networkClient
                    )
                }
            } finally {
                bytes.fill(0)
            }
        } ?: MissingCredentialTool(definition)
        return tool.resolved(actualConnection.connectionUid, actualConnection.name, WEB_SEARCH_TOOL)
    }

    private suspend fun resolveMcpTools(
        connection: ToolConnection,
        bindings: List<AgentToolBindingWithConnection>,
        features: AppFeatureSettings,
        profileUid: String
    ): List<ResolvedAgentTool> {
        val selectedNames = bindings.map { it.binding.toolName }.toSet()
        val remoteTools = discoverMcpTools(connection)
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
                        mcpConfig(current, forceRefresh, rejectedHeader)
                    },
                    remoteToolName = remoteTool.name,
                    outputSchema = remoteTool.outputSchema,
                    clientManager = mcpClientManager
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
                    shareableReadOnly = remoteTool.name in connection.approvedReadTools.lines().map(String::trim)
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
        tool = this,
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
    private val outputSchema: io.modelcontextprotocol.kotlin.sdk.types.ToolSchema? = null
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
        val result = try {
            clientManager.callTool(initialConfig, remoteToolName, remoteArguments, callId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (authType != ToolConnectionAuthType.OAUTH || !error.isUnauthorized()) throw error
            clientManager.callTool(
                config(true, initialConfig.authorizationHeader),
                remoteToolName,
                remoteArguments,
                callId
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
