package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

/**
 * Platform settings entry point.
 *
 * The previous implementation mixed APIs from a different settings model and
 * could not compile. Keep the route available while the screen is migrated to
 * the current PlatformSettingViewModel contract.
 */
@Composable
fun PlatformSettingScreen(
    modifier: Modifier = Modifier,
    settingViewModel: PlatformSettingViewModel = hiltViewModel(),
    onNavigationClick: () -> Unit = {}
) {
    // Intentionally empty until the UI is migrated to the current view-model API.
}
