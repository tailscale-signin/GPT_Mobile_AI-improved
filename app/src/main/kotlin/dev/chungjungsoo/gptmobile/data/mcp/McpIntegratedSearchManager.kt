package dev.chungjungsoo.gptmobile.data.mcp

import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State representing MCPSearch integrated search configuration and individual tool toggles.
 */
data class McpSearchToolToggle(
    val toolName: String,
    val isEnabled: Boolean = true
)

data class McpIntegratedSearchConfig(
    val isIntegratedSearchEnabled: Boolean = true,
    val toolToggles: Map<String, Boolean> = defaultToolToggles(),
    val maxTools: Int? = null
) {
    fun isToolEnabled(toolName: String): Boolean {
        if (!isIntegratedSearchEnabled) return false
        return toolToggles[toolName] ?: true
    }

    /**
     * Applies this search configuration to an existing [ChatMcpToolConfig],
     * carrying over any maxTools constraint and active tool definitions.
     */
    fun applyToChatMcpToolConfig(baseConfig: ChatMcpToolConfig): ChatMcpToolConfig {
        // Apply only search-tool restrictions; preserve every unrelated chat toggle.
        val disabledSearchTools = toolToggles.filter { (name, enabled) ->
            !isIntegratedSearchEnabled || !enabled
        }.keys
        return baseConfig.copy(maxTools = maxTools ?: baseConfig.maxTools).let { config ->
            disabledSearchTools.fold(config) { current, name -> current.withToolDisabled(name) }
        }
    }

    companion object {
        fun defaultToolToggles(): Map<String, Boolean> = McpSearchToolSet.tools.associate { it.name to true }
    }
}

/**
 * Interface for managing integrated search using MCPSearch.
 * Turned ON by default, with capabilities to toggle the search integration
 * and individual MCP search tools on or off per model/session.
 */
interface McpIntegratedSearchRepository {
    val searchConfigFlow: Flow<McpIntegratedSearchConfig>
    fun getSearchConfig(): McpIntegratedSearchConfig
    fun setIntegratedSearchEnabled(enabled: Boolean)
    fun setToolEnabled(toolName: String, enabled: Boolean)
    fun setMaxTools(limit: Int?)
    fun resetToDefaults()
    fun getActiveTools(): List<McpBuiltinTool>
}

class McpIntegratedSearchManager(
    initialConfig: McpIntegratedSearchConfig = McpIntegratedSearchConfig()
) : McpIntegratedSearchRepository {

    private val _searchConfigFlow = MutableStateFlow(initialConfig)
    override val searchConfigFlow: Flow<McpIntegratedSearchConfig> = _searchConfigFlow.asStateFlow()

    override fun getSearchConfig(): McpIntegratedSearchConfig = _searchConfigFlow.value

    override fun setIntegratedSearchEnabled(enabled: Boolean) {
        _searchConfigFlow.value = _searchConfigFlow.value.copy(isIntegratedSearchEnabled = enabled)
    }

    override fun setToolEnabled(toolName: String, enabled: Boolean) {
        val currentToggles = _searchConfigFlow.value.toolToggles.toMutableMap()
        currentToggles[toolName] = enabled
        _searchConfigFlow.value = _searchConfigFlow.value.copy(toolToggles = currentToggles)
    }

    override fun setMaxTools(limit: Int?) {
        val clampedLimit = limit?.coerceAtLeast(0)
        _searchConfigFlow.value = _searchConfigFlow.value.copy(maxTools = clampedLimit)
    }

    override fun resetToDefaults() {
        _searchConfigFlow.value = McpIntegratedSearchConfig(
            isIntegratedSearchEnabled = true,
            toolToggles = McpIntegratedSearchConfig.defaultToolToggles(),
            maxTools = null
        )
    }

    override fun getActiveTools(): List<McpBuiltinTool> {
        val config = _searchConfigFlow.value
        if (!config.isIntegratedSearchEnabled) return emptyList()

        val enabledTools = McpSearchToolSet.tools.filter { tool ->
            config.toolToggles[tool.name] ?: true
        }

        return if (config.maxTools != null) {
            enabledTools.take(config.maxTools)
        } else {
            enabledTools
        }
    }
}
