package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.agent.tool.AgentToolResolver
import dev.chungjungsoo.gptmobile.data.agent.tool.namespaceMcpToolName
import dev.chungjungsoo.gptmobile.data.catalog.CatalogEntry
import dev.chungjungsoo.gptmobile.data.database.dao.ToolConnectionDao
import dev.chungjungsoo.gptmobile.data.database.entity.BuiltInAgentTool
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.localmodel.LocalModelStatus
import dev.chungjungsoo.gptmobile.data.localmodel.SocVariantResolver
import dev.chungjungsoo.gptmobile.data.localruntime.AcceleratorOption
import dev.chungjungsoo.gptmobile.data.localruntime.LocalAccelerators
import dev.chungjungsoo.gptmobile.data.localruntime.MAX_HIGH_RAM_CONTEXT_TOKENS
import dev.chungjungsoo.gptmobile.data.localruntime.localSamplingDefaults
import dev.chungjungsoo.gptmobile.data.localruntime.resolvedEngineMaxTokens
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.GeminiSafetySettings
import dev.chungjungsoo.gptmobile.data.repository.LocalModelRepository
import dev.chungjungsoo.gptmobile.data.repository.ModelCatalogRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolBindingSelection
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import dev.chungjungsoo.gptmobile.di.DeviceRamGb
import dev.chungjungsoo.gptmobile.di.DeviceSocModel
import dev.chungjungsoo.gptmobile.presentation.ui.setup.DownloadedLocalModelOption
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class PlatformSettingViewModel @Inject constructor(
    private val settingRepository: SettingRepository,
    private val toolConnectionRepository: ToolConnectionRepository,
    private val catalogRepository: ModelCatalogRepository,
    private val localModelRepository: LocalModelRepository,
    private val toolConnectionDao: ToolConnectionDao,
    private val secretVault: SecretVault,
    savedStateHandle: SavedStateHandle,
    @DeviceSocModel private val deviceSocModel: String = "",
    @DeviceRamGb private val deviceRamGb: Long = 8L
) : ViewModel() {

    private val platformUid: String = checkNotNull(savedStateHandle[PLATFORM_ID_KEY])

    private val _platformState = MutableStateFlow<PlatformV2?>(null)
    val platformState: StateFlow<PlatformV2?> = _platformState.asStateFlow()

    private val _dialogState = MutableStateFlow(DialogState())
    val dialogState: StateFlow<DialogState> = _dialogState.asStateFlow()

    private val _toolBindingState = MutableStateFlow(ToolBindingState())
    val toolBindingState: StateFlow<ToolBindingState> = _toolBindingState.asStateFlow()

    private val _userMessage = MutableStateFlow<Int?>(null)
    val userMessage: StateFlow<Int?> = _userMessage.asStateFlow()

    private val _catalogEntries = MutableStateFlow<List<CatalogEntry>>(emptyList())
    val catalogEntries: StateFlow<List<CatalogEntry>> = _catalogEntries.asStateFlow()

    private val _downloadedLocalModels = MutableStateFlow<List<DownloadedLocalModelOption>>(emptyList())
    val downloadedLocalModels: StateFlow<List<DownloadedLocalModelOption>> = _downloadedLocalModels.asStateFlow()

    val acceleratorOptions: StateFlow<List<AcceleratorOption>> = combine(
        _platformState,
        _catalogEntries,
        _downloadedLocalModels
    ) { platform, entries, localModels ->
        if (platform?.compatibleType != ClientType.LITERT_LM) {
            emptyList()
        } else {
            val entry = entries.firstOrNull { it.id == platform.model }
            val downloaded = localModels.firstOrNull { it.catalogEntryId == platform.model }
            LocalAccelerators.optionsFor(
                entry = entry,
                deviceSocModel = deviceSocModel,
                isModelDownloaded = downloaded?.isDownloaded == true
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    private var mcpDiscoveryJob: Job? = null

    init {
        loadPlatform()
        loadToolBindings()
        loadCatalogEntries()
        loadDownloadedLocalModels()
    }

    private fun loadCatalogEntries() {
        viewModelScope.launch {
            _catalogEntries.update { catalogRepository.fetchCatalog() }
        }
    }

    private fun loadDownloadedLocalModels() {
        viewModelScope.launch {
            localModelRepository.localModels.collect { models ->
                val ready = models
                    .filter { it.status == LocalModelStatus.READY }
                    .map { DownloadedLocalModelOption(catalogEntryId = it.catalogEntryId, isDownloaded = true) }
                _downloadedLocalModels.update { ready }
            }
        }
    }

    private fun loadPlatform() {
        viewModelScope.launch {
            val platforms = settingRepository.fetchPlatformV2s()
            val platform = platforms.firstOrNull { it.uid == platformUid }
            _platformState.update { platform }
        }
    }

    fun loadToolBindings() {
        viewModelScope.launch {
            runCatching {
                val connections = toolConnectionRepository.listConnections()
                val bindings = toolConnectionRepository.listBindingsByProfile(platformUid)
                val mcpConnections = connections.filter { it.type == ToolConnectionType.MCP }
                val mcpConnectionUids = mcpConnections.map { it.connectionUid }.toSet()
                val searchConnections = connections.filter { it.type in WEB_SEARCH_TYPES }
                val searchConnectionUids = searchConnections.map { it.connectionUid }.toSet()
                ToolBindingState(
                    searchConnections = searchConnections,
                    selectedSearchConnectionUid = bindings.firstOrNull {
                        it.toolName == WEB_SEARCH_TOOL && it.connectionUid in searchConnectionUids
                    }?.connectionUid,
                    readUrlEnabled = bindings.any { it.toolName == BuiltInAgentTool.READ_URL && it.connectionUid == null },
                    mcpConnections = mcpConnections,
                    selectedMcpTools = bindings.mapNotNull { binding ->
                        binding.connectionUid?.takeIf { it in mcpConnectionUids }?.let { ToolBindingSelection(it, binding.toolName) }
                    }.toSet(),
                    errorMessage = null
                )
            }.onSuccess { state ->
                _toolBindingState.update { state }
            }.onFailure(::showToolError)
        }
    }

    fun toggleEnabled() {
        val platform = _platformState.value ?: return
        val enabling = !platform.enabled
        if (enabling && platform.compatibleType == ClientType.LITERT_LM) {
            val downloaded = _downloadedLocalModels.value.any { it.catalogEntryId == platform.model && it.isDownloaded }
            if (!downloaded) {
                _userMessage.update { R.string.local_platform_enable_model_not_ready }
                return
            }
        }
        updatePlatform(platform.copy(enabled = enabling))
    }

    fun clearUserMessage() {
        _userMessage.update { null }
    }

    fun openPlatformNameDialog() = _dialogState.update { it.copy(isPlatformNameDialogOpen = true) }
    fun closePlatformNameDialog() = _dialogState.update { it.copy(isPlatformNameDialogOpen = false) }

    fun openApiUrlDialog() = _dialogState.update { it.copy(isApiUrlDialogOpen = true) }
    fun closeApiUrlDialog() = _dialogState.update { it.copy(isApiUrlDialogOpen = false) }

    fun openApiTokenDialog() = _dialogState.update { it.copy(isApiTokenDialogOpen = true) }
    fun closeApiTokenDialog() = _dialogState.update { it.copy(isApiTokenDialogOpen = false) }

    fun openApiModelDialog() = _dialogState.update { it.copy(isApiModelDialogOpen = true) }
    fun closeApiModelDialog() = _dialogState.update { it.copy(isApiModelDialogOpen = false) }

    fun openTemperatureDialog() = _dialogState.update { it.copy(isTemperatureDialogOpen = true) }
    fun closeTemperatureDialog() = _dialogState.update { it.copy(isTemperatureDialogOpen = false) }

    fun openTopPDialog() = _dialogState.update { it.copy(isTopPDialogOpen = true) }
    fun closeTopPDialog() = _dialogState.update { it.copy(isTopPDialogOpen = false) }

    fun openTopKDialog() = _dialogState.update { it.copy(isTopKDialogOpen = true) }
    fun closeTopKDialog() = _dialogState.update { it.copy(isTopKDialogOpen = false) }

    fun openMaxTokensDialog() = _dialogState.update { it.copy(isMaxTokensDialogOpen = true) }
    fun closeMaxTokensDialog() = _dialogState.update { it.copy(isMaxTokensDialogOpen = false) }

    fun openAcceleratorDialog() = _dialogState.update { it.copy(isAcceleratorDialogOpen = true) }
    fun closeAcceleratorDialog() = _dialogState.update { it.copy(isAcceleratorDialogOpen = false) }

    fun openSystemPromptDialog() = _dialogState.update { it.copy(isSystemPromptDialogOpen = true) }
    fun closeSystemPromptDialog() = _dialogState.update { it.copy(isSystemPromptDialogOpen = false) }

    fun openTimeoutDialog() = _dialogState.update { it.copy(isTimeoutDialogOpen = true) }
    fun closeTimeoutDialog() = _dialogState.update { it.copy(isTimeoutDialogOpen = false) }

    fun openGeminiSafetyDialog() = _dialogState.update { it.copy(isGeminiSafetyDialogOpen = true) }
    fun closeGeminiSafetyDialog() = _dialogState.update { it.copy(isGeminiSafetyDialogOpen = false) }

    fun openDeleteDialog() = _dialogState.update { it.copy(isDeleteDialogOpen = true) }
    fun closeDeleteDialog() = _dialogState.update { it.copy(isDeleteDialogOpen = false) }

    fun updatePlatformName(name: String) {
        _platformState.value?.let { platform ->
            updatePlatform(platform.copy(name = name))
            closePlatformNameDialog()
        }
    }

    fun updateApiUrl(url: String) {
        _platformState.value?.let { platform ->
            updatePlatform(platform.copy(url = url))
            closeApiUrlDialog()
        }
    }

    fun updateApiToken(token: String) {
        _platformState.value?.let { platform ->
            viewModelScope.launch {
                val ref = secretVault.write(platform.uid, token)
                updatePlatform(platform.copy(token = ref))
                closeApiTokenDialog()
            }
        }
    }

    suspend fun readApiToken(): String {
        val platform = _platformState.value ?: return ""
        return secretVault.read(platform.uid, platform.token).orEmpty()
    }

    fun updateApiModel(model: String) {
        _platformState.value?.let { platform ->
            val updated = if (platform.compatibleType == ClientType.LITERT_LM) {
                reseedLocalModelDefaults(platform, model)
            } else {
                platform.copy(model = model)
            }
            updatePlatform(updated)
            closeApiModelDialog()
        }
    }

    private fun reseedLocalModelDefaults(platform: PlatformV2, catalogEntryId: String): PlatformV2 {
        val entry = _catalogEntries.value.firstOrNull { it.id == catalogEntryId }
        val defaults = entry?.let { localSamplingDefaults(it, deviceSocModel, deviceRamGb) }
        return platform.copy(
            model = catalogEntryId,
            temperature = defaults?.temperature ?: platform.temperature,
            topP = defaults?.topP ?: platform.topP,
            topK = defaults?.topK ?: platform.topK,
            maxTokens = defaults?.maxTokens ?: platform.maxTokens,
            accelerator = defaults?.accelerator ?: platform.accelerator
        )
    }

    fun updateTemperature(temperature: Float?) {
        _platformState.value?.let { platform ->
            updatePlatform(platform.copy(temperature = temperature))
            closeTemperatureDialog()
        }
    }

    fun updateTopP(topP: Float?) {
        _platformState.value?.let { platform ->
            updatePlatform(platform.copy(topP = topP))
            closeTopPDialog()
        }
    }

    fun updateTopK(topK: Int?) {
        _platformState.value?.let { platform ->
            updatePlatform(platform.copy(topK = topK?.coerceIn(MIN_TOP_K, MAX_TOP_K)))
            closeTopKDialog()
        }
    }

    fun updateMaxTokens(maxTokens: Int?) {
        _platformState.value?.let { platform ->
            val capped = maxTokens?.let { requested ->
                resolvedEngineMaxTokens(
                    requestedMaxTokens = requested.coerceIn(MIN_MAX_TOKENS, DEFAULT_MAX_TOKENS_CAP),
                    accelerator = platform.accelerator.orEmpty(),
                    entry = catalogEntryFor(platform),
                    deviceSocModel = deviceSocModel,
                    deviceRamGb = deviceRamGb
                )
            }
            updatePlatform(platform.copy(maxTokens = capped))
            closeMaxTokensDialog()
        }
    }

    fun maxTokensCap(): Int {
        val platform = _platformState.value ?: return DEFAULT_MAX_TOKENS_CAP
        if (platform.compatibleType != ClientType.LITERT_LM) {
            return DEFAULT_MAX_TOKENS_CAP
        }
        val entry = catalogEntryFor(platform)
        if (LocalAccelerators.normalize(platform.accelerator) == LocalAccelerators.NPU && entry != null) {
            val variantLimit = SocVariantResolver.resolve(entry, deviceSocModel).contextSize
            if (variantLimit > 0) {
                return variantLimit
            }
        }
        if (deviceRamGb >= 12L) {
            return MAX_HIGH_RAM_CONTEXT_TOKENS
        }
        return entry?.defaults?.maxTokens ?: MAX_HIGH_RAM_CONTEXT_TOKENS
    }

    private fun catalogEntryFor(platform: PlatformV2): CatalogEntry? = _catalogEntries.value.firstOrNull { it.id == platform.model }

    fun updateAccelerator(accelerator: String) {
        _platformState.value?.let { platform ->
            val options = acceleratorOptions.value
            val option = options.firstOrNull { it.accelerator.equals(accelerator, ignoreCase = true) }
            if (option == null || !option.enabled) {
                closeAcceleratorDialog()
                return
            }
            val normalized = LocalAccelerators.normalize(accelerator)
            val adjustedMaxTokens = platform.maxTokens?.let { current ->
                resolvedEngineMaxTokens(
                    requestedMaxTokens = current,
                    accelerator = normalized,
                    entry = catalogEntryFor(platform),
                    deviceSocModel = deviceSocModel,
                    deviceRamGb = deviceRamGb
                )
            }
            updatePlatform(platform.copy(accelerator = normalized, maxTokens = adjustedMaxTokens))
            closeAcceleratorDialog()
        }
    }

    fun updateSystemPrompt(systemPrompt: String) {
        _platformState.value?.let { platform ->
            updatePlatform(platform.copy(systemPrompt = systemPrompt.ifBlank { null }))
            closeSystemPromptDialog()
        }
    }

    fun updateTimeout(timeoutSeconds: Int) {
        _platformState.value?.let { platform ->
            updatePlatform(platform.copy(timeout = timeoutSeconds.coerceAtLeast(0)))
            closeTimeoutDialog()
        }
    }

    fun updateGeminiSafetySettings(settings: GeminiSafetySettings) {
        _platformState.value?.let { platform ->
            updatePlatform(platform.copy(geminiSafetySettings = settings))
            closeGeminiSafetyDialog()
        }
    }

    fun deletePlatform(onDeleted: () -> Unit) {
        _platformState.value?.let { platform ->
            viewModelScope.launch {
                toolConnectionDao.deleteBindingsByProfile(platform.uid)
                secretVault.delete(platform.uid, platform.token)
                settingRepository.deletePlatformV2(platform)
                closeDeleteDialog()
                onDeleted()
            }
        }
    }

    private fun updatePlatform(platform: PlatformV2) {
        _platformState.update { platform }
        viewModelScope.launch {
            settingRepository.updatePlatformV2(platform)
        }
    }

    fun openSearchBackendDialog() {
        _toolBindingState.update { it.copy(isSearchBackendDialogOpen = true) }
    }

    fun closeSearchBackendDialog() {
        _toolBindingState.update { it.copy(isSearchBackendDialogOpen = false) }
    }

    fun selectSearchConnection(connectionUid: String?) {
        viewModelScope.launch {
            runCatching {
                toolConnectionRepository.setSearchBinding(platformUid, connectionUid)
            }.onSuccess {
                _toolBindingState.update {
                    it.copy(
                        selectedSearchConnectionUid = connectionUid,
                        isSearchBackendDialogOpen = false,
                        errorMessage = null
                    )
                }
            }.onFailure(::showToolError)
        }
    }

    fun toggleBuiltInTool(toolName: String, enabled: Boolean) {
        when (toolName) {
            WEB_SEARCH_TOOL -> {
                if (enabled) {
                    val fallback = _toolBindingState.value.searchConnections.firstOrNull()?.connectionUid
                    selectSearchConnection(fallback)
                } else {
                    selectSearchConnection(null)
                }
            }
            BuiltInAgentTool.READ_URL -> toggleReadUrl(enabled)
            else -> {
                viewModelScope.launch {
                    runCatching {
                        if (enabled) {
                            toolConnectionRepository.bindTool(platformUid, null, toolName)
                        } else {
                            toolConnectionRepository.unbindTool(platformUid, null, toolName)
                        }
                    }
                        .onSuccess {
                            _toolBindingState.update { it.copy(errorMessage = null) }
                        }
                        .onFailure(::showToolError)
                }
            }
        }
    }

    fun toggleReadUrl(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { toolConnectionRepository.setReadUrlBinding(platformUid, enabled) }
                .onSuccess {
                    _toolBindingState.update { it.copy(readUrlEnabled = enabled, errorMessage = null) }
                }
                .onFailure(::showToolError)
        }
    }

    fun openMcpToolsDialog() {
        mcpDiscoveryJob?.cancel()
        val connections = _toolBindingState.value.mcpConnections
        _toolBindingState.update {
            it.copy(
                isMcpToolsDialogOpen = true,
                isMcpToolsLoading = true,
                mcpToolOptions = emptyList>,
                pendingMcpTools = it.selectedMcpTools,
                errorMessage = null
            )
        }
        mcpDiscoveryJob = viewModelScope.launch {
            try {
                val results = coroutineScope {
                    connections.map { connection ->
                        async { connection to discoverMcpTools(connection) }
                    }.awaitAll()
                }
                val options = results.flatMap { (connection, result) ->
                    result.getOrDefault(emptyList()).map { tool ->
                        McpToolOption(
                            connectionUid = connection.connectionUid,
                            connectionName = connection.name,
                            toolName = tool.name,
                            modelToolName = namespaceMcpToolName(connection.alias, tool.name),
                            description = tool.description
                        )
                    }
                }.sortedWith(compareBy<McpToolOption> { it.connectionName }.thenBy { it.toolName })
                val failures = results.mapNotNull { (connection, result) ->
                    result.exceptionOrNull()?.let { "${connection.name}: ${it.message ?: "discovery failed"}" }
                }
                _toolBindingState.update {
                    it.copy(
                        isMcpToolsLoading = false,
                        mcpToolOptions = options,
                        errorMessage = failures.takeIf(List<String>::isNotEmpty)?.joinToString("\n")
                    )
                }
            } catch (error: CancellationException) {
                throw error
            }
        }
    }

    private suspend fun discoverMcpTools(connection: ToolConnection) = runCatching {
        val resolver = AgentToolResolver(
            connections = listOf(connection),
            authHeaderProvider = { secretVault.read(connection.connectionUid, connection.authSecretRef) }
        )
        resolver.discoverTools()
    }

    fun closeMcpToolsDialog() {
        mcpDiscoveryJob?.cancel()
        mcpDiscoveryJob = null
        _toolBindingState.update {
            it.copy(
                isMcpToolsDialogOpen = false,
                isMcpToolsLoading = false
            )
        }
    }

    fun toggleMcpTool(connectionUid: String, toolName: String) {
        _toolBindingState.update { state ->
            val selection = ToolBindingSelection(connectionUid, toolName)
            val updated = if (selection in state.pendingMcpTools) {
                state.pendingMcpTools - selection
            } else {
                state.pendingMcpTools + selection
            }
            state.copy(pendingMcpTools = updated)
        }
    }

    fun saveMcpTools() {
        val selections = _toolBindingState.value.pendingMcpTools.toList()
        viewModelScope.launch {
            runCatching { toolConnectionRepository.replaceMcpToolBindings(platformUid, selections) }
                .onSuccess {
                    _toolBindingState.update {
                        it.copy(
                            selectedMcpTools = selections.toSet(),
                            isMcpToolsDialogOpen = false,
                            errorMessage = null
                        )
                    }
                }
                .onFailure(::showToolError)
        }
    }

    private fun showToolError(error: Throwable) {
        _toolBindingState.update { it.copy(errorMessage = error.message ?: "Tool binding update failed.") }
    }

    data class DialogState(
        val isPlatformNameDialogOpen: Boolean = false,
        val isApiUrlDialogOpen: Boolean = false,
        val isApiTokenDialogOpen: Boolean = false,
        val isApiModelDialogOpen: Boolean = false,
        val isTemperatureDialogOpen: Boolean = false,
        val isTopPDialogOpen: Boolean = false,
        val isTopKDialogOpen: Boolean = false,
        val isMaxTokensDialogOpen: Boolean = false,
        val isAcceleratorDialogOpen: Boolean = false,
        val isSystemPromptDialogOpen: Boolean = false,
        val isTimeoutDialogOpen: Boolean = false,
        val isGeminiSafetyDialogOpen: Boolean = false,
        val isDeleteDialogOpen: Boolean = false
    )

    data class ToolBindingState(
        val searchConnections: List<ToolConnection> = emptyList(),
        val selectedSearchConnectionUid: String? = null,
        val readUrlEnabled: Boolean = false,
        val mcpConnections: List<ToolConnection> = emptyList(),
        val selectedMcpTools: Set<ToolBindingSelection> = emptySet(),
        val pendingMcpTools: Set<ToolBindingSelection> = emptySet(),
        val mcpToolOptions: List<McpToolOption> = emptyList(),
        val isSearchBackendDialogOpen: Boolean = false,
        val isMcpToolsDialogOpen: Boolean = false,
        val isMcpToolsLoading: Boolean = false,
        val errorMessage: String? = null
    )

    data class McpToolOption(
        val connectionUid: String,
        val connectionName: String,
        val toolName: String,
        val modelToolName: String,
        val description: String?
    )

    companion object {
        private const val WEB_SEARCH_TOOL = "web_search"
        private val WEB_SEARCH_TYPES = setOf(ToolConnectionType.FIRECRAWL, ToolConnectionType.PERPLEXITY, ToolConnectionType.EXA)
        const val MIN_TOP_K = 1
        const val MAX_TOP_K = 128
        const val MIN_MAX_TOKENS = 1
        const val DEFAULT_MAX_TOKENS_CAP = 32768
    }
}
