package dev.melo.gptmobile.improved.presentation.ui.migrate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.melo.gptmobile.improved.data.repository.ChatRepository
import dev.melo.gptmobile.improved.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface MigrationState {
    data object Idle : MigrationState
    data object InProgress : MigrationState
    data class Success(val message: String) : MigrationState
    data class Error(val message: String) : MigrationState
}

class MigrateViewModel(
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _migrationState = MutableStateFlow<MigrationState>(MigrationState.Idle)
    val migrationState: StateFlow<MigrationState> = _migrationState.asStateFlow()

    fun startMigration() {
        viewModelScope.launch {
            _migrationState.value = MigrationState.InProgress
            try {
                // Perform data migration if needed
                _migrationState.value = MigrationState.Success("Migration completed successfully.")
            } catch (e: Exception) {
                _migrationState.value = MigrationState.Error(e.message ?: "Unknown error occurred")
            }
        }
    }
}
