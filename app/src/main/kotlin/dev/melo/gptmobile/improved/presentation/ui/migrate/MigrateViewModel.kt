package dev.melo.gptmobile.improved.presentation.ui.migrate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.melo.gptmobile.improved.data.repository.ChatRepository
import dev.melo.gptmobile.improved.data.repository.SettingRepository
import javax.inject.Inject
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

@HiltViewModel
class MigrateViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val settingRepository: SettingRepository
) : ViewModel() {

    private val _migrationState = MutableStateFlow<MigrationState>(MigrationState.Idle)
    val migrationState: StateFlow<MigrationState> = _migrationState.asStateFlow()

    fun startMigration() {
        viewModelScope.launch {
            _migrationState.value = MigrationState.InProgress
            try {
                settingRepository.migrateToPlatformV2()
                _migrationState.value = MigrationState.Success("Migration completed successfully.")
            } catch (e: Exception) {
                _migrationState.value = MigrationState.Error(e.message ?: "Unknown error occurred")
            }
        }
    }
}
