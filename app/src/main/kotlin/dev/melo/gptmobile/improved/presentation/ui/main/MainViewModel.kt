package dev.melo.gptmobile.improved.presentation.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.melo.gptmobile.improved.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingRepository: SettingRepository
) : ViewModel() {

    sealed interface SplashEvent {
        data object OpenIntro : SplashEvent
        data object OpenMigrate : SplashEvent
    }

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _event = MutableSharedFlow<SplashEvent>(replay = 1)
    val event: SharedFlow<SplashEvent> = _event.asSharedFlow()

    init {
        checkInitialState()
    }

    private fun checkInitialState() {
        viewModelScope.launch {
            try {
                val platformV2s = settingRepository.fetchPlatformV2s()
                val legacyPlatforms = settingRepository.fetchPlatforms()

                val hasLegacyConfigured = legacyPlatforms.any { it.enabled || !it.token.isNullOrBlank() }

                if (platformV2s.isEmpty()) {
                    if (hasLegacyConfigured) {
                        _event.emit(SplashEvent.OpenMigrate)
                    } else {
                        _event.emit(SplashEvent.OpenIntro)
                    }
                }
            } catch (_: Exception) {
                // If anything fails during startup check, fall through gracefully
            } finally {
                _isReady.value = true
            }
        }
    }
}
