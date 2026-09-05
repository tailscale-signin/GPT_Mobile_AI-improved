package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.DynamicTheme
import dev.chungjungsoo.gptmobile.data.model.ThemeMode
import dev.chungjungsoo.gptmobile.presentation.common.LocalDynamicTheme
import dev.chungjungsoo.gptmobile.presentation.common.LocalThemeMode
import dev.chungjungsoo.gptmobile.presentation.common.LocalThemeViewModel
import dev.chungjungsoo.gptmobile.presentation.common.RadioItem
import dev.chungjungsoo.gptmobile.presentation.common.SettingItem
import dev.chungjungsoo.gptmobile.util.getClientTypeDisplayName
import dev.chungjungsoo.gptmobile.util.getDynamicThemeTitle
import dev.chungjungsoo.gptmobile.util.getThemeModeTitle
import dev.chungjungsoo.gptmobile.util.pinnedExitUntilCollapsedScrollBehavior
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingScreen(
    modifier: Modifier = Modifier,
    settingViewModel: SettingViewModelV2 = hiltViewModel(),
    onNavigationClick: () -> Unit,
    onNavigateToAddPlatform: () -> Unit,
    onNavigateToPlatformSetting: (String) -> Unit,
    onNavigateToLocalModels: () -> Unit,
    onNavigateToToolConnections: () -> Unit,
    onNavigateToAboutPage: () -> Unit
) {
    val scrollState = rememberScrollState()
    val scrollBehavior = pinnedExitUntilCollapsedScrollBehavior(
        canScroll = { scrollState.canScrollForward || scrollState.canScrollBackward }
    )
    val platformState by settingViewModel.platformState.collectAsStateWithLifecycle()
    val dialogState by settingViewModel.dialogState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        settingViewModel.uiEvent.collectLatest { event ->
            when (event) {
                is SettingViewModelV2.UiEvent.RestoreSuccess -> {
                    Toast.makeText(
                        context,
                        context.getString(R.string.restore_configuration_success, event.count),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                settingViewModel.fetchPlatforms()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        modifier = modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SettingTopBar(
                scrollBehavior = scrollBehavior,
                navigationOnClick = onNavigationClick
            )
        }
    ) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .verticalScroll(scrollState)
        ) {
            ThemeSetting { settingViewModel.openThemeDialog() }

            BackupRestoreSetting { settingViewModel.openBackupRestoreDialog() }

            // Add Platform button
            SettingItem(
                title = stringResource(R.string.add_platform),
                description = stringResource(R.string.add_platform_description),
                onItemClick = onNavigateToAddPlatform,
                showTrailingIcon = false,
                showLeadingIcon = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            )
            SettingItem(
                title = stringResource(R.string.local_models),
                description = stringResource(R.string.local_models_description),
                onItemClick = onNavigateToLocalModels,
                showTrailingIcon = true,
                showLeadingIcon = false
            )
            SettingItem(
                title = stringResource(R.string.web_tools),
                description = stringResource(R.string.web_tools_description),
                onItemClick = onNavigateToToolConnections,
                showTrailingIcon = true,
                showLeadingIcon = false
            )

            // Dynamic platform list
            platformState.forEach { platform ->
                PlatformItem(
                    platform = platform,
                    onItemClick = { onNavigateToPlatformSetting(platform.uid) },
                    onDeleteClick = { settingViewModel.openDeleteDialog(platform.id) }
                )
            }

            AboutPageItem(onItemClick = onNavigateToAboutPage)

            if (dialogState.isThemeDialogOpen) {
                ThemeSettingDialog(settingViewModel)
            }

            if (dialogState.isDeleteDialogOpen) {
                DeletePlatformDialog(settingViewModel)
            }

            if (dialogState.isBackupRestoreDialogOpen) {
                BackupRestoreOptionsDialog(settingViewModel)
            }

            if (dialogState.isExportDialogOpen) {
                ExportConfigurationDialog(
                    exportedJson = dialogState.exportedConfigJson,
                    onDismiss = settingViewModel::closeExportDialog
                )
            }

            if (dialogState.isRestoreDialogOpen) {
                RestoreConfigurationDialog(
                    inputJson = dialogState.restoreJsonInput,
                    errorMessage = dialogState.restoreErrorMessage,
                    onInputChanged = settingViewModel::onRestoreJsonInputChanged,
                    onConfirm = settingViewModel::restoreConfiguration,
                    onDismiss = settingViewModel::closeRestoreDialog
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingTopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    navigationOnClick: () -> Unit
) {
    LargeTopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground
        ),
        title = {
            Text(
                modifier = Modifier.padding(4.dp),
                text = stringResource(R.string.settings),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            IconButton(
                modifier = Modifier.padding(4.dp),
                onClick = navigationOnClick
            ) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back))
            }
        },
        scrollBehavior = scrollBehavior
    )
}

@Composable
fun ThemeSetting(
    onItemClick: () -> Unit
) {
    SettingItem(
        title = stringResource(R.string.theme_settings),
        description = stringResource(R.string.theme_description),
        onItemClick = onItemClick,
        showTrailingIcon = false,
        showLeadingIcon = false
    )
}

@Composable
fun BackupRestoreSetting(
    onItemClick: () -> Unit
) {
    SettingItem(
        title = stringResource(R.string.backup_and_restore),
        description = stringResource(R.string.backup_and_restore_description),
        onItemClick = onItemClick,
        showTrailingIcon = false,
        showLeadingIcon = false
    )
}

@Composable
fun AboutPageItem(
    onItemClick: () -> Unit
) {
    SettingItem(
        title = stringResource(R.string.about),
        description = stringResource(R.string.about_description),
        onItemClick = onItemClick,
        showTrailingIcon = true,
        showLeadingIcon = false
    )
}

@Composable
fun ThemeSettingDialog(
    settingViewModel: SettingViewModelV2 = hiltViewModel()
) {
    val themeViewModel = LocalThemeViewModel.current
    AlertDialog(
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(text = stringResource(R.string.dynamic_theme), style = MaterialTheme.typography.titleMedium)
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                )
                DynamicTheme.entries.forEach { theme ->
                    RadioItem(
                        title = getDynamicThemeTitle(theme),
                        description = null,
                        value = theme.name,
                        selected = LocalDynamicTheme.current == theme
                    ) {
                        themeViewModel.updateDynamicTheme(theme)
                    }
                }
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                )
                Text(text = stringResource(R.string.dark_mode), style = MaterialTheme.typography.titleMedium)
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                )
                ThemeMode.entries.forEach { theme ->
                    RadioItem(
                        title = getThemeModeTitle(theme),
                        description = null,
                        value = theme.name,
                        selected = LocalThemeMode.current == theme
                    ) {
                        themeViewModel.updateThemeMode(theme)
                    }
                }
            }
        },
        onDismissRequest = settingViewModel::closeThemeDialog,
        confirmButton = {
            TextButton(
                onClick = settingViewModel::closeThemeDialog
            ) {
                Text(stringResource(R.string.confirm))
            }
        }
    )
}

@Composable
fun BackupRestoreOptionsDialog(
    settingViewModel: SettingViewModelV2 = hiltViewModel()
) {
    AlertDialog(
        title = {
            Text(stringResource(R.string.backup_and_restore))
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = settingViewModel::openExportDialog
                ) {
                    Text(stringResource(R.string.export_configuration))
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = settingViewModel::openRestoreDialog
                ) {
                    Text(stringResource(R.string.restore_configuration))
                }
            }
        },
        onDismissRequest = settingViewModel::closeBackupRestoreDialog,
        confirmButton = {
            TextButton(onClick = settingViewModel::closeBackupRestoreDialog) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun ExportConfigurationDialog(
    exportedJson: String,
    onDismiss: () -> Unit
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    AlertDialog(
        title = {
            Text(stringResource(R.string.backup_configuration_dialog_title))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.backup_configuration_dialog_description),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = exportedJson,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    textStyle = MaterialTheme.typography.bodySmall
                )
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            Row {
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(exportedJson))
                        Toast.makeText(
                            context,
                            context.getString(R.string.copied_to_clipboard),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                ) {
                    Text(stringResource(R.string.copy_to_clipboard))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
fun RestoreConfigurationDialog(
    inputJson: String,
    errorMessage: String?,
    onInputChanged: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        title = {
            Text(stringResource(R.string.restore_configuration_dialog_title))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.restore_configuration_dialog_description),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = inputJson,
                    onValueChange = onInputChanged,
                    placeholder = { Text(stringResource(R.string.paste_configuration_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                    isError = errorMessage != null
                )
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = inputJson.isNotBlank()
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun PlatformItem(
    platform: PlatformV2,
    onItemClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    SettingItem(
        title = platform.name,
        description = "${getClientTypeDisplayName(platform.compatibleType)} • ${if (platform.enabled) stringResource(R.string.enabled) else stringResource(R.string.disabled)}",
        onItemClick = onItemClick,
        showTrailingIcon = true,
        showLeadingIcon = false
    )
}

@Composable
fun DeletePlatformDialog(
    settingViewModel: SettingViewModelV2 = hiltViewModel()
) {
    AlertDialog(
        title = {
            Text(stringResource(R.string.delete_platform))
        },
        text = {
            Text(stringResource(R.string.delete_platform_confirmation))
        },
        onDismissRequest = settingViewModel::closeDeleteDialog,
        confirmButton = {
            TextButton(
                onClick = settingViewModel::confirmDelete
            ) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            TextButton(
                onClick = settingViewModel::closeDeleteDialog
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
