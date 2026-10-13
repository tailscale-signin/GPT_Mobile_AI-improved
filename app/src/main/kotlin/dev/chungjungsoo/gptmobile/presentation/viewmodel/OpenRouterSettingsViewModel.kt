package dev.chungjungsoo.gptmobile.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ProviderConnection
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.ApiCredentialRotator
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterModelItem
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterPolicy
import dev.chungjungsoo.gptmobile.data.openrouter.account.AccountFailure
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterAccess
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterAccountClient
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterAccountException
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterIdentity
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterKeySnapshot
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterTab
import dev.chungjungsoo.gptmobile.data.openrouter.account.money
import dev.chungjungsoo.gptmobile.data.openrouter.account.text
import dev.chungjungsoo.gptmobile.data.repository.OpenRouterSettingsRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.domain.model.OpenRouterSettings
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** No credential or validation grant is stored in SavedStateHandle, preferences or portable UI settings. */
@HiltViewModel
class OpenRouterSettingsViewModel @Inject constructor(
    private val repository: OpenRouterSettingsRepository,
    private val connections: SettingRepository,
    private val account: OpenRouterAccountClient,
    private val catalog: dev.chungjungsoo.gptmobile.data.repository.OpenRouterModelRepository,
    private val receipts: dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterReceiptStore,
    savedStateHandle: SavedStateHandle,
    private val queueCache: dev.chungjungsoo.gptmobile.data.database.dao.OpenRouterBatchCacheDao? = null
) : ViewModel() {
    private val _state = MutableStateFlow(OpenRouterWorkspaceState())
    val state = _state.asStateFlow()
    private val _draftKey = MutableStateFlow("")
    val draftKey = _draftKey.asStateFlow()
    private var activeKey = ""
    private var committedCredentials = ""
    private var attempt = 0L
    private var job: Job? = null
    private var catalogJob: Job? = null
    private var receiptJob: Job? = null
    private var accountReadJob: Job? = null
    private var readSession = 0L
    private var nextRefreshAt = Long.MIN_VALUE
    private val json = Json { ignoreUnknownKeys = true }

    init {
        loadConnections(savedStateHandle.get<String>("connectionUid"))
    }

    private fun loadConnections(preferred: String? = null) {
        viewModelScope.launch {
            try {
                val eligible = connections.fetchProviderConnections().filter { it.compatibleType == ClientType.OPENROUTER }
                _state.update { it.copy(connections = eligible, loading = false) }
                val selected = eligible.find { it.uid == preferred } ?: eligible.singleOrNull()
                selected?.let { selectConnection(it.uid) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                fail(AccountFailure.NETWORK)
            }
        }
    }
    fun createConnection() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val created = connections.addProviderConnection(ProviderConnection(name = "OpenRouter", compatibleType = ClientType.OPENROUTER, apiUrl = OpenRouterIdentity.endpoints.first()))
                loadConnections(created.uid)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                fail(AccountFailure.NETWORK)
            }
        }
    }
    fun selectConnection(uid: String) {
        clearAccountReadSession()
        cancelValidation()
        catalogJob?.cancel()
        activeKey = ""
        committedCredentials = ""
        _draftKey.value = ""
        nextRefreshAt = Long.MIN_VALUE
        val selected = _state.value.connections.find { it.uid == uid } ?: return
        _state.value = OpenRouterWorkspaceState(connections = _state.value.connections, connection = selected, loading = true, endpointDraft = selected.apiUrl, nameDraft = selected.name)
        receiptJob?.cancel()
        receiptJob = viewModelScope.launch { receipts.observe(uid).collect { records -> if (_state.value.connection?.uid == uid) _state.update { it.copy(receipts = records) } } }
        val ticket = attempt
        job = viewModelScope.launch {
            try {
                val settings = repository.loadSettings(uid)
                val legacyDefaults = repository.hasLegacyDefaults(uid)
                val profiles = connections.fetchPlatformV2s().filter { it.providerConnectionUid == uid }.map { it.copy(token = null, secretRef = null) }
                ensureActive()
                if (ticket != attempt) return@launch
                committedCredentials = settings.apiKey
                val keys = ApiCredentialRotator.parseKeys(settings.apiKey)
                activeKey = keys.firstOrNull().orEmpty()
                val revision = activeKey.takeIf { it.isNotBlank() }?.let { OpenRouterIdentity.revision(uid, settings.baseUrl, it) }
                _state.update { it.copy(settings = settings.copy(apiKey = ""), draft = settings.copy(apiKey = ""), profiles = profiles, revision = revision, keyCount = keys.size, loading = false, legacyDefaults = legacyDefaults) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                fail(AccountFailure.NETWORK)
            }
        }
    }
    fun editKey(value: String) {
        if (value != _draftKey.value) nextRefreshAt = Long.MIN_VALUE
        cancelValidation()
        _draftKey.value = value
        _state.update { it.copy(failure = null) }
    }
    fun editEndpoint(value: String) {
        if (value != _state.value.endpointDraft) nextRefreshAt = Long.MIN_VALUE
        cancelValidation()
        _state.update { it.copy(endpointDraft = value, failure = null) }
    }
    fun editName(value: String) {
        _state.update { it.copy(nameDraft = value.take(100)) }
    }
    fun discard() {
        _draftKey.value = ""
        _state.update { it.copy(draft = it.settings, endpointDraft = it.connection?.apiUrl.orEmpty(), nameDraft = it.connection?.name.orEmpty(), failure = null) }
    }
    fun cancelValidation() {
        attempt++
        job?.cancel()
        _state.update { it.copy(validating = false, loading = false) }
    }

    fun validate(connect: Boolean = true) {
        val before = _state.value
        val connection = before.connection ?: return
        if (before.validating || before.loading || System.nanoTime() < nextRefreshAt) return
        val replacement = connect && _draftKey.value.isNotBlank()
        val credential = if (replacement) _draftKey.value else activeKey
        val endpoint = if (connect) before.endpointDraft else connection.apiUrl
        val normalizedKey: String
        val normalizedEndpoint: String
        try {
            normalizedKey = OpenRouterIdentity.credential(credential)
            normalizedEndpoint = OpenRouterIdentity.endpoint(endpoint)
        } catch (e: OpenRouterAccountException) {
            fail(e.category)
            return
        } catch (_: IllegalArgumentException) {
            fail(AccountFailure.INVALID)
            return
        }
        cancelValidation()
        val ticket = attempt
        val oldCredentials = committedCredentials
        _state.update { it.copy(validating = true, failure = null) }
        job = viewModelScope.launch {
            try {
                val snapshot = account.key(normalizedEndpoint, normalizedKey)
                if (snapshot.isManagement) throw OpenRouterAccountException(AccountFailure.ROLE)
                if (snapshot.expired) throw OpenRouterAccountException(AccountFailure.EXPIRED)
                ensureActive()
                if (ticket != attempt) return@launch
                // Reject stale completions if another editor rotated/deleted the selected connection.
                val current = connections.getProviderConnection(connection.uid)
                if (current?.apiUrl != connection.apiUrl || connections.getProviderCredentials(connection.uid).orEmpty() != oldCredentials) {
                    selectConnection(connection.uid)
                    return@launch
                }
                val updated = if (connect && (replacement || normalizedEndpoint != connection.apiUrl)) {
                    connections.replaceProviderCredential(connection.uid, connection.apiUrl, oldCredentials, normalizedEndpoint, if (replacement) normalizedKey else null)
                } else {
                    connection
                }
                ensureActive()
                if (ticket != attempt) return@launch
                activeKey = normalizedKey
                if (replacement) committedCredentials = normalizedKey
                if (connect) _draftKey.value = ""
                val revision = OpenRouterIdentity.revision(updated.uid, normalizedEndpoint, normalizedKey)
                val changedIdentity = revision != _state.value.revision
                if (changedIdentity) {
                    catalogJob?.cancel()
                    clearAccountReadSession()
                }
                _state.update {
                    it.copy(
                        connection = updated, connections = it.connections.map { old -> if (old.uid == updated.uid) updated else old },
                        revision = revision, access = OpenRouterAccess(revision), snapshot = snapshot, validating = false, stale = false, failure = null,
                        keyCount = ApiCredentialRotator.parseKeys(committedCredentials).size,
                        endpointDraft = if (connect) normalizedEndpoint else it.endpointDraft, settings = it.settings.copy(baseUrl = normalizedEndpoint), draft = it.draft.copy(baseUrl = normalizedEndpoint),
                        catalog = if (changedIdentity) emptyList() else it.catalog, catalogAt = if (changedIdentity) null else it.catalogAt,
                        catalogLoading = if (changedIdentity) false else it.catalogLoading, catalogError = if (changedIdentity) false else it.catalogError,
                        endpoints = if (changedIdentity) null else it.endpoints, endpointLoading = if (changedIdentity) false else it.endpointLoading,
                        endpointModel = if (changedIdentity) null else it.endpointModel, endpointAt = if (changedIdentity) null else it.endpointAt,
                        providers = if (changedIdentity) emptyList() else it.providers, providersLoading = if (changedIdentity) false else it.providersLoading,
                        batches = if (changedIdentity) emptyList() else it.batches, batchLoading = if (changedIdentity) false else it.batchLoading,
                        batchError = if (changedIdentity) false else it.batchError, batchUpdatedAt = if (changedIdentity) null else it.batchUpdatedAt,
                        reconciling = if (changedIdentity) false else it.reconciling
                    )
                }
                nextRefreshAt = System.nanoTime() + 10_000_000_000L
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (ticket == attempt) {
                    val failure = (e as? OpenRouterAccountException)?.category ?: AccountFailure.NETWORK
                    val invalidate = !replacement && endpoint == connection.apiUrl && failure in setOf(AccountFailure.INVALID, AccountFailure.EXPIRED, AccountFailure.ROLE)
                    _state.update {
                        it.copy(
                            validating = false,
                            failure = failure,
                            stale = it.snapshot != null,
                            access = if (invalidate) OpenRouterAccess() else it.access,
                            tab = if (invalidate) OpenRouterTab.SETUP else it.tab,
                            snapshot = if (invalidate) null else it.snapshot
                        )
                    }
                    nextRefreshAt = System.nanoTime() + ((e as? OpenRouterAccountException)?.retryAfterSeconds ?: 10) * 1_000_000_000L
                }
            }
        }
    }
    fun selectTab(tab: OpenRouterTab) {
        _state.update { it.copy(tab = it.access.select(tab, it.revision)) }
        if (_state.value.tab == OpenRouterTab.ACCOUNT && System.currentTimeMillis() - (_state.value.snapshot?.fetchedAt ?: 0) !in 0..60_000) validate(false)
        if (_state.value.tab == OpenRouterTab.ROUTING && System.currentTimeMillis() - (_state.value.catalogAt ?: 0) !in 0..900_000) refreshCatalog()
    }
    fun disconnect() {
        clearAccountReadSession()
        val connection = _state.value.connection ?: return
        cancelValidation()
        catalogJob?.cancel()
        _draftKey.value = ""
        activeKey = ""
        _state.update { it.copy(access = OpenRouterAccess(), snapshot = null, revision = null, tab = OpenRouterTab.SETUP, catalog = emptyList(), endpoints = null, loading = true) }
        job = viewModelScope.launch {
            try {
                connections.updateProviderConnection(connection, "")
                selectConnection(connection.uid)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                fail(AccountFailure.NETWORK)
            }
        }
    }
    fun updateDraft(change: (OpenRouterSettings) -> OpenRouterSettings) {
        _state.update { it.copy(draft = change(it.draft).copy(apiKey = ""), saveError = false, saved = false) }
    }
    fun apply() {
        val before = _state.value
        val connection = before.connection ?: return
        if (before.saving || before.loading) return
        try {
            OpenRouterPolicy.validate(before.draft.routing ?: dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterProviderRouting())
        } catch (_: Exception) {
            _state.update { it.copy(saveError = true) }
            return
        }
        _state.update { it.copy(saving = true, saveError = false) }
        viewModelScope.launch {
            try {
                val saved = repository.updateSettings(connection.uid, before.settings.revision) { before.draft }
                if (before.nameDraft.isNotBlank() && before.nameDraft != connection.name) {
                    connections.getProviderConnection(connection.uid)?.let { connections.updateProviderConnection(it.copy(name = before.nameDraft.trim())) }
                }
                if (_state.value.connection?.uid == connection.uid) {
                    _state.update {
                        it.copy(
                            settings = saved.copy(apiKey = ""),
                            draft = if (it.draft == before.draft) saved.copy(apiKey = "") else it.draft.copy(revision = saved.revision),
                            saving = false,
                            saved = true,
                            connection = it.connection?.copy(name = before.nameDraft.trim().ifBlank { connection.name })
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(saving = false, saveError = true) }
            }
        }
    }
    fun refreshCatalog() {
        val before = _state.value
        if (!before.unlocked || before.catalogLoading || (before.catalogAt != null && System.currentTimeMillis() - before.catalogAt in 0..9_999)) return
        val key = activeKey
        val endpoint = before.connection?.apiUrl ?: return
        _state.update { it.copy(catalogLoading = true, catalogError = false) }
        catalogJob = viewModelScope.launch {
            try {
                val result = catalog.fetchUserModels(before.connection.uid, endpoint, key, true).getOrThrow()
                ensureActive()
                if (_state.value.revision == before.revision) _state.update { it.copy(catalog = result.first, catalogAt = result.second, catalogLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (error: Exception) {
                if (_state.value.revision == before.revision) {
                    val revoked = (error as? OpenRouterAccountException)?.category == AccountFailure.INVALID
                    _state.update {
                        it.copy(
                            catalogLoading = false,
                            catalogError = true,
                            access = if (revoked) OpenRouterAccess() else it.access,
                            snapshot = if (revoked) null else it.snapshot,
                            tab = if (revoked) OpenRouterTab.SETUP else it.tab
                        )
                    }
                }
            }
        }
    }
    fun compareProviders(model: String) {
        val before = _state.value
        if (!before.unlocked || before.endpointLoading) return
        if (before.endpointModel == model && before.endpoints != null && System.currentTimeMillis() - (before.endpointAt ?: 0) in 0..300_000) return
        val parts = model.split('/')
        if (parts.size != 2 || parts.any { !it.matches(Regex("[A-Za-z0-9_.:-]+")) }) return
        val key = activeKey
        _state.update { it.copy(endpointLoading = true, endpoints = if (it.endpointModel == model) it.endpoints else null) }
        viewModelScope.launch {
            try {
                val response = account.objectResource(before.connection!!.apiUrl, key, "models/${parts[0]}/${parts[1]}/endpoints")
                if (_state.value.revision == before.revision) _state.update { it.copy(endpointLoading = false, endpoints = response, endpointAt = System.currentTimeMillis(), endpointModel = model) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (_state.value.revision == before.revision) _state.update { it.copy(endpointLoading = false, catalogError = true) }
            }
        }
    }
    fun reconcileReceipts() {
        val before = _state.value
        val uid = before.connection?.uid ?: return
        val revision = before.revision ?: return
        if (!before.unlocked || before.reconciling) return
        val key = activeKey
        _state.update { it.copy(reconciling = true) }
        viewModelScope.launch {
            try {
                for (receipt in receipts.pending(uid, revision)) {
                    if (_state.value.revision != revision) break
                    val id = receipt.generationId?.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]+")) } ?: continue
                    val data = account.objectResource(before.connection.apiUrl, key, "generation?id=$id")["data"] as? JsonObject ?: continue
                    receipts.record(receipt.copy(costUsd = data.money("total_cost")))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { } finally {
                if (_state.value.connection?.uid == uid) _state.update { it.copy(reconciling = false) }
            }
        }
    }
    fun refreshQueueCache(clear: Boolean = false) {
        val uid = _state.value.connection?.uid ?: return
        val cache = queueCache ?: return
        viewModelScope.launch {
            try {
                val prefix = OpenRouterIdentity.digest(uid) + "_"
                if (clear) cache.clearForConnection(prefix)
                val count = cache.countForConnection(prefix)
                if (_state.value.connection?.uid == uid) _state.update { it.copy(queueCacheCount = count) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(queueCacheCount = null) }
            }
        }
    }
    fun inheritLegacyDefaults() {
        val uid = _state.value.connection?.uid ?: return
        viewModelScope.launch {
            try {
                repository.migrateLegacyDefaults(uid)
                selectConnection(uid)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(saveError = true) }
            }
        }
    }
    fun freshNextRequest() {
        _state.value.connection?.uid?.let { dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterFreshRequests.request(it) }
    }
    fun safeDiagnostics(): String = buildString {
        val current = _state.value
        appendLine("OpenRouter connection diagnostics")
        appendLine("Endpoint: " + runCatching { java.net.URI(current.connection?.apiUrl.orEmpty()).host }.getOrNull().orEmpty())
        appendLine("Authentication: " + if (current.unlocked) "verified this session" else "unvalidated")
        appendLine("Last result: " + (current.failure?.name ?: "none"))
        appendLine("Verified at: " + (current.snapshot?.fetchedAt ?: "never"))
    }
    fun loadProviders() {
        val before = _state.value
        if (!before.unlocked || before.providersLoading) return
        val key = activeKey
        _state.update { it.copy(providersLoading = true) }
        viewModelScope.launch {
            try {
                val entries = (account.objectResource(before.connection!!.apiUrl, key, "providers")["data"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                    .mapNotNull { it as? JsonObject }.filter { !it.text("slug").isNullOrBlank() }.take(300)
                if (_state.value.revision == before.revision) _state.update { it.copy(providers = entries, providersLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (_state.value.revision == before.revision) _state.update { it.copy(providersLoading = false, catalogError = true) }
            }
        }
    }
    fun connectAccountReadAccess(credential: String) {
        if (!_state.value.unlocked) return
        clearAccountReadSession()
        val session = readSession
        val endpoint = _state.value.connection?.apiUrl ?: return
        _state.update { it.copy(accountReadLoading = true) }
        accountReadJob = viewModelScope.launch {
            try {
                val identity = account.key(endpoint, credential)
                if (!identity.isManagement) throw OpenRouterAccountException(AccountFailure.ROLE)
                val wallet = runCatching { account.objectResource(endpoint, credential, "credits")["data"] as? JsonObject }
                    .getOrElse {
                        if (it is CancellationException) throw it
                        null
                    }
                if (session != readSession) return@launch
                _state.update { it.copy(wallet = wallet, accountReadError = wallet == null) }
                val activity = runCatching { account.objectResource(endpoint, credential, "activity")["data"] as? kotlinx.serialization.json.JsonArray }
                    .getOrElse {
                        if (it is CancellationException) throw it
                        null
                    }
                if (session == readSession) _state.update { it.copy(accountActivity = activity, accountReadLoading = false, accountReadAt = System.currentTimeMillis(), accountReadError = wallet == null || activity == null) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (session == readSession) _state.update { it.copy(accountReadLoading = false, accountReadError = true) }
            }
        }
        // No credential field is retained after this one-shot coroutine. Reopening/backgrounding clears all account-wide panels.
    }
    fun clearAccountReadSession() {
        readSession++
        accountReadJob?.cancel()
        _state.update { it.copy(wallet = null, accountActivity = null, accountReadAt = null, accountReadLoading = false, accountReadError = false) }
    }
    fun refreshBatches() {
        val before = _state.value
        if (!before.unlocked || before.batchLoading) return
        val key = activeKey
        _state.update { it.copy(batchLoading = true) }
        viewModelScope.launch {
            try {
                val client = dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterBatchClient(key, baseUrl = before.connection!!.apiUrl)
                val batches = client.list(30)
                if (_state.value.revision == before.revision) _state.update { it.copy(batches = batches, batchLoading = false, batchError = false, batchUpdatedAt = System.currentTimeMillis()) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (_state.value.revision == before.revision) _state.update { it.copy(batchLoading = false, batchError = true) }
            }
        }
    }
    fun cancelBatch(id: String, delete: Boolean = false) {
        val before = _state.value
        if (!before.unlocked || before.batchLoading || before.batches.none { it.id == id }) return
        if (delete && before.batches.find { it.id == id }?.status !in setOf("completed", "failed", "expired", "cancelled")) return
        val key = activeKey
        _state.update { it.copy(batchLoading = true) }
        viewModelScope.launch {
            try {
                val client = dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterBatchClient(key, baseUrl = before.connection!!.apiUrl)
                if (delete) client.delete(id) else client.cancel(id)
                if (_state.value.revision == before.revision) {
                    _state.update { it.copy(batchLoading = false) }
                    refreshBatches()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (_state.value.revision == before.revision) _state.update { it.copy(batchLoading = false, batchError = true) }
            }
        }
    }
    suspend fun exportBatch(id: String): String? {
        val before = _state.value
        if (!before.unlocked || before.batches.none { it.id == id }) return null
        return try {
            val result = dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterBatchClient(activeKey, baseUrl = before.connection!!.apiUrl).status(id)
            if (_state.value.revision != before.revision) {
                null
            } else {
                kotlinx.serialization.json.buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive(result.meta.id))
                    put("status", kotlinx.serialization.json.JsonPrimitive(result.meta.status))
                    put(
                        "results",
                        kotlinx.serialization.json.JsonArray(
                            result.results.orEmpty().map { row ->
                                kotlinx.serialization.json.buildJsonObject {
                                    put("custom_id", kotlinx.serialization.json.JsonPrimitive(row.customId))
                                    row.response?.let { put("response", runCatching { json.parseToJsonElement(it) }.getOrDefault(kotlinx.serialization.json.JsonPrimitive(it))) }
                                    row.error?.let { put("error", runCatching { json.parseToJsonElement(it) }.getOrDefault(kotlinx.serialization.json.JsonPrimitive(it))) }
                                }
                            }
                        )
                    )
                }.toString()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _state.update { it.copy(batchError = true) }
            null
        }
    }
    fun hideAmounts() {
        _state.update { it.copy(hideAmounts = !it.hideAmounts) }
    }
    private fun fail(failure: AccountFailure) {
        _state.update { it.copy(failure = failure, validating = false, loading = false) }
    }
    override fun onCleared() {
        clearAccountReadSession()
        activeKey = ""
        committedCredentials = ""
        _draftKey.value = ""
        super.onCleared()
    }
}

data class OpenRouterWorkspaceState(
    val connections: List<ProviderConnection> = emptyList(),
    val connection: ProviderConnection? = null,
    val profiles: List<PlatformV2> = emptyList(),
    val tab: OpenRouterTab = OpenRouterTab.SETUP,
    val access: OpenRouterAccess = OpenRouterAccess(),
    val revision: String? = null,
    val settings: OpenRouterSettings = OpenRouterSettings(apiKey = ""),
    val draft: OpenRouterSettings = settings,
    val endpointDraft: String = OpenRouterIdentity.endpoints.first(),
    val nameDraft: String = "",
    val loading: Boolean = true,
    val validating: Boolean = false,
    val failure: AccountFailure? = null,
    val snapshot: OpenRouterKeySnapshot? = null,
    val stale: Boolean = false,
    val keyCount: Int = 0,
    val saving: Boolean = false,
    val saveError: Boolean = false,
    val saved: Boolean = false,
    val hideAmounts: Boolean = false,
    val catalog: List<OpenRouterModelItem> = emptyList(),
    val catalogAt: Long? = null,
    val catalogLoading: Boolean = false,
    val catalogError: Boolean = false,
    val endpoints: JsonObject? = null,
    val endpointLoading: Boolean = false,
    val endpointModel: String? = null,
    val endpointAt: Long? = null,
    val receipts: List<dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterReceipt> = emptyList(),
    val reconciling: Boolean = false,
    val providers: List<JsonObject> = emptyList(),
    val providersLoading: Boolean = false,
    val wallet: JsonObject? = null,
    val accountActivity: kotlinx.serialization.json.JsonArray? = null,
    val accountReadLoading: Boolean = false,
    val accountReadError: Boolean = false,
    val accountReadAt: Long? = null,
    val batches: List<dev.chungjungsoo.gptmobile.data.openrouter.BatchMeta> = emptyList(),
    val batchLoading: Boolean = false,
    val batchError: Boolean = false,
    val batchUpdatedAt: Long? = null,
    val legacyDefaults: Boolean = false,
    val queueCacheCount: Int? = null
) {
    val unlocked: Boolean get() = access.unlocked(revision)
    val dirty: Boolean get() = settings != draft || nameDraft != connection?.name.orEmpty()
}
