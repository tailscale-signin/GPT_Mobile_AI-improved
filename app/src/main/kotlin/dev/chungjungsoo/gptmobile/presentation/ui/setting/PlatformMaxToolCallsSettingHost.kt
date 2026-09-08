package dev.chungjungsoo.gptmobile.presentation.ui.setting

// This file intentionally delegates the max-tool-call preference wiring to a
// small composable wrapper so the platform settings UI persists edits through
// PlatformSettingViewModel.

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Displays the per-platform maximum tool-call preference and persists changes.
 *
 * This host is kept separate from [MaxToolCallsSetting] so it can observe the
 * latest platform state rather than retaining a stale PlatformV2 instance.
 */
@Composable
fun PlatformMaxToolCallsSettingHost(
    settingViewModel: PlatformSettingViewModel
) {
    val platform by settingViewModel.platformState.collectAsStateWithLifecycle()
    platform?.let { platformData ->
        MaxToolCallsSetting(
            maxToolCalls = platformData.maxToolCalls,
            enabled = platformData.enabled,
            onMaxToolCallsChanged = {
                settingViewModel.updateMaxToolCalls(platformData, it)
            }
        )
    }
}
