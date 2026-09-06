package dev.melo.gptmobile.improved.presentation.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.melo.gptmobile.improved.data.model.Setting
import dev.melo.gptmobile.improved.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class MainViewModel @Inject constructor(
    settingRepository: SettingRepository
) : ViewModel() {
    val setting: StateFlow<Setting> = settingRepository.settingFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        Setting()
    )
}
