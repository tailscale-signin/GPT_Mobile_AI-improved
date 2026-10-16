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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    onNavigateToAppearance: () -> Unit = settingViewModel::openThemeDialog,
    onSearchDestination: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val platforms by settingViewModel.platformState.collectAsStateWithLifecycle()
    val providerConnections by settingViewModel.providerConnections.collectAsStateWithLifecycle()
    val dialogState by settingViewModel.dialogState.collectAsStateWithLifecycle()
    val debugMode by settingViewModel.debugMode.collectAsStateWithLifecycle()
    val featureSettings by settingViewModel.featureSettings.collectAsStateWithLifecycle()
    var showResearch by rememberSaveable { mutableStateOf(false) }
    var showExecution by rememberSaveable { mutableStateOf(false) }
    var showDelegation by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val searchEntries = SettingsSearchRegistry.entries.filter { (githubWorkspaceEnabled || it.destination != SettingsSearchDestination.WORKSPACE) && (it.key != "research" || featureSettings.isToolPluginEnabled(dev.chungjungsoo.gptmobile.data.model.ToolPluginId.DEEP_RESEARCH)) } +
        memoryControlSearchEntries + privacyControlSearchEntries +
        platforms.flatMap { profile -> profileControlSearchEntries(profile, providerConnections.any { it.uid == profile.providerConnectionUid }) + SettingsSearchEntry("profile.${profile.uid}", profile.name, "AI › Profiles › ${profile.name}", SettingsSearchDestination.PROFILES, profile.uid, "${profile.model} ${profile.compatibleType}") }

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
                    query = query,
                    onQueryChange = { query = it },
                    entries = searchEntries,
                    onSelect = { entry ->
                        if (entry.key == "research") {
                            showResearch = true
                        } else if (entry.key == "execution") {
                            showExecution = true
                        } else {
                            when (entry.destination) {
                                SettingsSearchDestination.BACKUP -> settingViewModel.openBackupRestoreDialog()
                                SettingsSearchDestination.DELEGATION -> showDelegation = true
                                else -> onSearchDestination(entry.destination.name, entry.focus)
                            }
                        }
                    }
                )
            }
            if (query.isBlank()) {
                item {
                    SettingsCategory(title = stringResource(R.string.settings_category_ai)) {
                        SettingsDestination(
                            icon = Icons.Rounded.SmartToy,
                            title = stringResource(R.string.settings_profiles),
                            onClick = onNavigateToAiPlatforms
                        )
                        SettingsDestination(
                            icon = Icons.Rounded.Storage,
                            title = stringResource(R.string.local_models),
                            onClick = onNavigateToLocalModels
                        )
                        SettingsDestination(icon = Icons.Rounded.Tune, title = "Execution & efficiency", onClick = { showExecution = true })
                    }
                }

                item {
                    SettingsCategory(title = stringResource(R.string.settings_category_tools)) {
                        SettingsDestination(
                            icon = Icons.Rounded.Build,
                            title = stringResource(R.string.settings_category_tools),
                            onClick = onNavigateToToolConnections
                        )
                        if (featureSettings.isToolPluginEnabled(dev.chungjungsoo.gptmobile.data.model.ToolPluginId.DEEP_RESEARCH)) SettingsDestination(icon = Icons.Rounded.Psychology, title = "Research", onClick = { showResearch = true })
                    }
                }
                item {
                    SettingsCategory(title = stringResource(R.string.settings_category_memory)) {
                        SettingsDestination(
                            icon = Icons.Rounded.Psychology,
                            title = stringResource(R.string.settings_delegation),
                            onClick = { showDelegation = true }
                        )
                        SettingsDestination(
                            icon = Icons.Rounded.AccountTree,
                            title = stringResource(R.string.settings_memory),
                            onClick = onNavigateToFactVault
                        )
                        if (githubWorkspaceEnabled) {
                            SettingsDestination(
                                icon = Icons.Rounded.Build,
                                title = stringResource(R.string.settings_workspace),
                                onClick = onNavigateToWorkspaces
                            )
                        }
                    }
                }

                item {
                    SettingsCategory(
                        title = stringResource(R.string.settings_category_experience)
                    ) {
                        SettingsDestination(
                            icon = Icons.Rounded.Palette,
                            title = stringResource(R.string.theme_settings),
                            onClick = onNavigateToAppearance
                        )
                        SettingsDestination(
                            icon = Icons.Rounded.Tune,
                            title = stringResource(R.string.settings_advanced),
                            onClick = onNavigateToAdvancedSettings
                        )
                    }
                }

                item {
                    SettingsCategory(
                        title = stringResource(R.string.settings_category_data)
                    ) {
                        SettingsDestination(
                            icon = Icons.Rounded.BugReport,
                            title = "Activity",
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
    }

    if (showResearch) ResearchFeatureScreen(onClose = { showResearch = false })
    if (showExecution) ExecutionEfficiencyScreen(onClose = { showExecution = false })

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
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Text(
            title.split(Regex("\\s+")).joinToString(" ") { word -> word.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() } },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ThemeSettingDialog(settingViewModel: SettingViewModelV2) {
    ThemeSettingsScreen(onDismiss = settingViewModel::closeThemeDialog)
}
