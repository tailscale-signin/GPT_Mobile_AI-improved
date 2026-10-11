package dev.chungjungsoo.gptmobile.presentation.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.dto.ThemeSetting
import dev.chungjungsoo.gptmobile.data.model.DynamicTheme
import dev.chungjungsoo.gptmobile.data.model.ThemeMode
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@HiltViewModel
class ThemeViewModel @Inject constructor(private val settingRepository: SettingRepository) : ViewModel() {

    private val _themeSetting = MutableStateFlow(ThemeSetting())
    val themeSetting = _themeSetting.asStateFlow()

    private val themeMutex = Mutex()

    init {
        fetchThemes()
    }

    private fun fetchThemes() {
        viewModelScope.launch {
            settingRepository.observeThemes().collect {
                themeMutex.withLock { _themeSetting.value = settingRepository.fetchThemes() }
            }
        }
    }

    fun applyDraft(draft: ThemeSetting, onError: (String) -> Unit = {}, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                persistTheme { draft }
                onSaved()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                onError(error.message ?: "Could not save appearance. Please try again.")
            }
        }
    }

    fun updateDynamicTheme(theme: DynamicTheme) {
        viewModelScope.launch {
            persistTheme { it.copy(dynamicTheme = theme) }
        }
    }

    fun updateThemeMode(theme: ThemeMode) {
        viewModelScope.launch {
            persistTheme { it.copy(themeMode = theme) }
        }
    }

    fun updateCustomPrimaryArgb(argb: Long?) {
        viewModelScope.launch {
            persistTheme { it.copy(customPrimaryArgb = argb, customPalette = null, themeFamily = null) }
        }
    }

    fun updateCustomPalette(palette: dev.chungjungsoo.gptmobile.data.dto.CustomThemePalette?) {
        viewModelScope.launch {
            persistTheme { it.copy(dynamicTheme = DynamicTheme.OFF, customPalette = palette, customPrimaryArgb = palette?.primary, themeFamily = null) }
        }
    }

    fun applyProfile(profile: dev.chungjungsoo.gptmobile.data.dto.SavedThemeProfile) {
        viewModelScope.launch { persistTheme { it.copy(dynamicTheme = DynamicTheme.OFF, themeMode = profile.mode, customPalette = profile.palette, customPrimaryArgb = profile.palette.primary, themeFamily = null) } }
    }

    fun saveProfile(name: String, palette: dev.chungjungsoo.gptmobile.data.dto.CustomThemePalette) {
        val label = name.trim().take(40)
        if (label.isEmpty()) return
        viewModelScope.launch {
            persistTheme { current ->
                val profile = dev.chungjungsoo.gptmobile.data.dto.SavedThemeProfile(label, palette, current.themeMode)
                current.copy(dynamicTheme = DynamicTheme.OFF, customPalette = palette, customPrimaryArgb = palette.primary, themeFamily = null, savedProfiles = current.savedProfiles.filterNot { it.name.equals(label, true) } + profile)
            }
        }
    }

    fun deleteProfile(name: String) {
        viewModelScope.launch { persistTheme { it.copy(savedProfiles = it.savedProfiles.filterNot { profile -> profile.name == name }) } }
    }

    private suspend fun persistTheme(transform: (ThemeSetting) -> ThemeSetting) {
        themeMutex.withLock {
            val updated = transform(_themeSetting.value)
            settingRepository.updateThemes(updated)
            _themeSetting.value = updated
        }
    }
}
