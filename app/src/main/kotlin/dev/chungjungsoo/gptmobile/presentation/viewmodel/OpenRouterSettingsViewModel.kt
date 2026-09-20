package dev.chungjungsoo.gptmobile.presentation.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.repository.OpenRouterSettingsRepository
import dev.chungjungsoo.gptmobile.domain.model.OpenRouterSettings
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class OpenRouterSettingsViewModel @Inject constructor(
    private val repository: OpenRouterSettingsRepository
) : ViewModel() {

    private val _settings = MutableLiveData<OpenRouterSettings>()
    val settings: LiveData<OpenRouterSettings> = _settings

    init {
        loadSettings()
    }

    fun loadSettings() {
        viewModelScope.launch {
            _settings.value = repository.loadSettings()
        }
    }

    fun updateSettings(newSettings: OpenRouterSettings) {
        viewModelScope.launch {
            repository.saveSettings(newSettings)
            _settings.value = newSettings
        }
    }

    fun validateApiKey(key: String): Boolean {
        return key.isNotBlank() && key.length >= 32
    }
}
