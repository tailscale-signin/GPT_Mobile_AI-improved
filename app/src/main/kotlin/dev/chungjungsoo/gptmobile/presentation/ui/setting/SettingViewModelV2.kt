package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.backup.BackupRestoreResult
import dev.chungjungsoo.gptmobile.data.backup.BackupStatus
import dev.chungjungsoo.gptmobile.data.backup.CompleteBackupManager
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
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
    private val completeBackupManager: CompleteBackupManager
) : ViewModel() {

    val platformState: StateFlow<List<PlatformV2>> = settingRepository.observePlatformV2s()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _localRuntimeBackend = MutableStateFlow(LocalRuntimeBackend.DEFAULT)
    val localRuntimeBackend: StateFlow<LocalRuntimeBackend> = _localRuntimeBackend.asStateFlow()

    val debugMode: StateFlow<Boolean> = settingRepository.observeDebugMode()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _backupStatus = MutableStateFlow(completeBackupManager.getBackupStatus())
    val backupStatus: StateFlow<BackupStatus> = _backupStatus.asStateFlow()

    private val _backupUi = MutableStateFlow(BackupUiState())
    val backupUi: StateFlow<BackupUiState> = _backupUi.asStateFlow()
    private var pendingPassword: String? = null

    private val _dialogState = MutableStateFlow(DialogState())
    val dialogState: StateFlow<DialogState> = _dialogState.asStateFlow()

    private val _uiEvent = MutableSharedFlow<UiEvent>()
    val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

    init {
        fetchPlatforms()
        loadLocalRuntimeBackend()
        refreshBackupStatus()
    }

    fun refreshBackupStatus() {
        _backupStatus.value = completeBackupManager.getBackupStatus()
    }

    private fun loadLocalRuntimeBackend() {
        viewModelScope.launch {
            _localRuntimeBackend.value = settingRepository.getLocalRuntimeBackend()
        }
    }

    fun updateLocalRuntimeBackend(backend: LocalRuntimeBackend) {
        viewModelScope.launch {
            settingRepository.updateLocalRuntimeBackend(backend)
            _localRuntimeBackend.value = backend
            _uiEvent.emit(UiEvent.ShowToast("Local inference engine set to ${backend.displayName}"))
        }
    }

    fun updateDebugMode(enabled: Boolean) {
        viewModelScope.launch {
            settingRepository.updateDebugMode(enabled)
            _uiEvent.emit(UiEvent.ShowToast(if (enabled) "Debug diagnostics HUD enabled" else "Debug diagnostics HUD disabled"))
        }
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
            val updated = target.copy(isFavorite = !target.isFavorite)
            updatePlatform(updated)
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

    fun openBackupRestoreDialog() {
        refreshBackupStatus()
        _dialogState.update { it.copy(isBackupRestoreDialogOpen = true) }
    }

    fun closeBackupRestoreDialog() {
        if (_backupUi.value.isBusy) return
        pendingPassword = null
        _backupUi.value = BackupUiState()
        _dialogState.update { it.copy(isBackupRestoreDialogOpen = false) }
    }

    fun updateBackupPassword(value: String) {
        if (!_backupUi.value.isBusy) _backupUi.update { it.copy(password = value, message = null) }
    }

    fun updateBackupConfirmation(value: String) {
        if (!_backupUi.value.isBusy) _backupUi.update { it.copy(confirmation = value, message = null) }
    }

    // Keep picker state in the ViewModel so rotation does not lose the password.
    // Never persist passwords in a SavedStateHandle or a Bundle.
    fun prepareBackupPicker(restoring: Boolean): Boolean {
        val state = _backupUi.value
        if (state.isBusy || (!restoring && !state.canBackup)) return false
        pendingPassword = state.password
        _backupUi.update { it.copy(isBusy = true, message = null, isError = false) }
        return true
    }

    fun cancelBackupPicker() {
        pendingPassword = null
        _backupUi.update { it.copy(isBusy = false, isWorking = false, restoreUri = null) }
    }

    fun backupDestinationSelected(uri: Uri?) {
        if (uri == null) {
            cancelBackupPicker()
            return
        }
        val password = pendingPassword ?: run {
            cancelBackupPicker()
            return
        }
        runBackupOperation { completeBackupManager.backup(uri, password) }
    }

    fun restoreSourceSelected(uri: Uri?) {
        if (uri == null || pendingPassword == null) {
            cancelBackupPicker()
            return
        }
        _backupUi.update { it.copy(restoreUri = uri) }
    }

    fun confirmRestore() {
        val uri = _backupUi.value.restoreUri ?: return
        val password = pendingPassword ?: return
        _backupUi.update { it.copy(restoreUri = null) }
        runBackupOperation { completeBackupManager.restore(uri, password) }
    }

    private fun runBackupOperation(operation: suspend () -> BackupRestoreResult) {
        if (_backupUi.value.isWorking) return
        _backupUi.update { it.copy(isBusy = true, isWorking = true) }
        viewModelScope.launch {
            try {
                val result = operation()
                _backupUi.update {
                    it.copy(
                        message = result.message,
                        isError = !result.success,
                        password = if (result.success) "" else it.password,
                        confirmation = if (result.success) "" else it.confirmation
                    )
                }
                refreshBackupStatus()
                if (result.success) {
                    fetchPlatforms()
                    loadLocalRuntimeBackend()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _backupUi.update { it.copy(message = error.localizedMessage ?: "Backup or restore failed.", isError = true) }
            } finally {
                cancelBackupPicker()
            }
        }
    }

    data class BackupUiState(
        val password: String = "",
        val confirmation: String = "",
        val isBusy: Boolean = false,
        val isWorking: Boolean = false,
        val restoreUri: Uri? = null,
        val message: String? = null,
        val isError: Boolean = false
    ) {
        val canBackup: Boolean get() = !isBusy && password.length >= 8 && password == confirmation
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
