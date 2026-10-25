package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.catalog.CatalogEntry
import dev.chungjungsoo.gptmobile.data.huggingface.HuggingFaceModelSearchClient
import dev.chungjungsoo.gptmobile.data.huggingface.HuggingFaceTokenStore
import dev.chungjungsoo.gptmobile.data.localmodel.GatedDownloadCoordinator
import dev.chungjungsoo.gptmobile.data.localmodel.LocalModelStatus
import dev.chungjungsoo.gptmobile.data.repository.LocalModelRepository
import dev.chungjungsoo.gptmobile.data.repository.ModelCatalogRepository
import dev.chungjungsoo.gptmobile.di.DeviceSocModel
import dev.chungjungsoo.gptmobile.presentation.ui.localmodel.HuggingFaceAuthClient
import dev.chungjungsoo.gptmobile.presentation.ui.localmodel.LocalDownloadGuards
import dev.chungjungsoo.gptmobile.presentation.ui.localmodel.LocalModelDownloadActions
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class LocalModelsViewModel @Inject constructor(
    private val modelCatalogRepository: ModelCatalogRepository,
    private val localModelRepository: LocalModelRepository,
    private val benchmarkStore: dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStore,
    gatedDownloadCoordinator: GatedDownloadCoordinator,
    private val huggingFaceTokenStore: HuggingFaceTokenStore,
    private val downloadGuards: LocalDownloadGuards,
    huggingFaceAuthClient: HuggingFaceAuthClient,
    @param:DeviceSocModel private val deviceSocModel: String,
    private val huggingFaceModelSearchClient: HuggingFaceModelSearchClient? = null,
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val importContext: android.content.Context? = null
) : ViewModel() {

    private val downloadActions = LocalModelDownloadActions(
        localModelRepository = localModelRepository,
        gatedDownloadCoordinator = gatedDownloadCoordinator,
        huggingFaceTokenStore = huggingFaceTokenStore,
        downloadGuards = downloadGuards,
        huggingFaceAuthClient = huggingFaceAuthClient,
        scope = viewModelScope,
        deviceSocModel = deviceSocModel
    )

    private val downloadPreferencesMutex = kotlinx.coroutines.sync.Mutex()
    private val mutableWifiOnly = MutableStateFlow<Boolean?>(null)
    val wifiOnly: StateFlow<Boolean?> = mutableWifiOnly
    private val mutableDownloadSettingsError = MutableStateFlow<String?>(null)
    val downloadSettingsError: StateFlow<String?> = mutableDownloadSettingsError

    fun updateWifiOnly(enabled: Boolean) {
        if (mutableWifiOnly.value == null) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            downloadPreferencesMutex.lock()
            try {
                val context = importContext ?: return@launch
                check(
                    context.getSharedPreferences("local-model-downloads", android.content.Context.MODE_PRIVATE)
                        .edit().putBoolean("wifiOnly", enabled).commit()
                ) { "Could not save download settings." }
                mutableWifiOnly.value = enabled
                mutableDownloadSettingsError.value = null
            } catch (canceled: kotlinx.coroutines.CancellationException) {
                throw canceled
            } catch (error: Exception) {
                mutableDownloadSettingsError.value = "Could not save the download preference. Try again."
            } finally {
                downloadPreferencesMutex.unlock()
            }
        }
    }

    private val catalogEntries = MutableStateFlow<List<CatalogEntry>>(emptyList())
    private val mutableCatalogStatus = MutableStateFlow<dev.chungjungsoo.gptmobile.data.catalog.CatalogSnapshot?>(null)
    val catalogStatus: StateFlow<dev.chungjungsoo.gptmobile.data.catalog.CatalogSnapshot?> = mutableCatalogStatus
    private var refreshJob: Job? = null
    fun refreshCatalog() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            val snapshot = modelCatalogRepository.getSnapshot(refresh = true)
            mutableCatalogStatus.value = snapshot
            if (snapshot.complete) catalogEntries.value = snapshot.entries
        }
    }

    private val localListState = MutableStateFlow(LocalModelsListState())
    private val customDialogState = MutableStateFlow<LocalModelsDialog>(LocalModelsDialog.Hidden)
    private val hasHuggingFaceToken = MutableStateFlow(false)
    private val searchQuery = MutableStateFlow("")
    private val modelFilter = MutableStateFlow(LocalModelFilter.ALL)
    private val modelSource = MutableStateFlow(LocalModelSource.CATALOG)
    private val huggingFaceEntries = MutableStateFlow<List<CatalogEntry>>(emptyList())
    private val huggingFaceSearchState = MutableStateFlow(HuggingFaceSearchState())
    private var huggingFaceSearchJob: Job? = null
    private val mutableFilters = MutableStateFlow(LocalDiscoveryFilters())
    val filters: StateFlow<LocalDiscoveryFilters> = mutableFilters
    fun updateFilters(change: (LocalDiscoveryFilters) -> LocalDiscoveryFilters) {
        val previous = filters.value
        mutableFilters.value = change(previous)
        if (previous.processor != filters.value.processor) refreshHuggingFaceSearch()
    }
    fun resetFilters(showAll: Boolean = false) {
        mutableFilters.value = LocalDiscoveryFilters(everyModel = showAll)
        modelFilter.value = LocalModelFilter.ALL
        searchQuery.value = ""
        refreshHuggingFaceSearch()
    }
    fun setNpuOnly(value: Boolean) = updateFilters { it.copy(processor = if (value) "NPU" else "Any") }

    private val discoveryState = combine(
        searchQuery,
        modelFilter,
        modelSource,
        huggingFaceEntries,
        huggingFaceSearchState
    ) { query, filter, source, hfEntries, hfSearch ->
        LocalModelDiscoveryState(
            query = query,
            filter = filter,
            source = source,
            huggingFaceEntries = hfEntries,
            huggingFaceSearch = hfSearch
        )
    }

    val uiState: StateFlow<LocalModelsUiState> = combine(
        localListState,
        downloadActions.uiState,
        customDialogState,
        hasHuggingFaceToken,
        discoveryState
    ) { list, download, customDialog, hasToken, discovery ->
        val normalized = discovery.query.trim().lowercase()
        fun matchesFilter(item: LocalModelListItem): Boolean = when (discovery.filter) {
            LocalModelFilter.ALL -> true
            LocalModelFilter.READY -> item.status == LocalModelItemStatus.READY
            LocalModelFilter.AVAILABLE -> item.status == LocalModelItemStatus.NOT_DOWNLOADED
            LocalModelFilter.DOWNLOADING -> item.status == LocalModelItemStatus.DOWNLOADING
            LocalModelFilter.FAILED -> item.status == LocalModelItemStatus.FAILED
        }

        val localItemsById = list.items.associateBy { it.entry.id }
        val hfItems = discovery.huggingFaceEntries.map { entry ->
            localItemsById[entry.id]?.copy(entry = entry)
                ?: LocalModelListItem(
                    entry = entry,
                    status = LocalModelItemStatus.NOT_DOWNLOADED,
                    downloadSizeBytes = entry.sizeInBytes
                )
        }.filter(::matchesFilter)

        val catalogItems = list.items.filter { item ->
            val matchesQuery = normalized.isBlank() ||
                (item.entry.familyId + " " + item.entry.variantLabel + " " + item.entry.runtimeRequirement + " " + item.entry.precision).lowercase().contains(normalized) ||
                item.entry.displayName.lowercase().contains(normalized) ||
                item.entry.id.lowercase().contains(normalized) ||
                item.entry.downloadUrl.lowercase().contains(normalized) ||
                item.entry.supportedAccelerators.any { it.lowercase().contains(normalized) }
            matchesQuery && matchesFilter(item)
        }

        LocalModelsUiState(
            allItems = list.items,
            items = if (discovery.source == LocalModelSource.CATALOG) catalogItems else hfItems,
            totalItemCount = if (discovery.source == LocalModelSource.CATALOG) list.items.size else discovery.huggingFaceEntries.size,
            searchQuery = discovery.query,
            filter = discovery.filter,
            isLoading = list.isLoading,
            totalStorageBytes = list.totalStorageBytes,
            checkingAccessEntryId = download.checkingAccessEntryId,
            dialog = if (customDialog !is LocalModelsDialog.Hidden) customDialog else download.dialog,
            hasHuggingFaceToken = hasToken,
            source = discovery.source,
            huggingFaceItems = hfItems,
            isSearchingHuggingFace = discovery.huggingFaceSearch.isLoading,
            huggingFaceSearchError = discovery.huggingFaceSearch.error,
            huggingFaceSearchNotice = discovery.huggingFaceSearch.notice
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LocalModelsUiState())

    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                mutableWifiOnly.value = importContext?.getSharedPreferences("local-model-downloads", android.content.Context.MODE_PRIVATE)
                    ?.getBoolean("wifiOnly", false)
            } catch (canceled: kotlinx.coroutines.CancellationException) {
                throw canceled
            } catch (error: Exception) {
                mutableDownloadSettingsError.value = "Saved download preferences are unavailable."
            }
        }
        viewModelScope.launch {
            benchmarkStore.load()
            benchmarkStore.history.collect { history ->
                val scores = localBenchmarkScores(history)
                localListState.update { state ->
                    state.copy(items = state.items.map { it.copy(benchmarkScore = scores[it.entry.id]) })
                }
            }
        }
        viewModelScope.launch {
            hasHuggingFaceToken.value = huggingFaceTokenStore.readAccessToken() != null
        }
        viewModelScope.launch {
            runCatching { localModelRepository.reconcile() }
            val snapshot = modelCatalogRepository.getSnapshot()
            mutableCatalogStatus.value = snapshot
            catalogEntries.value = snapshot.entries
            var storageIdentity: List<String>? = null
            var measuredStorage = 0L
            combine(
                catalogEntries,
                localModelRepository.observeAll(),
                localModelRepository.observeWorkInfos()
            ) { catalogEntries, localModels, workInfos ->
                val items = catalogLocalModelItems(
                    catalogEntries,
                    localModels,
                    workInfos,
                    localModels.associate { it.catalogEntryId to localModelRepository.diskPartialBytes(it) },
                    deviceSocModel = deviceSocModel
                )
                val identity = localModels.filter { it.status == LocalModelStatus.READY }
                    .map { "${it.catalogEntryId}:${it.commitHash}:${it.updatedAt}" }.sorted()
                if (storageIdentity != identity) {
                    // Bundle rows track manifest length for integrity checks, not the
                    // multi-file installation size. Scan on install/delete, not progress.
                    measuredStorage = localModelRepository.totalStorageUsed()
                    storageIdentity = identity
                }
                items to measuredStorage
            }.collect { (items, storage) ->
                val scores = localBenchmarkScores(benchmarkStore.history.value)
                localListState.update {
                    it.copy(
                        items = items.map { item -> item.copy(benchmarkScore = scores[item.entry.id]) },
                        isLoading = false,
                        totalStorageBytes = storage
                    )
                }
            }
        }
    }

    fun updateSearchQuery(value: String) {
        searchQuery.value = value
        if (modelSource.value == LocalModelSource.HUGGING_FACE) {
            scheduleHuggingFaceSearch(value)
        }
    }

    fun updateFilter(filter: LocalModelFilter) {
        modelFilter.value = filter
    }

    fun updateModelSource(source: LocalModelSource) {
        modelSource.value = source
        if (source == LocalModelSource.HUGGING_FACE && huggingFaceEntries.value.isEmpty()) {
            scheduleHuggingFaceSearch(searchQuery.value, immediate = true)
        }
    }

    fun refreshHuggingFaceSearch() {
        if (modelSource.value == LocalModelSource.HUGGING_FACE) {
            scheduleHuggingFaceSearch(searchQuery.value, immediate = true)
        }
    }

    private fun scheduleHuggingFaceSearch(query: String, immediate: Boolean = false) {
        huggingFaceSearchJob?.cancel()
        val client = huggingFaceModelSearchClient
        if (client == null) {
            huggingFaceSearchState.value = HuggingFaceSearchState(
                isLoading = false,
                error = "Hugging Face search is unavailable in this build."
            )
            return
        }

        huggingFaceSearchJob = viewModelScope.launch {
            if (!immediate) delay(HUGGING_FACE_SEARCH_DEBOUNCE_MS)
            if (!immediate) huggingFaceEntries.value = emptyList()
            huggingFaceSearchState.value = HuggingFaceSearchState(isLoading = true)
            runCatching {
                if (filters.value.processor == "NPU") {
                    client.searchNpuOutcome(query, deviceSocModel)
                } else {
                    client.searchOutcome(query, onPartial = { partial ->
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val installed = localListState.value.items.filter { it.status == LocalModelItemStatus.READY }.map { it.entry }
                        huggingFaceEntries.value = partial.results.map { dev.chungjungsoo.gptmobile.data.huggingface.preserveInstalledHubIdentity(it.toCatalogEntry(), installed) }
                        huggingFaceSearchState.value = HuggingFaceSearchState(isLoading = true, notice = partial.notice)
                    })
                }
            }
                .onSuccess { outcome ->
                    val installed = localListState.value.items.filter { it.status == LocalModelItemStatus.READY }.map { it.entry }
                    huggingFaceEntries.value = outcome.results.map { dev.chungjungsoo.gptmobile.data.huggingface.preserveInstalledHubIdentity(it.toCatalogEntry(), installed) }
                    huggingFaceSearchState.value = HuggingFaceSearchState(notice = outcome.notice)
                }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    huggingFaceSearchState.value = HuggingFaceSearchState(
                        error = error.localizedMessage ?: "Could not search Hugging Face."
                    )
                }
        }
    }

    fun onDownloadClick(entry: CatalogEntry) {
        downloadActions.requestDownload(entry, currentStatus(entry.id))
    }

    fun confirmRamWarning() {
        downloadActions.confirmRamWarning()
    }

    fun confirmMeteredDownload() {
        downloadActions.confirmMeteredDownload()
    }

    fun onDeleteClick(entry: CatalogEntry) {
        customDialogState.value = LocalModelsDialog.DeleteConfirm(entry)
    }

    fun confirmDelete() = confirmDeleteWith { localModelRepository.deleteModel(it) }

    fun confirmDeleteWith(delete: suspend (String) -> Unit) {
        val entry = (customDialogState.value as? LocalModelsDialog.DeleteConfirm)?.entry ?: return
        customDialogState.value = LocalModelsDialog.Hidden
        viewModelScope.launch {
            try {
                delete(entry.id)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                customDialogState.value = LocalModelsDialog.ImportFailed(error.message ?: "Model files could not be deleted", "Model deletion paused")
            }
        }
    }

    fun cancelDownload(entry: CatalogEntry) {
        viewModelScope.launch { localModelRepository.cancelDownload(entry.id) }
    }

    fun dismissDialog() {
        if (customDialogState.value !is LocalModelsDialog.Hidden) {
            customDialogState.value = LocalModelsDialog.Hidden
        } else {
            downloadActions.dismissDialog()
        }
    }

    val importStatus = MutableStateFlow<String?>(null)
    val importCopyRecovery = MutableStateFlow<dev.chungjungsoo.gptmobile.data.localmodel.LocalModelSource?>(null)
    val importActive = MutableStateFlow(false)
    private val importScheduling = MutableStateFlow(false)
    val importedModelId = MutableStateFlow<String?>(null)

    suspend fun inspectImport(source: dev.chungjungsoo.gptmobile.data.localmodel.LocalModelSource) = inspectImport(source.uri, source.folder)

    suspend fun inspectImport(uri: Uri, folder: Boolean): dev.chungjungsoo.gptmobile.data.localmodel.LocalImportPreview = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val resolver = checkNotNull(importContext).contentResolver
        var name = uri.lastPathSegment.orEmpty()
        var size: Long? = null
        val source = if (folder) {
            val parent = android.provider.DocumentsContract.getTreeDocumentId(uri)
            val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(uri, parent)
            val matches = resolver.query(children, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                buildList {
                    var count = 0
                    while (cursor.moveToNext()) {
                        require(++count <= 1024) { "Folder contains too many entries" }
                        if (cursor.getString(1) == "manifest.json") add(cursor.getString(0))
                    }
                }
            }.orEmpty()
            require(matches.size == 1) { "Choose a model folder containing one manifest.json file." }
            android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, matches.single())
        } else {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0) ?: name
                    size = if (cursor.isNull(1)) null else cursor.getLong(1).takeIf { it >= 0 }
                }
            }
            uri
        }
        val input = resolver.openInputStream(source) ?: error("Source cannot be read; select it again.")
        val preview = dev.chungjungsoo.gptmobile.data.localmodel.LocalImportPreview.inspect(input, name, size, dev.chungjungsoo.gptmobile.BuildConfig.GENIEX_ENABLED, folder)
        val coroutine = kotlinx.coroutines.currentCoroutineContext()
        preview.copy(
            availableBytes = (importContext?.getExternalFilesDir(null) ?: checkNotNull(importContext).filesDir).usableSpace,
            linkProblem = dev.chungjungsoo.gptmobile.data.localmodel.LocalModelSourceImport.linkProblem(
                checkNotNull(importContext),
                dev.chungjungsoo.gptmobile.data.localmodel.LocalModelSource(uri, if (folder) dev.chungjungsoo.gptmobile.data.localmodel.LocalModelSource.Kind.FOLDER else dev.chungjungsoo.gptmobile.data.localmodel.LocalModelSource.Kind.FILE),
                name,
                { coroutine.ensureActive() }
            )
        )
    }
    init {
        importContext?.let { context ->
            viewModelScope.launch {
                combine(
                    androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(dev.chungjungsoo.gptmobile.data.worker.LocalModelImportWorker.WORK),
                    importScheduling
                ) { jobs, scheduling -> jobs to scheduling }.collectLatest { (jobs, scheduling) ->
                    if (scheduling) {
                        importActive.value = true
                        return@collectLatest
                    }
                    val (latest, ownsGrant, source) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val preferences = context.getSharedPreferences("local-import-job", android.content.Context.MODE_PRIVATE)
                        Triple(preferences.getString("latest", null), preferences.getBoolean("ownsGrant", false), preferences.getString("source", null))
                    }
                    val worker = jobs.firstOrNull { !it.state.isFinished } ?: jobs.firstOrNull { it.id.toString() == latest }
                    val copyAvailable = worker?.state == androidx.work.WorkInfo.State.FAILED && worker.outputData.getBoolean(dev.chungjungsoo.gptmobile.data.worker.LocalModelImportWorker.COPY_AVAILABLE, false)
                    importCopyRecovery.value = if (copyAvailable && source != null) {
                        val folder = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { context.getSharedPreferences("local-import-job", android.content.Context.MODE_PRIVATE).getBoolean("folder", false) }
                        dev.chungjungsoo.gptmobile.data.localmodel.LocalModelSource(Uri.parse(source), if (folder) dev.chungjungsoo.gptmobile.data.localmodel.LocalModelSource.Kind.FOLDER else dev.chungjungsoo.gptmobile.data.localmodel.LocalModelSource.Kind.FILE, dev.chungjungsoo.gptmobile.data.localmodel.LocalModelSource.Mode.COPY)
                    } else {
                        null
                    }
                    if (worker?.state?.isFinished == true && worker.id.toString() == latest && ownsGrant && !copyAvailable) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            source?.let { if (!dev.chungjungsoo.gptmobile.data.localmodel.LinkedModelSources.usesGrant(Uri.parse(it))) runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
                            context.getSharedPreferences("local-import-job", android.content.Context.MODE_PRIVATE).edit().putBoolean("ownsGrant", false).apply()
                        }
                    }
                    if (worker?.state == androidx.work.WorkInfo.State.SUCCEEDED) catalogEntries.value = modelCatalogRepository.getCachedVisibleEntries()
                    importedModelId.value = worker?.takeIf { it.state == androidx.work.WorkInfo.State.SUCCEEDED }?.outputData?.getString("modelId")
                    importActive.value = worker != null && !worker.state.isFinished
                    importStatus.value = worker?.let {
                        when (it.state) {
                            androidx.work.WorkInfo.State.RUNNING -> "Importing and verifying · ${it.progress.getLong("bytes", 0) / (1024 * 1024)} MB"
                            androidx.work.WorkInfo.State.ENQUEUED, androidx.work.WorkInfo.State.BLOCKED -> "Import queued; source access will be checked again after an interruption."
                            androidx.work.WorkInfo.State.CANCELLED -> "Import canceled. Choose the source again to retry."
                            else -> it.outputData.getString("message") ?: "Import interrupted. Choose the source again to retry."
                        }
                    }
                }
            }
        }
    }
    fun cancelImport() {
        importContext?.let { androidx.work.WorkManager.getInstance(it).cancelUniqueWork(dev.chungjungsoo.gptmobile.data.worker.LocalModelImportWorker.WORK) }
    }

    fun importCustomModel(contentResolver: ContentResolver, source: dev.chungjungsoo.gptmobile.data.localmodel.LocalModelSource) = importCustomModel(contentResolver, source.uri, source.folder, source.mode)

    fun importCustomModel(contentResolver: ContentResolver, uri: Uri, folder: Boolean = false, mode: dev.chungjungsoo.gptmobile.data.localmodel.LocalModelSource.Mode = dev.chungjungsoo.gptmobile.data.localmodel.LocalModelSource.Mode.LINK) {
        val context = importContext ?: return
        if (importActive.value) return
        importScheduling.value = true
        importActive.value = true
        importedModelId.value = null
        viewModelScope.launch {
            var ownsGrant = false
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val prior = context.getSharedPreferences("local-import-job", android.content.Context.MODE_PRIVATE)
                    val priorSource = prior.getString("source", null)?.let(Uri::parse)
                    val inheritedGrant = prior.getBoolean("ownsGrant", false)
                    if (inheritedGrant && priorSource != null && priorSource != uri && !dev.chungjungsoo.gptmobile.data.localmodel.LinkedModelSources.usesGrant(priorSource)) {
                        runCatching { contentResolver.releasePersistableUriPermission(priorSource, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    }
                    ownsGrant = inheritedGrant && priorSource == uri || contentResolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val request = androidx.work.OneTimeWorkRequestBuilder<dev.chungjungsoo.gptmobile.data.worker.LocalModelImportWorker>()
                        .setInputData(androidx.work.Data.Builder().putString("source", uri.toString()).putBoolean("folder", folder).putString(dev.chungjungsoo.gptmobile.data.worker.LocalModelImportWorker.MODE, mode.name).putBoolean(dev.chungjungsoo.gptmobile.data.worker.LocalModelImportWorker.OWNS_GRANT, ownsGrant).build()).build()
                    check(
                        context.getSharedPreferences("local-import-job", android.content.Context.MODE_PRIVATE).edit()
                            .putString("latest", request.id.toString()).putString("source", uri.toString()).putBoolean("folder", folder).putBoolean("ownsGrant", ownsGrant).commit()
                    ) { "Could not persist the import job" }
                    androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(dev.chungjungsoo.gptmobile.data.worker.LocalModelImportWorker.WORK, androidx.work.ExistingWorkPolicy.KEEP, request).result.get()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                importActive.value = false
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if (ownsGrant && !dev.chungjungsoo.gptmobile.data.localmodel.LinkedModelSources.usesGrant(uri)) {
                        runCatching { contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    }
                }
                customDialogState.value = LocalModelsDialog.ImportFailed("Import could not be queued: ${failure.message.orEmpty().take(300)}. Choose a model file with persistent read access.")
            } finally {
                importScheduling.value = false
            }
        }
    }

    fun startHuggingFaceSignIn(): Intent? = downloadActions.startHuggingFaceSignIn()

    fun onAuthActivityResult(data: Intent?) {
        downloadActions.onAuthActivityResult(data)
    }

    fun onLicenseTabClosed() {
        downloadActions.onLicenseTabClosed()
    }

    fun retryAfterLicense() {
        downloadActions.retryAfterLicense()
    }

    fun openAccessTokenDialog() {
        downloadActions.openAccessTokenDialog()
    }

    fun saveHuggingFaceAccessToken(token: String) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            huggingFaceTokenStore.saveAccessToken(trimmed)
            hasHuggingFaceToken.value = true
            downloadActions.retryAfterAccessToken()
        }
    }

    fun removeHuggingFaceAccessToken() {
        viewModelScope.launch {
            huggingFaceTokenStore.clear()
            hasHuggingFaceToken.value = false
        }
    }

    override fun onCleared() {
        huggingFaceSearchJob?.cancel()
        downloadActions.release()
        super.onCleared()
    }

    private fun localBenchmarkScores(history: List<dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRun>): Map<String, Int> =
        history
            .filter { it.local && it.mode != dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode.DELEGATION && it.suiteVersion == 1 && it.finished && !it.canceled && it.stoppedReason == null }
            .groupBy { it.model }
            .mapNotNull { (model, runs) ->
                dev.chungjungsoo.gptmobile.data.benchmark.benchmarkRating(
                    runs.sortedByDescending { it.startedAt }.let { sorted ->
                        val latest = sorted.first()
                        sorted.filter { it.configKey == latest.configKey && it.backend == latest.backend && it.accelerator == latest.accelerator }.take(5)
                    },
                    local = true
                ).score?.let { model to it }
            }
            .toMap()

    private companion object {
        const val HUGGING_FACE_SEARCH_DEBOUNCE_MS = 350L
    }

    private fun currentStatus(catalogEntryId: String): LocalModelItemStatus? = localListState.value.items.firstOrNull { it.entry.id == catalogEntryId }?.status
}

private data class HuggingFaceSearchState(
    val notice: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

private data class LocalModelDiscoveryState(
    val query: String,
    val filter: LocalModelFilter,
    val source: LocalModelSource,
    val huggingFaceEntries: List<CatalogEntry>,
    val huggingFaceSearch: HuggingFaceSearchState
)

private data class LocalModelsListState(
    val items: List<LocalModelListItem> = emptyList(),
    val isLoading: Boolean = true,
    val totalStorageBytes: Long = 0L
)

data class LocalDiscoveryFilters(val runtime: String = "All", val processor: String = "Any", val capability: String = "Any", val everyModel: Boolean = false)
