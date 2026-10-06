package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon

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
    modifier: Modifier = Modifier
) {
    val platforms by settingViewModel.platformState.collectAsState()
    val providerConnections by settingViewModel.providerConnections.collectAsState()
    val dialogState by settingViewModel.dialogState.collectAsState()
    val debugMode by settingViewModel.debugMode.collectAsState()
    val featureSettings by settingViewModel.featureSettings.collectAsState()
    var showDelegation by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigationClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.go_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                SettingsDiscoveryPanel(
                    featureSettings,
                    destinations = buildMap {
                        put("Models · profiles · reasoning · search engines · crawler", onNavigateToAiPlatforms)
                        put("Memory · semantic recall · documents", onNavigateToFactVault)
                        put("Plugins · remote MCP · GitHub tools", onNavigateToToolConnections)
                        if (githubWorkspaceEnabled) put("GitHub workspace · repositories · code · pull requests", onNavigateToWorkspaces)
                        put("Theme · appearance · colors", settingViewModel::openThemeDialog)
                        put("Privacy · storage · runtime · advanced", onNavigateToAdvancedSettings)
                        put("Debug · statistics · benchmarks", onNavigateToDebugDiagnostics)
                        put("Backup · restore · encryption", settingViewModel::openBackupRestoreDialog)
                        put("Local models · LiteRT · QNN", onNavigateToLocalModels)
                    },
                    change = settingViewModel::updateFeature
                )
            }
            item {
                SettingsCategory(title = "AI") {
                    SettingsDestination(
                        icon = Icons.Rounded.SmartToy,
                        title = "AI Platforms & Profiles",
                        onClick = onNavigateToAiPlatforms
                    )
                    SettingsDestination(
                        icon = Icons.Rounded.Storage,
                        title = stringResource(R.string.local_models),
                        onClick = onNavigateToLocalModels
                    )
                }
            }

            item {
                SettingsCategory(title = "Plugins/Tools") {
                    SettingsDestination(
                        icon = Icons.Rounded.Build,
                        title = "Plugins/Tools",
                        onClick = onNavigateToToolConnections
                    )
                    SettingsDestination(
                        icon = Icons.Rounded.Psychology,
                        title = "Model Delegation",
                        onClick = { showDelegation = true }
                    )
                    SettingsDestination(
                        icon = Icons.Rounded.AccountTree,
                        title = "Memory",
                        onClick = onNavigateToFactVault
                    )
                    if (githubWorkspaceEnabled) {
                        SettingsDestination(
                            icon = Icons.Rounded.Build,
                            title = "GitHub Workspace",
                            onClick = onNavigateToWorkspaces
                        )
                    }
                }
            }

            item {
                SettingsCategory(
                    title = "Experience"
                ) {
                    SettingsDestination(
                        icon = Icons.Rounded.Palette,
                        title = stringResource(R.string.theme_settings),
                        onClick = settingViewModel::openThemeDialog
                    )
                    SettingsDestination(
                        icon = Icons.Rounded.Tune,
                        title = "Advanced Settings",
                        onClick = onNavigateToAdvancedSettings
                    )
                }
            }

            item {
                SettingsCategory(
                    title = "Diagnostics & Data"
                ) {
                    SettingsDestination(
                        icon = Icons.Rounded.BugReport,
                        title = "Debug & Statistics",
                        onClick = onNavigateToDebugDiagnostics
                    )

                    SettingsDestination(
                        icon = Icons.Rounded.Backup,
                        title = stringResource(R.string.backup_and_restore),
                        onClick = settingViewModel::openBackupRestoreDialog
                    )
                }
            }

            item {
                SettingsCategory(
                    title = "About"
                ) {
                    SettingsDestination(
                        icon = Icons.Rounded.Info,
                        title = stringResource(R.string.about),
                        onClick = onNavigateToAboutPage
                    )
                }
            }
            item { Spacer(Modifier.height(48.dp)) }
        }
    }

    if (showDelegation) {
        LocalToolConfigurationDialog(section = "delegation", onDismiss = { showDelegation = false })
    }

    if (dialogState.isThemeDialogOpen) {
        ThemeSettingDialog(settingViewModel)
    }

    BackupRestoreHost(settingViewModel)
}

@Composable
private fun SettingsCategory(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title.split(Regex("\\s+")).joinToString(" ") { word -> word.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() } },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Column(Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun SettingsDestination(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Text(
            title.split(Regex("\\s+")).joinToString(" ") { word -> word.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() } },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun ThemeSettingDialog(settingViewModel: SettingViewModelV2) {
    ThemeSettingsScreen(onDismiss = settingViewModel::closeThemeDialog)
}
