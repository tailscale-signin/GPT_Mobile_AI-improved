package dev.melo.gptmobile.improved.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.melo.gptmobile.improved.data.database.entity.PlatformV2
import dev.melo.gptmobile.improved.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class SettingViewModelV2 @Inject constructor(
    private val settingRepository: SettingRepository
) : ViewModel() {

    private val _platformState = MutableStateFlow(listOf<PlatformV2>())
    val platformState: StateFlow<List<PlatformV2>> = _platformState.asStateFlow()

    private val _dialogState = MutableStateFlow(DialogState())
    val dialogState: StateFlow<DialogState> = _dialogState.asStateFlow()

    private val _uiEvent = MutableSharedFlow<UiEvent>()
    val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

    init {
        fetchPlatforms()
    }

    fun fetchPlatforms() {
        viewModelScope.launch {
            val platforms = settingRepository.fetchPlatformV2s()
            _platformState.update { platforms }
        }
    }

    fun addPlatform(platform: PlatformV2) {
        viewModelScope.launch {
            settingRepository.addPlatformV2(platform)
            fetchPlatforms()
        }
    }

    fun updatePlatform(platform: PlatformV2) {
        viewModelScope.launch {
            settingRepository.updatePlatformV2(platform)
            fetchPlatforms()
        }
    }

    fun deletePlatform(platform: PlatformV2) {
        viewModelScope.launch {
            settingRepository.deletePlatformV2(platform)
            fetchPlatforms()
        }
    }

    fun togglePlatformEnabled(platformId: Int) {
        val platform = _platformState.value.find { it.id == platformId }
        platform?.let {
            updatePlatform(it.copy(enabled = !it.enabled))
        }
    }

    fun openThemeDialog() = _dialogState.update { it.copy(isThemeDialogOpen = true) }

    fun closeThemeDialog() = _dialogState.update { it.copy(isThemeDialogOpen = false) }

    fun openDeleteDialog(platformId: Int) = _dialogState.update {
        it.copy(
            isDeleteDialogOpen = true,
            platformToDelete = platformId
        )
    }

    fun closeDeleteDialog() = _dialogState.update {
        it.copy(
            isDeleteDialogOpen = false,
            platformToDelete = null
        )
    }

    fun confirmDelete() {
        _dialogState.value.platformToDelete?.let { platformId ->
            val platform = _platformState.value.find { it.id == platformId }
            platform?.let { deletePlatform(it) }
        }
        closeDeleteDialog()
    }

    fun openBackupRestoreDialog() = _dialogState.update { it.copy(isBackupRestoreDialogOpen = true) }

    fun closeBackupRestoreDialog() = _dialogState.update { it.copy(isBackupRestoreDialogOpen = false) }

    fun openExportDialog() {
        viewModelScope.launch {
            val json = settingRepository.exportConfigurationJson()
            _dialogState.update {
                it.copy(
                    isBackupRestoreDialogOpen = false,
                    isExportDialogOpen = true,
                    exportedConfigJson = json
                )
            }
        }
    }

    fun closeExportDialog() = _dialogState.update {
        it.copy(
            isExportDialogOpen = false,
            exportedConfigJson = ""
        )
    }

    fun openRestoreDialog() = _dialogState.update {
        it.copy(
            isBackupRestoreDialogOpen = false,
            isRestoreDialogOpen = true,
            restoreJsonInput = "",
            restoreErrorMessage = null
        )
    }

    fun closeRestoreDialog() = _dialogState.update {
        it.copy(
            isRestoreDialogOpen = false,
            restoreJsonInput = "",
            restoreErrorMessage = null
        )
    }

    fun onRestoreJsonInputChanged(input: String) = _dialogState.update {
        it.copy(restoreJsonInput = input, restoreErrorMessage = null)
    }

    fun restoreConfiguration() {
        val json = _dialogState.value.restoreJsonInput.trim()
        if (json.isBlank()) return

        viewModelScope.launch {
            val result = settingRepository.importConfigurationJson(json)
            result.onSuccess { count ->
                fetchPlatforms()
                closeRestoreDialog()
                _uiEvent.emit(UiEvent.RestoreSuccess(count))
            }.onFailure { error ->
                _dialogState.update {
                    it.copy(restoreErrorMessage = error.localizedMessage ?: "Invalid configuration format")
                }
            }
        }
    }

    sealed interface UiEvent {
        data class RestoreSuccess(val count: Int) : UiEvent
    }

    data class DialogState(
        val isThemeDialogOpen: Boolean = false,
        val isDeleteDialogOpen: Boolean = false,
        val platformToDelete: Int? = null,
        val isBackupRestoreDialogOpen: Boolean = false,
        val isExportDialogOpen: Boolean = false,
        val exportedConfigJson: String = "",
        val isRestoreDialogOpen: Boolean = false,
        val restoreJsonInput: String = "",
        val restoreErrorMessage: String? = null
    )
}
