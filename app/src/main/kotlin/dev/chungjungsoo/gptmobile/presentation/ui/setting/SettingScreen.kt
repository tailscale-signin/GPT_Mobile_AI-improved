package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Compile-safe settings route shell while the settings UI is migrated to the
 * V2 view-model and navigation contracts.
 */
@Composable
fun SettingScreen(
    settingViewModel: SettingViewModelV2,
    onNavigationClick: () -> Unit,
    onNavigateToAddPlatform: () -> Unit,
    onNavigateToPlatformSetting: (String) -> Unit,
    onNavigateToLocalModels: () -> Unit,
    onNavigateToToolConnections: () -> Unit,
    onNavigateToAboutPage: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Intentionally empty until the settings UI migration is complete.
}
