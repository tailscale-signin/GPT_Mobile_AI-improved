package dev.melo.gptmobile.improved.presentation.ui.migrate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.melo.gptmobile.improved.data.repository.MigrationProgress
import dev.melo.gptmobile.improved.data.repository.MigrationRepository
import dev.melo.gptmobile.improved.data.repository.MigrationState
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MigrateUiState(
    val migrationState: MigrationState = MigrationState.Idle,
)

@HiltViewModel
class MigrateViewModel @Inject constructor(
    private val migrationRepository: MigrationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MigrateUiState())
    val uiState: StateFlow<MigrateUiState> = _uiState.asStateFlow()

    fun startExport() {
        viewModelScope.launch {
            migrationRepository.exportData().collect { state ->
                _uiState.update { it.copy(migrationState = state) }
            }
        }
    }

    fun startImport() {
        viewModelScope.launch {
            migrationRepository.importData().collect { state ->
                _uiState.update { it.copy(migrationState = state) }
            }
        }
    }

    fun resetState() {
        _uiState.update { it.copy(migrationState = MigrationState.Idle) }
    }
}
