package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.presentation.designsystem.AppDestinationRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingScreen(
    settingViewModel: SettingViewModelV2,
    onNavigationClick: () -> Unit,
    onNavigateToAiPlatforms: () -> Unit,
    onNavigateToLocalModels: () -> Unit,
    onNavigateToOpenRouterSettings: () -> Unit = {},
    onNavigateToToolConnections: () -> Unit,
    onNavigateToAdvancedSettings: () -> Unit,
    onNavigateToDebugDiagnostics: () -> Unit,
    onNavigateToAboutPage: () -> Unit,
    onNavigateToFactVault: () -> Unit = {},
    onNavigateToWorkspaces: () -> Unit = {},
    githubWorkspaceEnabled: Boolean = false,
    onNavigateToAppearance: () -> Unit = settingViewModel::openThemeDialog,
    onSearchDestination: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val platforms by settingViewModel.platformState.collectAsStateWithLifecycle()
    val providers by settingViewModel.providerConnections.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val entries = SettingsSearchRegistry.entries + memoryControlSearchEntries + privacyControlSearchEntries +
        platforms.flatMap { profile -> profileControlSearchEntries(profile, providers.any { it.uid == profile.providerConnectionUid }) + SettingsSearchEntry("profile.${profile.uid}", profile.name, "AI › Profiles › ${profile.name}", SettingsSearchDestination.PROFILES, profile.uid, "${profile.model} ${profile.compatibleType}") }
    Scaffold(modifier = modifier.fillMaxSize(), topBar = {
        LargeTopAppBar(title = { Text("Settings") }, actions = {
            IconButton(onClick = onNavigateToDebugDiagnostics) { Icon(Icons.Rounded.Timeline, "Activity") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SettingsDiscoveryPanel(query, { query = it }, entries, { onSearchDestination(it.destination.name, it.focus) }) }
            if (query.isBlank()) {
                item { AppDestinationRow("Appearance", "Theme, colour and display", Icons.Rounded.Palette, onNavigateToAppearance) }
                item { AppDestinationRow("Conversations", "Reading, composer and notifications", Icons.Rounded.ChatBubbleOutline, { onSearchDestination("CONVERSATIONS", "") }) }
                item { AppDestinationRow("Memory", "Saved knowledge and recall controls", Icons.Rounded.Psychology, onNavigateToFactVault) }
                item { AppDestinationRow("Privacy & data", "Access, backup and storage", Icons.Rounded.Security, { onSearchDestination("PRIVACY", "") }) }
                item { AppDestinationRow("Activity & diagnostics", "Runs, usage and benchmarks", Icons.Rounded.Timeline, onNavigateToDebugDiagnostics) }
                item { AppDestinationRow("About", "Version, credits and licences", Icons.Rounded.Info, onNavigateToAboutPage) }
            }
        }
    }
}

@Composable
fun ThemeSettingDialog(settingViewModel: SettingViewModelV2) {
    ThemeSettingsScreen(onDismiss = settingViewModel::closeThemeDialog)
}
