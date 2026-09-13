package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.catalog.CatalogEntry
import dev.chungjungsoo.gptmobile.data.huggingface.HuggingFaceTokenStore
import dev.chungjungsoo.gptmobile.data.localmodel.GatedDownloadCoordinator
import dev.chungjungsoo.gptmobile.data.localmodel.LocalModelImportResult
import dev.chungjungsoo.gptmobile.data.localmodel.LocalModelStatus
import dev.chungjungsoo.gptmobile.data.repository.LocalModelRepository
import dev.chungjungsoo.gptmobile.data.repository.ModelCatalogRepository
import dev.chungjungsoo.gptmobile.di.DeviceSocModel
import dev.chungjungsoo.gptmobile.presentation.ui.localmodel.HuggingFaceAuthClient
import dev.chungjungsoo.gptmobile.presentation.ui.localmodel.LocalDownloadGuards
import dev.chungjungsoo.gptmobile.presentation.ui.localmodel.LocalModelDownloadActions
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class LocalModelsViewModel @Inject constructor(
    private val modelCatalogRepository: ModelCatalogRepository,
    private val localModelRepository: LocalModelRepository,
    gatedDownloadCoordinator: GatedDownloadCoordinator,
    private val huggingFaceTokenStore: HuggingFaceTokenStore,
    downloadGuards: LocalDownloadGuards,
    huggingFaceAuthClient: HuggingFaceAuthClient,
    @param:DeviceSocModel private val deviceSocModel: String
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

    private val _listState = MutableStateFlow(LocalModelsListState())
    private val listState = _listState.asStateFlow()
    private val _dialog = MutableStateFlow<LocalModelsDialog>(LocalModelsDialog.Hidden)
    private val hasHuggingFaceToken = MutableStateFlow(false)

    val uiState: StateFlow<LocalModelsUiState> = combine(
        _listState,
        downloadActions.uiState,
        _dialog,
        hasHuggingFaceToken
    ) { list, download, customDialog, hasToken ->
        LocalModelsUiState(
            items = list.items,
            isLoading = list.isLoading,
            totalStorageBytes = list.totalStorageBytes,
            checkingAccessEntryId = download.checkingAccessEntryId,
            dialog = if (customDialog !is LocalModelsDialog.Hidden) customDialog else download.dialog,
            hasHuggingFaceToken = hasToken
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LocalModelsUiState())

    init {
        viewModelScope.launch {
            hasHuggingFaceToken.value = huggingFaceTokenStore.readAccessToken() != null
        }
        viewModelScope.launch {
            runCatching { localModelRepository.reconcile() }
            val catalogEntries = runCatching { modelCatalogRepository.getVisibleEntries() }
                .getOrDefault(emptyList())
            combine(
                localModelRepository.observeAll(),
                localModelRepository.observeWorkInfos()
            ) { localModels, workInfos ->
                val items = catalogLocalModelItems(
                    catalogEntries,
                    localModels,
                    workInfos,
                    localModels.associate { it.catalogEntryId to localModelRepository.diskPartialBytes(it) },
                    deviceSocModel = deviceSocModel
                )
                val storage = localModels
                    .filter { it.status == LocalModelStatus.READY }
                    .sumOf { it.totalBytes }
                items to storage
            }.collect { (items, storage) ->
                _listState.update {
                    it.copy(
                        items = items,
                        isLoading = false,
                        totalStorageBytes = storage
                    )
                }
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
        _dialog.value = LocalModelsDialog.DeleteConfirm(entry)
    }

    fun confirmDelete() {
        val entry = (_dialog.value as? LocalModelsDialog.DeleteConfirm)?.entry ?: return
        _dialog.value = LocalModelsDialog.Hidden
        viewModelScope.launch { localModelRepository.deleteModel(entry.id) }
    }

    fun cancelDownload(entry: CatalogEntry) {
        viewModelScope.launch { localModelRepository.cancelDownload(entry.id) }
    }

    fun dismissDialog() {
        if (_dialog.value !is LocalModelsDialog.Hidden) {
            _dialog.value = LocalModelsDialog.Hidden
        } else {
            downloadActions.dismissDialog()
        }
    }

    fun importCustomModel(contentResolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            val fileName = queryDisplayName(contentResolver, uri) ?: uri.lastPathSegment ?: "custom.gguf"
            val inputStream = runCatching { contentResolver.openInputStream(uri) }.getOrNull()
            if (inputStream == null) {
                _dialog.value = LocalModelsDialog.ImportFailed("Could not open file stream.")
                return@launch
            }
            val result = localModelRepository.importCustomModel(inputStream, fileName)
            if (result is LocalModelImportResult.Failure) {
                _dialog.value = LocalModelsDialog.ImportFailed(result.message)
            }
        }
    }

    private fun queryDisplayName(contentResolver: ContentResolver, uri: Uri): String? {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            return cursor.getString(nameIndex)
                        }
                    }
                }
            }
        }
        return uri.path?.substringAfterLast('/')
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
        downloadActions.release()
        super.onCleared()
    }

    private fun currentStatus(catalogEntryId: String): LocalModelItemStatus? = _listState.value.items.firstOrNull { it.entry.id == catalogEntryId }?.status
}

private data class LocalModelsListState(
    val items: List<LocalModelListItem> = emptyList(),
    val isLoading: Boolean = true,
    val totalStorageBytes: Long = 0L
)
