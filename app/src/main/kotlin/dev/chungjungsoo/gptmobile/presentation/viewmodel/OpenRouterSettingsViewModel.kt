package dev.chungjungsoo.gptmobile.presentation.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.repository.OpenRouterSettingsRepository
import dev.chungjungsoo.gptmobile.domain.model.OpenRouterSettings
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class OpenRouterSettingsViewModel @Inject constructor(
    private val repository: OpenRouterSettingsRepository
) : ViewModel() {

    private val _settings = MutableLiveData<OpenRouterSettings>()
    val settings: LiveData<OpenRouterSettings> = _settings

    private val _uiState = MutableStateFlow(OpenRouterSettings(apiKey = ""))
    val uiState: StateFlow<OpenRouterSettings> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    fun loadSettings() {
        viewModelScope.launch {
            val s = repository.loadSettings()
            _settings.value = s
            _uiState.value = s
        }
    }

    fun updateSettings(newSettings: OpenRouterSettings) {
        val validated = newSettings.copy(
            batchSize = newSettings.batchSize.coerceIn(1, 100),
            maxRetries = newSettings.maxRetries.coerceIn(0, 10),
            cacheTtlSeconds = newSettings.cacheTtlSeconds.coerceAtLeast(0)
        )
        viewModelScope.launch {
            repository.saveSettings(validated)
            _settings.value = validated
            _uiState.value = validated
        }
    }

    fun validateApiKey(key: String): Boolean {
        return key.isNotBlank() && key.length >= 32
    }
}
