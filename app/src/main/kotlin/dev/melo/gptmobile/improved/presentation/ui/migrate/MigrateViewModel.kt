package dev.melo.gptmobile.improved.presentation.ui.migrate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.melo.gptmobile.improved.data.repository.MigrationRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface MigrateUiState {
    data object Initial : MigrateUiState
    data object Migrating : MigrateUiState
    data object Success : MigrateUiState
    data class Error(val message: String) : MigrateUiState
}

@HiltViewModel
class MigrateViewModel @Inject constructor(
    private val migrationRepository: MigrationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<MigrateUiState>(MigrateUiState.Initial)
    val uiState: StateFlow<MigrateUiState> = _uiState.asStateFlow()

    fun startMigration() {
        viewModelScope.launch {
            _uiState.value = MigrateUiState.Migrating
            try {
                migrationRepository.migrate()
                _uiState.value = MigrateUiState.Success
            } catch (e: Exception) {
                _uiState.value = MigrateUiState.Error(e.message ?: "Unknown migration error")
            }
        }
    }
}
