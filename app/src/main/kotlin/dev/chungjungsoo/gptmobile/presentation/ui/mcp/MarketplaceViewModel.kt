package dev.chungjungsoo.gptmobile.presentation.ui.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplacePackage
import dev.chungjungsoo.gptmobile.data.marketplace.MarketplacePackageStore
import dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceCatalog
import dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceRegistry
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

data class MarketplaceUiState(
    val downloadedIds: Set<String> = emptySet(),
    val downloadingIds: Set<String> = emptySet(),
    val removingIds: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
    val registryNeedsRepair: Boolean = false,
    val changingIds: Set<String> = emptySet(),
    val message: String? = null,
    val operations: Map<String, dev.chungjungsoo.gptmobile.data.marketplace.MarketplaceOperationState> = emptyMap(),
    val testingConnectionUids: Set<String> = emptySet(),
    val connectionTests: Map<String, MarketplaceConnectionTestResult> = emptyMap()
)

data class MarketplaceConnectionTestResult(
    val connected: Boolean,
    val toolCount: Int? = null,
    val completedAt: Long,
    val failureCode: String? = null,
    val message: String,
    val correlationId: String
)

/** Own downloads outside composition so rotation cannot cancel or duplicate them. */
@HiltViewModel
class MarketplaceViewModel @Inject constructor(
    private val store: MarketplacePackageStore,
    private val registry: NativeMarketplaceRegistry,
    private val connections: ToolConnectionRepository,
    private val mcpClientManager: dev.chungjungsoo.gptmobile.data.agent.tool.McpClientManager? = null,
    private val toolTrust: dev.chungjungsoo.gptmobile.data.permissions.ToolTrustStore? = null,
    private val freeConsent: dev.chungjungsoo.gptmobile.data.permissions.FreeModelToolConsentStore? = null,
    private val settings: dev.chungjungsoo.gptmobile.data.repository.SettingRepository? = null,
    private val agentToolResolver: dev.chungjungsoo.gptmobile.data.agent.tool.AgentToolResolver? = null
) : ViewModel() {
    private val _uninstallRequest = MutableStateFlow<GitHubMarketplacePackage?>(null)
    val uninstallRequest = _uninstallRequest.asStateFlow()
    fun requestUninstall(entry: GitHubMarketplacePackage?) {
        _uninstallRequest.value = entry
    }

    val installations = registry.state
    private val _uiState = MutableStateFlow(MarketplaceUiState())
    val uiState = _uiState.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()
    private val initialization = viewModelScope.launch {
        try {
            registry.load()
            val ids = store.downloadedIds()
            val removals = store.pendingRemovalIds()
            // A verified package left behind after process death can safely recover disabled registration.
            dev.chungjungsoo.gptmobile.data.marketplace.MarketplaceMutations.mutex.withLock {
                (ids - removals).forEach { id ->
                    dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplaceCatalog.find(id)?.takeIf(NativeMarketplaceCatalog::supports)?.let { registry.install(it) }
                }
            }
            _uiState.update { it.copy(downloadedIds = ids) }
            removals.forEach { id -> dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplaceCatalog.find(id)?.let(::remove) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: dev.chungjungsoo.gptmobile.data.marketplace.MarketplaceRegistryRepairRequired) {
            _uiState.update { it.copy(registryNeedsRepair = true, message = "Plugin registry needs repair. Rebuild registrations from verified packages; recovered plugins stay disabled.") }
        } catch (_: Exception) {
            showMessage("Unable to read installed packages. Check available storage, then reopen Marketplace.")
        }
    }

    fun download(entry: GitHubMarketplacePackage) = runOperation(entry, removing = false)

    fun remove(entry: GitHubMarketplacePackage) = runOperation(entry, removing = true)

    fun cancelDownload(id: String) {
        if (id in _uiState.value.downloadingIds) jobs[id]?.cancel()
    }

    fun showMessage(message: String?) {
        _uiState.update { it.copy(message = message) }
    }

    /** Explicit MCP initialize/discovery probe. It never calls a discovered tool. */
    fun testConnection(connectionUid: String) {
        if (connectionUid in _uiState.value.testingConnectionUids) return
        _uiState.update { it.copy(testingConnectionUids = it.testingConnectionUids + connectionUid) }
        viewModelScope.launch {
            val correlationId = java.util.UUID.randomUUID().toString()
            try {
                initialization.join()
                val connection = connections.getConnection(connectionUid)
                    ?: throw IllegalStateException("The saved connection is no longer available.")
                require(connection.type == dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType.MCP) {
                    "Only MCP connections support discovery tests."
                }
                val resolver = agentToolResolver ?: throw IllegalStateException("Connection testing is unavailable.")
                // Reuse the production discovery path so OAuth reauthorization and
                // endpoint validation behave exactly as they do during setup.
                val tools = resolver.discoverMcpTools(connection, forceRefresh = true)
                _uiState.update { state ->
                    state.copy(connectionTests = state.connectionTests + (connectionUid to MarketplaceConnectionTestResult(
                        connected = true,
                        toolCount = tools.size,
                        completedAt = System.currentTimeMillis(),
                        message = "Connected; discovered ${tools.size} tool${if (tools.size == 1) "" else "s"}. No tool was executed.",
                        correlationId = correlationId
                    )))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { state ->
                    state.copy(connectionTests = state.connectionTests + (connectionUid to MarketplaceConnectionTestResult(
                        connected = false,
                        completedAt = System.currentTimeMillis(),
                        failureCode = error::class.simpleName?.take(80) ?: "connection_test_failed",
                        // Server errors may echo credentials or private endpoint data. Keep
                        // the UI message actionable and use the correlation ID for support.
                        message = "Connection discovery failed. Check the saved endpoint and credentials, then retry.",
                        correlationId = correlationId
                    )))
                }
            } finally {
                _uiState.update { it.copy(testingConnectionUids = it.testingConnectionUids - connectionUid) }
            }
        }
    }

    suspend fun exportBytes(entry: GitHubMarketplacePackage): ByteArray = store.exportBytes(entry)

    fun repairRegistry() {
        viewModelScope.launch {
            try {
                dev.chungjungsoo.gptmobile.data.marketplace.MarketplaceMutations.mutex.withLock {
                    val ids = store.downloadedIds() - store.pendingRemovalIds()
                    registry.repairFromVerifiedPackages(ids)
                    _uiState.update { it.copy(registryNeedsRepair = false, downloadedIds = ids, message = "Registrations repaired. Review settings, add credentials and enable each plugin when ready.") }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showMessage("Repair could not finish. Check storage and retry; your damaged registry has been preserved.")
            }
        }
    }

    fun setEnabled(entry: GitHubMarketplacePackage, enabled: Boolean) {
        if (entry.id in _uiState.value.changingIds || entry.id in jobs) return
        _uiState.update { it.copy(changingIds = it.changingIds + entry.id) }
        viewModelScope.launch {
            try {
                initialization.join()
                dev.chungjungsoo.gptmobile.data.marketplace.MarketplaceMutations.mutex.withLock {
                    // Validate the registration/key before changing any global gates. Profile choices remain intact.
                    if (enabled) registry.configuration(entry, requireEnabled = false)
                    registry.setEnabled(entry, false)
                    if (enabled) {
                        settings?.let { repository ->
                            val previous = repository.getFeatureSettings()
                            val updated = previous.withToolPluginEnabled(entry.id, true)
                                .withToolPluginEnabled(dev.chungjungsoo.gptmobile.data.model.ToolServiceCatalog.forPackage(entry).id, true)
                            repository.updateFeatureSettings(updated)
                            try {
                                registry.setEnabled(entry, true)
                            } catch (error: Exception) {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { repository.updateFeatureSettings(previous) }
                                throw error
                            }
                        } ?: registry.setEnabled(entry, true)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showMessage(dev.chungjungsoo.gptmobile.data.marketplace.marketplaceFailureMessage(error))
            } finally {
                _uiState.update { it.copy(changingIds = it.changingIds - entry.id) }
            }
        }
    }

    fun configure(entry: GitHubMarketplacePackage, endpoint: String, key: String, maxResults: Int, dailyLimit: Int, clearKey: Boolean, endpoints: Map<String, String> = emptyMap(), disabledOperations: Set<String> = emptySet(), endpointConfigs: Map<String, dev.chungjungsoo.gptmobile.data.marketplace.NativeEndpointConfig> = emptyMap(), endpointSecrets: Map<String, String> = emptyMap(), onSaved: () -> Unit) {
        viewModelScope.launch {
            try {
                dev.chungjungsoo.gptmobile.data.marketplace.MarketplaceMutations.mutex.withLock {
                    registry.configure(entry, endpoint, key, maxResults, dailyLimit, clearKey, endpoints, disabledOperations, endpointConfigs, endpointSecrets)
                }
                onSaved()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showMessage(dev.chungjungsoo.gptmobile.data.marketplace.marketplaceFailureMessage(error))
            }
        }
    }

    private fun runOperation(entry: GitHubMarketplacePackage, removing: Boolean) {
        if (jobs.containsKey(entry.id)) return
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                // Do not let an older startup scan overwrite a new download/removal.
                initialization.join()
                if (removing) {
                    setOperation(entry.id, dev.chungjungsoo.gptmobile.data.marketplace.MarketplaceOperationPhase.REMOVING)
                    val removedConnectionUids = dev.chungjungsoo.gptmobile.data.marketplace.MarketplaceMutations.mutex.withLock {
                        store.beginRemoval(entry)
                        if (NativeMarketplaceCatalog.supports(entry)) registry.uninstall(entry)
                        toolTrust?.revoke(entry.id)
                        freeConsent?.revokeConnection(entry.id)
                        // A marketplace-created MCP registration is owned by its persisted provider identity;
                        // similarly named user-created connections are never removed as a side effect.
                        val ownedConnections = connections.listConnections()
                            .filter { it.marketplaceOrigin == "MARKETPLACE" && it.marketplaceProviderId == entry.id }
                        ownedConnections.forEach { owned ->
                            toolTrust?.revoke(owned.connectionUid)
                            freeConsent?.revokeConnection(owned.connectionUid)
                            connections.deleteConnection(owned.connectionUid)
                        }
                        store.remove(entry)
                        if (entry.provider == "openstreetmap") {
                            dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplaceCatalog.legacyPackages.forEach { legacy ->
                                store.remove(legacy)
                                toolTrust?.revoke(legacy.id)
                                freeConsent?.revokeConnection(legacy.id)
                            }
                        }
                        store.finishRemoval(entry)
                        ownedConnections.map { it.connectionUid }
                    }
                    removedConnectionUids.forEach { uid -> runCatching { mcpClientManager?.close(uid) } }
                } else {
                    // Downloading and digest verification can take seconds; do not block unrelated settings mutations.
                    store.download(entry) { phase -> setOperation(entry.id, phase) }
                    setOperation(entry.id, dev.chungjungsoo.gptmobile.data.marketplace.MarketplaceOperationPhase.INSTALLING)
                    dev.chungjungsoo.gptmobile.data.marketplace.MarketplaceMutations.mutex.withLock {
                        if (NativeMarketplaceCatalog.supports(entry)) registry.install(entry)
                    }
                }
                _uiState.update {
                    it.copy(
                        downloadedIds = if (removing) it.downloadedIds - entry.id else it.downloadedIds + entry.id,
                        operations = it.operations - entry.id
                    )
                }
            } catch (cancelled: CancellationException) {
                if (!removing) {
                    _uiState.update { it.copy(errors = it.errors + (entry.id to "Download cancelled. Retry when ready.")) }
                }
                _uiState.update { it.copy(operations = it.operations - entry.id) }
                throw cancelled
            } catch (error: Exception) {
                val message = dev.chungjungsoo.gptmobile.data.marketplace.marketplaceFailureMessage(error, removing)
                _uiState.update { state ->
                    val previous = state.operations[entry.id]
                    state.copy(
                        errors = state.errors + (entry.id to message),
                        operations = if (previous == null) state.operations else state.operations + (entry.id to previous.copy(
                            phase = dev.chungjungsoo.gptmobile.data.marketplace.MarketplaceOperationPhase.FAILED,
                            updatedAt = System.currentTimeMillis(),
                            failureCode = error::class.simpleName?.take(80) ?: "operation_failed"
                        ))
                    )
                }
            }
        }
        jobs[entry.id] = job
        _uiState.update {
            it.copy(
                downloadingIds = if (removing) it.downloadingIds else it.downloadingIds + entry.id,
                removingIds = if (removing) it.removingIds + entry.id else it.removingIds,
                errors = it.errors - entry.id,
                operations = it.operations + (entry.id to dev.chungjungsoo.gptmobile.data.marketplace.MarketplaceOperationState(
                    operationId = java.util.UUID.randomUUID().toString(),
                    packageId = entry.id,
                    phase = dev.chungjungsoo.gptmobile.data.marketplace.MarketplaceOperationPhase.QUEUED,
                    startedAt = System.currentTimeMillis()
                ))
            )
        }
        // Also runs if cancellation happens before the coroutine body starts.
        job.invokeOnCompletion {
            jobs.remove(entry.id)
            _uiState.update { it.copy(downloadingIds = it.downloadingIds - entry.id, removingIds = it.removingIds - entry.id) }
        }
        job.start()
    }

    private fun setOperation(id: String, phase: dev.chungjungsoo.gptmobile.data.marketplace.MarketplaceOperationPhase) {
        _uiState.update { state ->
            val current = state.operations[id] ?: return@update state
            state.copy(operations = state.operations + (id to current.copy(phase = phase, updatedAt = System.currentTimeMillis())))
        }
    }
}
