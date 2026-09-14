package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.database.dao.AgentToolBindingWithConnection
import dev.chungjungsoo.gptmobile.data.database.entity.BuiltInAgentTool
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

data class ResolvedAgentTool(
    val tool: AgentTool,
    val connectionUid: String?,
    val connectionName: String?,
    val realToolName: String,
    val modelToolName: String
)

class AgentToolResolver @Inject constructor(
    private val toolConnectionRepository: ToolConnectionRepository,
    private val settingRepository: SettingRepository,
    private val secretVault: SecretVault,
    private val networkClient: NetworkClient,
    private val mcpClientManager: McpClientManager,
    private val mcpOAuthCoordinator: McpOAuthCoordinator,
    private val deviceLocationTool: DeviceLocationTool
) {
    suspend fun discoverMcpTools(connection: ToolConnection): List<Tool> {
        val config = mcpConfig(connection)
        return try {
            mcpClientManager.listTools(config)
        } catch (error: Exception) {
            if (connection.authType != ToolConnectionAuthType.OAUTH || !error.isUnauthorized()) throw error
            mcpClientManager.listTools(
                mcpConfig(
                    connection,
                    forceOAuthRefresh = true,
                    rejectedAuthorizationHeader = config.authorizationHeader
                )
            )
        }
    }

    suspend fun resolve(
        profileUid: String,
        chatToolConfig: ChatMcpToolConfig? = null
    ): List<ResolvedAgentTool> {
        val platforms = settingRepository.fetchPlatformV2s()
        val platform = platforms.firstOrNull { it.uid == profileUid }

        // If master disableAllTools is toggled, return no tools immediately
        if (platform?.disableAllTools == true) {
            return emptyList()
        }

        val disableRemote = platform?.disableRemoteTools == true
        val disableLocal = platform?.disableLocalTools == true

        // Baseline zero-config tools available out of the box to all models
        val defaultWebSearch = WebSearchTool(
            config = WebSearchProviderConfig(
                provider = WebSearchProvider.AUTO,
                bearerToken = "",
                endpointUrl = "http://127.0.0.1:8000/search"
            ),
            networkClient = networkClient
        )

        val resolved = mutableListOf<ResolvedAgentTool>()

        if (!disableLocal) {
            resolved += CurrentDateTool().resolved(null, null, BuiltInAgentTool.CURRENT_DATE)
            resolved += CalculatorTool().resolved(null, null, BuiltInAgentTool.CALCULATE_EXPRESSION)
            resolved += ReadFileSliceTool().resolved(null, null, BuiltInAgentTool.READ_FILE_SLICE)
        }

        if (!disableRemote) {
            resolved += ReadUrlTool().resolved(null, null, BuiltInAgentTool.READ_URL)
            resolved += GitHubTool().resolved(null, null, BuiltInAgentTool.GITHUB)
            resolved += defaultWebSearch.resolved(null, null, WEB_SEARCH_TOOL)
        }

        val bindings = toolConnectionRepository.listBindingsWithConnections(profileUid)
            .sortedWith(compareBy<AgentToolBindingWithConnection> { it.binding.toolName }.thenBy { it.binding.connectionUid ?: "" }.thenBy { it.binding.bindingUid })
        bindings
            .filterNot { it.connection?.type == ToolConnectionType.MCP }
            .forEach { binding ->
                val isRemoteBinding = binding.binding.toolName in setOf(WEB_SEARCH_TOOL, BuiltInAgentTool.READ_URL, BuiltInAgentTool.GITHUB)
                val isLocalBinding = !isRemoteBinding
                if ((isRemoteBinding && !disableRemote) || (isLocalBinding && !disableLocal)) {
                    resolveBinding(binding)?.let { customResolvedTool ->
                        resolved.removeAll { it.modelToolName == customResolvedTool.modelToolName }
                        resolved += customResolvedTool
                    }
                }
            }

        if (!disableRemote) {
            bindings.filter { it.connection?.type == ToolConnectionType.MCP }
                .groupBy { requireNotNull(it.connection).connectionUid }
                .toSortedMap()
                .values
                .forEach { mcpBindings ->
                    try {
                        resolved += resolveMcpTools(requireNotNull(mcpBindings.first().connection), mcpBindings)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                    }
                }
        }

        val sanitized = sanitizeToolNames(resolved)
        return applyChatToolConfig(sanitized, chatToolConfig)
    }

    private fun applyChatToolConfig(
        tools: List<ResolvedAgentTool>,
        chatToolConfig: ChatMcpToolConfig?
    ): List<ResolvedAgentTool> {
        if (chatToolConfig == null) return tools
        if (!chatToolConfig.enabled) return emptyList()
        val allowedModelNames = chatToolConfig.activeModelToolNames?.toSet() ?: return tools
        return tools.filter { it.modelToolName in allowedModelNames }
    }

    private fun resolveBinding(binding: AgentToolBindingWithConnection): ResolvedAgentTool? {
        val toolName = binding.binding.toolName
        val connection = binding.connection
        val tool = when (toolName) {
            BuiltInAgentTool.CURRENT_DATE -> CurrentDateTool()
            BuiltInAgentTool.CALCULATE_EXPRESSION -> CalculatorTool()
            BuiltInAgentTool.READ_URL -> ReadUrlTool()
            BuiltInAgentTool.DEVICE_LOCATION -> deviceLocationTool
            BuiltInAgentTool.READ_FILE_SLICE -> ReadFileSliceTool()
            BuiltInAgentTool.GITHUB -> {
                val secretToken = connection?.secretRef?.let(secretVault::retrieveSecret)?.let { String(it, StandardCharsets.UTF_8) }.orEmpty()
                GitHubTool(apiToken = secretToken)
            }
            WEB_SEARCH_TOOL -> {
                val config = when (connection?.type) {
                    ToolConnectionType.FIRECRAWL -> WebSearchProviderConfig(
                        provider = WebSearchProvider.FIRECRAWL,
                        bearerToken = connection.secretRef?.let(secretVault::retrieveSecret)?.let { String(it, StandardCharsets.UTF_8) }.orEmpty(),
                        endpointUrl = connection.endpointUrl.orEmpty()
                    )
                    ToolConnectionType.PERPLEXITY -> WebSearchProviderConfig(
                        provider = WebSearchProvider.PERPLEXITY,
                        bearerToken = connection.secretRef?.let(secretVault::retrieveSecret)?.let { String(it, StandardCharsets.UTF_8) }.orEmpty(),
                        endpointUrl = connection.endpointUrl.orEmpty()
                    )
                    ToolConnectionType.EXA -> WebSearchProviderConfig(
                        provider = WebSearchProvider.EXA,
                        bearerToken = connection.secretRef?.let(secretVault::retrieveSecret)?.let { String(it, StandardCharsets.UTF_8) }.orEmpty(),
                        endpointUrl = connection.endpointUrl.orEmpty()
                    )
                    else -> WebSearchProviderConfig(
                        provider = WebSearchProvider.AUTO,
                        bearerToken = "",
                        endpointUrl = "http://127.0.0.1:8000/search"
                    )
                }
                WebSearchTool(config = config, networkClient = networkClient)
            }
            else -> null
        } ?: return null

        return tool.resolved(
            connectionUid = connection?.connectionUid,
            connectionName = connection?.name,
            modelToolName = toolName
        )
    }

    private suspend fun resolveMcpTools(
        connection: ToolConnection,
        bindings: List<AgentToolBindingWithConnection>
    ): List<ResolvedAgentTool> {
        val activeNames = bindings.map { it.binding.toolName }.toSet()
        val tools = discoverMcpTools(connection).filter { it.name in activeNames }
        return tools.map { mcpTool ->
            val agentTool = object : AgentTool {
                override val definition: AgentToolDefinition = AgentToolDefinition(
                    name = mcpTool.name,
                    description = mcpTool.description.orEmpty(),
                    inputSchema = McpToolMapper.toJsonSchema(mcpTool.inputSchema)
                )

                override suspend fun execute(arguments: JsonObject): AgentToolResult {
                    return executeMcpToolWithRefresh(connection, mcpTool.name, arguments)
                }
            }
            agentTool.resolved(
                connectionUid = connection.connectionUid,
                connectionName = connection.name,
                modelToolName = mcpTool.name
            )
        }
    }

    private suspend fun executeMcpToolWithRefresh(
        connection: ToolConnection,
        toolName: String,
        arguments: JsonObject
    ): AgentToolResult {
        val config = mcpConfig(connection)
        return try {
            val result = mcpClientManager.executeTool(config, toolName, arguments)
            McpToolMapper.toAgentToolResult(result)
        } catch (error: Exception) {
            if (connection.authType != ToolConnectionAuthType.OAUTH || !error.isUnauthorized()) {
                AgentToolResult(
                    content = listOf(ToolResultContent(type = "text", text = "MCP tool execution failed: ${error.message}")),
                    isError = true
                )
            } else {
                try {
                    val refreshedConfig = mcpConfig(
                        connection,
                        forceOAuthRefresh = true,
                        rejectedAuthorizationHeader = config.authorizationHeader
                    )
                    val result = mcpClientManager.executeTool(refreshedConfig, toolName, arguments)
                    McpToolMapper.toAgentToolResult(result)
                } catch (refreshError: Exception) {
                    AgentToolResult(
                        content = listOf(ToolResultContent(type = "text", text = "MCP tool execution failed: ${refreshError.message}")),
                        isError = true
                    )
                }
            }
        }
    }

    private suspend fun mcpConfig(
        connection: ToolConnection,
        forceOAuthRefresh: Boolean = false,
        rejectedAuthorizationHeader: String? = null
    ): ChatMcpToolConfig.ServerConfig {
        val authHeader = when (connection.authType) {
            ToolConnectionAuthType.BEARER -> {
                connection.secretRef?.let(secretVault::retrieveSecret)?.let { secret ->
                    "Bearer ${String(secret, StandardCharsets.UTF_8)}"
                }
            }
            ToolConnectionAuthType.API_KEY -> {
                connection.secretRef?.let(secretVault::retrieveSecret)?.let { secret ->
                    String(secret, StandardCharsets.UTF_8)
                }
            }
            ToolConnectionAuthType.OAUTH -> {
                mcpOAuthCoordinator.getValidAccessToken(
                    connectionUid = connection.connectionUid,
                    forceRefresh = forceOAuthRefresh,
                    rejectedToken = rejectedAuthorizationHeader?.removePrefix("Bearer ")?.trim()
                )?.let { token -> "Bearer $token" }
            }
            else -> null
        }

        return ChatMcpToolConfig.ServerConfig(
            connectionUid = connection.connectionUid,
            endpointUrl = requireNotNull(connection.endpointUrl),
            authorizationHeader = authHeader,
            allowCleartext = connection.allowCleartext
        )
    }

    private fun sanitizeToolNames(tools: List<ResolvedAgentTool>): List<ResolvedAgentTool> {
        val counts = mutableMapOf<String, Int>()
        return tools.map { resolved ->
            val base = sanitizeModelToolName(resolved.modelToolName)
            val index = counts.getOrDefault(base, 0)
            counts[base] = index + 1
            val finalName = if (index == 0) base else "${base}_$index"
            val updatedTool = object : AgentTool {
                override val definition: AgentToolDefinition = resolved.tool.definition.copy(name = finalName)
                override suspend fun execute(arguments: JsonObject): AgentToolResult = resolved.tool.execute(arguments)
            }
            resolved.copy(
                tool = updatedTool,
                modelToolName = finalName
            )
        }
    }

    private fun sanitizeModelToolName(name: String): String {
        val replaced = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return replaced.take(64).ifEmpty { "tool" }
    }

    private fun AgentTool.resolved(
        connectionUid: String?,
        connectionName: String?,
        modelToolName: String
    ): ResolvedAgentTool = ResolvedAgentTool(
        tool = this,
        connectionUid = connectionUid,
        connectionName = connectionName,
        realToolName = definition.name,
        modelToolName = modelToolName
    )

    private fun Throwable.isUnauthorized(): Boolean {
        return this is StreamableHttpError && statusCode == 401
    }
}
