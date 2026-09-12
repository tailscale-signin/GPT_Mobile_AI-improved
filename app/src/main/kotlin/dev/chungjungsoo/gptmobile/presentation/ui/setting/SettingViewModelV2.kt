package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.backup.AppBackupManager
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.domain.usecase.ManagePlatformsUseCase
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class SettingViewModelV2 @Inject constructor(
    private val settingRepository: SettingRepository,
    private val appBackupManager: AppBackupManager,
    private val managePlatformsUseCase: ManagePlatformsUseCase
) : ViewModel() {

    val platformState: StateFlow<List<PlatformV2>> = settingRepository.observePlatformV2s()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _dialogState = MutableStateFlow(DialogState())
    val dialogState: StateFlow<DialogState> = _dialogState.asStateFlow()

    private val _uiEvent = MutableSharedFlow<UiEvent>()
    val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

    init {
        fetchPlatforms()
    }

    fun fetchPlatforms() {
        viewModelScope.launch {
            settingRepository.fetchPlatformV2s()
        }
    }

    fun addPlatform(platform: PlatformV2) {
        viewModelScope.launch {
            settingRepository.addPlatformV2(platform)
        }
    }

    fun updatePlatform(platform: PlatformV2) {
        viewModelScope.launch {
            settingRepository.updatePlatformV2(platform)
        }
    }

    fun deletePlatform(platform: PlatformV2) {
        viewModelScope.launch {
            settingRepository.deletePlatformV2(platform)
        }
    }

    fun togglePlatformEnabled(platformId: Int) {
        val platform = platformState.value.find { it.id == platformId }
        platform?.let { target ->
            val updated = target.copy(enabled = !target.enabled)
            updatePlatform(updated)
        }
    }

    fun togglePlatformFavorite(platformId: Int) {
        val platform = platformState.value.find { it.id == platformId }
        platform?.let { target ->
            viewModelScope.launch {
                managePlatformsUseCase.toggleFavoritePlatform(target.id, !target.isFavorite)
                fetchPlatforms()
            }
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
            val platform = platformState.value.find { it.id == platformId }
            platform?.let { deletePlatform(it) }
        }
        closeDeleteDialog()
    }

    fun openBackupRestoreDialog() = _dialogState.update { it.copy(isBackupRestoreDialogOpen = true) }

    fun closeBackupRestoreDialog() = _dialogState.update { it.copy(isBackupRestoreDialogOpen = false) }

    fun exportConfigurationToFile(uri: Uri, passphrase: String? = null) {
        viewModelScope.launch {
            val result = appBackupManager.exportConfiguration(uri, passphrase)
            if (result.success) {
                _uiEvent.emit(UiEvent.ShowToast(result.message))
            } else {
                _uiEvent.emit(UiEvent.ShowToast("Export failed: ${result.message}"))
            }
        }
    }

    fun restoreConfigurationFromFile(uri: Uri, passphrase: String? = null) {
        viewModelScope.launch {
            val result = appBackupManager.restoreConfiguration(uri, passphrase)
            if (result.success) {
                fetchPlatforms()
                _uiEvent.emit(UiEvent.ShowToast("Configuration restored successfully (${result.count} platforms imported)."))
            } else {
                _uiEvent.emit(UiEvent.ShowToast("Restore failed: ${result.message}"))
            }
        }
    }

    fun exportDatabaseToFile(uri: Uri, passphrase: String? = null) {
        viewModelScope.launch {
            val result = appBackupManager.exportDatabase(uri, passphrase)
            if (result.success) {
                _uiEvent.emit(UiEvent.ShowToast(result.message))
            } else {
                _uiEvent.emit(UiEvent.ShowToast("Export failed: ${result.message}"))
            }
        }
    }

    fun restoreDatabaseFromFile(uri: Uri, passphrase: String? = null) {
        viewModelScope.launch {
            val result = appBackupManager.restoreDatabase(uri, passphrase)
            if (result.success) {
                _uiEvent.emit(UiEvent.ShowToast("Database restored successfully (${result.count} chat(s) imported)."))
            } else {
                _uiEvent.emit(UiEvent.ShowToast("Restore failed: ${result.message}"))
            }
        }
    }

    sealed interface UiEvent {
        data class ShowToast(val message: String) : UiEvent
    }

    data class DialogState(
        val isThemeDialogOpen: Boolean = false,
        val isDeleteDialogOpen: Boolean = false,
        val platformToDelete: Int? = null,
        val isBackupRestoreDialogOpen: Boolean = false
    )
}
