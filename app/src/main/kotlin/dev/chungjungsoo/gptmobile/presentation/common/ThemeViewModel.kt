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

    fun updateDynamicTheme(theme: DynamicTheme) {
        viewModelScope.launch {
            persistTheme { it.copy(dynamicTheme = theme, activeProfileId = "custom") }
        }
    }

    fun updateThemeMode(theme: ThemeMode) {
        viewModelScope.launch {
            persistTheme { it.copy(themeMode = theme, activeProfileId = "custom") }
        }
    }

    fun updateCustomPrimaryArgb(argb: Long?) {
        viewModelScope.launch {
            persistTheme { it.copy(customPrimaryArgb = argb, customPalette = null, activeProfileId = "custom") }
        }
    }

    fun updateCustomPalette(palette: dev.chungjungsoo.gptmobile.data.dto.CustomThemePalette?) {
        viewModelScope.launch {
            persistTheme { it.copy(dynamicTheme = DynamicTheme.OFF, customPalette = palette, customPrimaryArgb = palette?.primary, activeProfileId = "custom") }
        }
    }

    fun applyProfile(profile: dev.chungjungsoo.gptmobile.data.dto.SavedThemeProfile) {
        viewModelScope.launch { persistTheme { it.copy(dynamicTheme = DynamicTheme.OFF, themeMode = profile.mode, customPalette = profile.palette, customPrimaryArgb = profile.palette.primary, activeProfileId = profile.id.takeIf { it.isNotBlank() }) } }
    }

    fun saveProfile(name: String, palette: dev.chungjungsoo.gptmobile.data.dto.CustomThemePalette) {
        val label = name.trim()
        if (label.codePointCount(0, label.length) > 40) return
        if (label.isEmpty()) return
        viewModelScope.launch {
            persistTheme { current ->
                val profile = dev.chungjungsoo.gptmobile.data.dto.SavedThemeProfile(label, palette, current.themeMode)
                if (current.savedProfiles.any { it.name.equals(label, true) }) current else current.copy(savedProfiles = current.savedProfiles + profile.copy(id = "saved:" + java.util.UUID.randomUUID()))
            }
        }
    }

    fun deleteProfile(name: String) {
        viewModelScope.launch { persistTheme { it.copy(savedProfiles = it.savedProfiles.filterNot { profile -> profile.name == name }) } }
    }

    private suspend fun persistTheme(transform: (ThemeSetting) -> ThemeSetting) {
        themeMutex.withLock {
            val updated = settingRepository.mutateThemes(transform)
            _themeSetting.value = updated
        }
    }
}
