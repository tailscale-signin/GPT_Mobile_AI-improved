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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
    val profiles: List<PlatformV2> = emptyList(),
    val featureSettings: AppFeatureSettings = AppFeatureSettings(),
    val connectionHealth: Map<String, ToolConnectionHealth> = emptyMap(),
    val isCreatingConnection: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class ToolConnectionsViewModel @Inject constructor(
    private val repository: ToolConnectionRepository,
    private val settingRepository: SettingRepository,
    private val agentToolResolver: AgentToolResolver? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ToolConnectionsUiState())
    val uiState: StateFlow<ToolConnectionsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                repository.observeAllConnections(),
                repository.observeAllBindings(),
                settingRepository.observePlatformV2s(),
                settingRepository.observeFeatureSettings()
            ) { connections, bindings, profiles, featureSettings ->
                _uiState.update { current ->
                    current.copy(
                        connections = connections,
                        bindings = bindings,
                        profiles = profiles,
                        featureSettings = featureSettings
                    )
                }
            }.collect {
                probeConnections()
            }
        }
    }

    fun addMcpConnection(
        name: String,
        alias: String,
        endpointUrl: String,
        authType: String,
        credential: String,
        allowCleartext: Boolean = false
    ) {
        val trimmedName = name.trim()
        val trimmedAlias = normalizeAlias(alias)
        val trimmedUrl = endpointUrl.trim()

        if (trimmedName.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Connection name cannot be empty.") }
            return
        }
        if (!isValidAlias(trimmedAlias)) {
            _uiState.update {
                it.copy(
                    errorMessage = "Tool alias '$trimmedAlias' is invalid. Use lowercase letters, digits, or underscore, starting with a letter."
                )
            }
            return
        }
        if (!isValidMcpEndpoint(trimmedUrl, allowCleartext)) {
            _uiState.update {
                it.copy(
                    errorMessage = "Endpoint URL must be a valid HTTP/HTTPS Streamable HTTP endpoint. Cleartext HTTP requires explicit approval."
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isCreatingConnection = true, errorMessage = null) }
            try {
                repository.createConnection(
                    name = trimmedName,
                    type = ToolConnectionType.MCP,
                    endpointUrl = trimmedUrl,
                    alias = trimmedAlias,
                    authType = authType,
                    credential = credential.trim().takeIf { it.isNotEmpty() }
                )
            } catch (t: Throwable) {
                _uiState.update { it.copy(errorMessage = t.message ?: "Failed to save MCP connection.") }
            } finally {
                _uiState.update { it.copy(isCreatingConnection = false) }
            }
        }
    }

    fun updateConnection(
        connection: ToolConnection,
        credential: String? = null
    ) {
        viewModelScope.launch {
            try {
                repository.updateConnection(connection, credential)
            } catch (t: Throwable) {
                _uiState.update { it.copy(errorMessage = t.message ?: "Failed to update connection.") }
            }
        }
    }

    fun deleteConnection(connectionUid: String) {
        viewModelScope.launch {
            try {
                repository.deleteConnection(connectionUid)
            } catch (t: Throwable) {
                _uiState.update { it.copy(errorMessage = t.message ?: "Failed to delete connection.") }
            }
        }
    }

    fun setBindingEnabled(
        profileUid: String,
        connectionUid: String?,
        toolName: String,
        enabled: Boolean
    ) {
        viewModelScope.launch {
            try {
                repository.setBindingEnabled(profileUid, connectionUid, toolName, enabled)
            } catch (t: Throwable) {
                _uiState.update { it.copy(errorMessage = t.message ?: "Failed to update tool binding.") }
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
                                        message = "Connected, but the server reported no tools.",
                                        checkedAt = checkedAt
                                    )
                                } else {
                                    ToolConnectionHealth(
                                        status = ToolConnectionHealthStatus.ONLINE,
                                        toolCount = tools.size,
                                        message = "${tools.size} remote tool${if (tools.size == 1) "" else "s"} available",
                                        checkedAt = checkedAt
                                    )
                                }
                            },
                            onFailure = { error ->
                                val text = error.message.orEmpty()
                                val limited = text.contains("401") ||
                                    text.contains("403") ||
                                    text.contains("auth", ignoreCase = true) ||
                                    text.contains("permission", ignoreCase = true)
                                ToolConnectionHealth(
                                    status = if (limited) ToolConnectionHealthStatus.LIMITED else ToolConnectionHealthStatus.OFFLINE,
                                    message = text.ifBlank { "Unable to reach the MCP server." },
                                    checkedAt = checkedAt
                                )
                            }
                        )
                    _uiState.update { state ->
                        state.copy(connectionHealth = state.connectionHealth + (connection.connectionUid to health))
                    }
                }
            }
    }

    companion object {
        fun normalizeAlias(input: String): String = input.trim().lowercase()

        fun isValidAlias(alias: String): Boolean {
            val normalized = normalizeAlias(alias)
            return normalized.isNotEmpty() &&
                normalized.length <= 40 &&
                normalized.matches(Regex("^[a-z][a-z0-9_]*$"))
        }

        fun isValidMcpEndpoint(rawUrl: String, allowCleartext: Boolean = false): Boolean {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty() || trimmed.length > 2000) return false
            return runCatching {
                val uri = URI.create(trimmed)
                val scheme = uri.scheme?.lowercase()
                val host = uri.host
                if (host.isNullOrBlank()) return@runCatching false
                when (scheme) {
                    "https" -> true
                    "http" -> allowCleartext || isLoopbackOrPrivateHost(host)
                    else -> false
                }
            }.getOrDefault(false)
        }

        private fun isLoopbackOrPrivateHost(host: String): Boolean {
            val normalized = host.lowercase()
            return normalized == "localhost" ||
                normalized == "127.0.0.1" ||
                normalized == "::1" ||
                normalized.startsWith("192.168.") ||
                normalized.startsWith("10.") ||
                normalized.matches(Regex("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*"))
        }
    }
}
