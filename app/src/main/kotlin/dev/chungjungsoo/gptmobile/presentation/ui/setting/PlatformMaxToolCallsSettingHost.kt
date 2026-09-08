package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Observes the current platform and persists maximum-tool-call changes. */
@Composable
fun PlatformMaxToolCallsSettingHost(settingViewModel: PlatformSettingViewModel) {
    val platform by settingViewModel.platformState.collectAsStateWithLifecycle()
    platform?.let { platformData ->
        MaxToolCallsSetting(
            maxToolCalls = platformData.maxToolCalls,
            enabled = platformData.enabled,
            onMaxToolCallsChanged = { settingViewModel.updateMaxToolCalls(platformData, it) }
        )
    }
}
