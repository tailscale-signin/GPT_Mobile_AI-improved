package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import dev.chungjungsoo.gptmobile.presentation.common.Route
import dev.chungjungsoo.gptmobile.presentation.common.ThemeViewModel

/**
 * Settings route entry point.
 *
 * The former UI referenced backup, platform, theme, drawable, and navigation
 * APIs that are not part of the current application contract. This safe shell
 * keeps navigation source-compatible and restores compilation while those
 * features are migrated independently.
 */
@Composable
fun SettingScreen(
    modifier: Modifier = Modifier,
    onNavigateTo: (Route) -> Unit = {},
    onBack: () -> Unit = {},
    themeViewModel: ThemeViewModel,
    settingViewModel: SettingViewModel = hiltViewModel()
) {
    // Intentionally empty until the UI is migrated to the current APIs.
}
