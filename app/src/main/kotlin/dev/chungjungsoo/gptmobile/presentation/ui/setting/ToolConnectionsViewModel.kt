package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.agent.tool.AgentToolResolver
import dev.chungjungsoo.gptmobile.data.database.entity.AgentToolBinding
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import java.net.URI
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ToolConnectionHealthStatus {
    IDLE,
    CHECKING,
    ONLINE,
    LIMITED,
    OFFLINE
}

data class ToolConnectionHealth(
    val status: ToolConnectionHealthStatus = ToolConnectionHealthStatus.IDLE,
    val toolCount: Int? = null,
    val message: String? = null,
    val checkedAt: Long? = null
)

data class ToolConnectionsUiState(
    val connections: List<ToolConnection> = emptyList(),
    val bindings: List<AgentToolBinding> = emptyList(),
    val platforms: List<PlatformV2> = emptyList(),
    val featureSettings: AppFeatureSettings = AppFeatureSettings(),
    val selectedPlatformUid: String? = null,
    val selectedConnectionUid: String? = null,
    val connectionHealth: Map<String, ToolConnectionHealth> = emptyMap(),
    val isSaving: Boolean = false,
    val isTesting: Boolean = false,
    val lastTestResult: String? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class ToolConnectionsViewModel @Inject constructor(
    private val toolConnectionRepository: ToolConnectionRepository,
    private val settingRepository: SettingRepository,
    private val agentToolResolver: AgentToolResolver? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ToolConnectionsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                toolConnectionRepository.observeAllConnections(),
                toolConnectionRepository.observeAllBindings(),
                settingRepository.getAllPlatformsFlow(),
                settingRepository.appFeatureSettingsFlow
            ) { connections, bindings, platforms, featureSettings ->
                ToolConnectionsUiState(
                    connections = connections,
                    bindings = bindings,
                    platforms = platforms,
                    featureSettings = featureSettings,
                    selectedPlatformUid = _uiState.value.selectedPlatformUid ?: platforms.firstOrNull()?.uid,
                    selectedConnectionUid = _uiState.value.selectedConnectionUid ?: connections.firstOrNull()?.connectionUid,
                    connectionHealth = _uiState.value.connectionHealth,
                    isSaving = _uiState.value.isSaving,
                    isTesting = _uiState.value.isTesting,
                    lastTestResult = _uiState.value.lastTestResult,
                    errorMessage = _uiState.value.errorMessage
                )
            }.collect { newState ->
                val prevConnections = _uiState.value.connections
                _uiState.value = newState
                if (prevConnections.isEmpty() && newState.connections.isNotEmpty()) {
                    probeConnections(newState.connections)
                }
            }
        }
    }

    fun selectPlatform(platformUid: String) {
        _uiState.update { it.copy(selectedPlatformUid = platformUid) }
    }

    fun selectConnection(connectionUid: String?) {
        _uiState.update { it.copy(selectedConnectionUid = connectionUid) }
    }

    fun setMasterToolsSwitch(enabled: Boolean) {
        viewModelScope.launch {
            val current = _uiState.value.featureSettings
            settingRepository.setAppFeatureSettings(current.copy(enableAgentTools = enabled))
        }
    }

    fun setPlatformToolsOverride(platformUid: String, enabled: Boolean) {
        viewModelScope.launch {
            val platform = _uiState.value.platforms.find { it.uid == platformUid } ?: return@launch
            val newPlatform = platform.copy(enableAgentTools = enabled)
            settingRepository.updatePlatform(newPlatform)
        }
    }

    fun saveConnection(connection: ToolConnection) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            try {
                validateConnection(connection)
                toolConnectionRepository.saveConnection(connection)
                _uiState.update { it.copy(isSaving = false) }
                probeConnection(connection)
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, errorMessage = e.message) }
            }
        }
    }

    fun deleteConnection(connectionUid: String) {
        viewModelScope.launch {
            try {
                toolConnectionRepository.deleteConnection(connectionUid)
                if (_uiState.value.selectedConnectionUid == connectionUid) {
                    _uiState.update { it.copy(selectedConnectionUid = null) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message) }
            }
        }
    }

    fun toggleConnectionEnabled(connectionUid: String, enabled: Boolean) {
        viewModelScope.launch {
            val conn = _uiState.value.connections.find { it.connectionUid == connectionUid } ?: return@launch
            toolConnectionRepository.saveConnection(conn.copy(isEnabled = enabled))
            if (enabled) {
                probeConnection(conn.copy(isEnabled = true))
            }
        }
    }

    fun setBindingEnabled(platformUid: String, connectionUid: String, enabled: Boolean) {
        viewModelScope.launch {
            val existing = _uiState.value.bindings.find {
                it.platformUid == platformUid && it.connectionUid == connectionUid
            }
            if (existing != null) {
                toolConnectionRepository.saveBinding(existing.copy(isEnabled = enabled))
            } else {
                toolConnectionRepository.saveBinding(
                    AgentToolBinding(
                        platformUid = platformUid,
                        connectionUid = connectionUid,
                        isEnabled = enabled
                    )
                )
            }
        }
    }

    fun setBindingToolFilter(platformUid: String, connectionUid: String, allowedTools: List<String>) {
        viewModelScope.launch {
            val existing = _uiState.value.bindings.find {
                it.platformUid == platformUid && it.connectionUid == connectionUid
            } ?: AgentToolBinding(platformUid = platformUid, connectionUid = connectionUid)
            toolConnectionRepository.saveBinding(existing.copy(allowedTools = allowedTools))
        }
    }

    fun testConnection(connection: ToolConnection) {
        viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true, lastTestResult = null, errorMessage = null) }
            try {
                validateConnection(connection)
                val resolver = agentToolResolver
                if (resolver == null) {
                    _uiState.update {
                        it.copy(
                            isTesting = false,
                            lastTestResult = "AgentToolResolver not available in this build."
                        )
                    }
                    return@launch
                }
                val tools = resolver.discoverMcpTools(connection)
                _uiState.update {
                    it.copy(
                        isTesting = false,
                        lastTestResult = "Success: discovered ${tools.size} tool(s): ${tools.joinToString(", ") { t -> t.name }}"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isTesting = false,
                        lastTestResult = "Connection failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun clearError() = _uiState.update { it.copy(errorMessage = null) }

    fun probeConnections(connections: List<ToolConnection> = _uiState.value.connections) {
        val resolver = agentToolResolver ?: return
        connections
            .filter { it.type == ToolConnectionType.MCP }
            .forEach { connection ->
                if (_uiState.value.connectionHealth[connection.connectionUid]?.status == ToolConnectionHealthStatus.CHECKING) {
                    return@forEach
                }
                _uiState.update { state ->
                    state.copy(
                        connectionHealth = state.connectionHealth + (
                            connection.connectionUid to ToolConnectionHealth(
                                status = ToolConnectionHealthStatus.CHECKING,
                                message = "Checking Streamable HTTP server…"
                            )
                        )
                    )
                }
                viewModelScope.launch {
                    val checkedAt = System.currentTimeMillis()
                    val health = runCatching { resolver.discoverMcpTools(connection) }
                            .fold(
                            onSuccess = { tools ->
                                if (tools.isEmpty()) {
                                    ToolConnectionHealth(
                                        status = ToolConnectionHealthStatus.LIMITED,
                                        toolCount = 0,
                                        message = "Server responded but advertised no tools",
                                        checkedAt = checkedAt
                                    )
                                } else {
                                    ToolConnectionHealth(
                                        status = ToolConnectionHealthStatus.ONLINE,
                                        toolCount = tools.size,
                                        message = "Online (${tools.size} tool${if (tools.size == 1) "" else "s"})",
                                        checkedAt = checkedAt
                                    )
                                }
                            },
                            onFailure = { error ->
                                ToolConnectionHealth(
                                    status = ToolConnectionHealthStatus.OFFLINE,
                                    message = error.message ?: "Failed to reach server",
                                    checkedAt = checkedAt
                                )
                            }
                            )
                    _uiState.update { state ->
                        state.copy(
                            connectionHealth = state.connectionHealth + (
                                connection.connectionUid to health
                            )
                        )
                    }
                }
            }
    }

    fun probeConnection(connection: ToolConnection) = probeConnections(listOf(connection))

    private fun validateConnection(connection: ToolConnection) {
        require(connection.name.isNotBlank()) { "Connection name cannot be empty." }
        require(connection.connectionUid.isNotBlank()) { "Connection UID cannot be empty." }
        if (connection.type == ToolConnectionType.MCP) {
            val endpoint = connection.endpointUrl?.trim().orEmpty()
            require(endpoint.isNotBlank()) { "MCP endpoint URL cannot be empty." }
            val uri = runCatching { URI(endpoint) }.getOrNull()
            require(uri != null && uri.isAbsolute) { "Invalid MCP endpoint URL: must be an absolute URL (e.g. http://10.0.2.2:8000/mcp)." }
            require(uri.scheme?.lowercase() in listOf("http", "https")) { "MCP endpoint URL must start with http:// or https://." }
        }
    }
}
