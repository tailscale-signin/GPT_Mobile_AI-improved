package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog

/** Shared picker and restore workflow for Settings and the first-run tour. */
@Composable
fun BackupRestoreHost(settingViewModel: SettingViewModelV2, onRestored: () -> Unit = {}, restoreOnly: Boolean = false) {
    val dialogState by settingViewModel.dialogState.collectAsState()
    val backupStatus by settingViewModel.backupStatus.collectAsState()
    val backupUi by settingViewModel.backupUi.collectAsState()
    val context = LocalContext.current

    val backupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
        onResult = settingViewModel::backupDestinationSelected
    )
    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = settingViewModel::restoreSourceSelected
    )

    val recoveryKeyBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
        settingViewModel::backupRecoveryKeySelected
    )
    val recoveryKeyRestoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
        settingViewModel::restoreRecoveryKeySelected
    )
    LaunchedEffect(backupUi.backupUri) {
        if (backupUi.backupUri != null) {
            try {
                recoveryKeyBackupLauncher.launch("gpt_mobile_${System.currentTimeMillis()}.gptkey")
            } catch (_: android.content.ActivityNotFoundException) {
                settingViewModel.cancelBackupPicker()
                Toast.makeText(context, R.string.backup_picker_unavailable, Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(settingViewModel) {
        settingViewModel.uiEvent.collect { event ->
            when (event) {
                is SettingViewModelV2.UiEvent.ShowToast ->
                    Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    val restoredCallback by androidx.compose.runtime.rememberUpdatedState(onRestored)
    LaunchedEffect(settingViewModel) {
        settingViewModel.restoreCompleted.collect {
            settingViewModel.closeBackupRestoreDialog()
            restoredCallback()
        }
    }
    if (dialogState.isBackupRestoreDialogOpen) {
        CompleteBackupDialog(
            state = backupUi,
            restoreOnly = restoreOnly,
            backupStatus = backupStatus,
            onDismiss = settingViewModel::closeBackupRestoreDialog,
            onBackup = {
                if (settingViewModel.prepareBackupPicker(restoring = false)) {
                    try {
                        backupLauncher.launch("gpt_mobile_${System.currentTimeMillis()}.gptbackup")
                    } catch (_: android.content.ActivityNotFoundException) {
                        settingViewModel.cancelBackupPicker()
                        Toast.makeText(context, R.string.backup_picker_unavailable, Toast.LENGTH_LONG).show()
                    }
                }
            },
            onRestore = {
                if (settingViewModel.prepareBackupPicker(restoring = true)) {
                    try {
                        restoreLauncher.launch(arrayOf("*/*"))
                    } catch (_: android.content.ActivityNotFoundException) {
                        settingViewModel.cancelBackupPicker()
                        Toast.makeText(context, R.string.backup_picker_unavailable, Toast.LENGTH_LONG).show()
                    }
                }
            },
            onRecentRestore = settingViewModel::restoreRecentBackup,
            onSectionChange = settingViewModel::updateBackupSection,
            onGroupChange = settingViewModel::updateBackupGroup,
            onPasswordProtectionChange = settingViewModel::updateBackupPasswordProtection,
            onPasswordChange = settingViewModel::updateBackupPassword
        )
    }

    if (backupUi.restoreUri != null && !backupUi.isWorking) {
        AlertDialog(
            title = { Text(stringResource(R.string.complete_restore_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.complete_restore_confirmation))
                    BackupSelectionContent(backupUi, onGroupChange = settingViewModel::updateBackupGroup, onSectionChange = settingViewModel::updateBackupSection)
                    if (backupUi.requiresRecoveryKey) {
                        Text("Select the separate recovery key saved with this backup. No password is required.")
                        Button(onClick = {
                            try {
                                recoveryKeyRestoreLauncher.launch(arrayOf("*/*"))
                            } catch (_: android.content.ActivityNotFoundException) {
                                Toast.makeText(context, R.string.backup_picker_unavailable, Toast.LENGTH_LONG).show()
                            }
                        }) {
                            Text(if (backupUi.recoveryKeyUri == null) "Choose recovery key" else "Recovery key selected")
                        }
                    }
                    if (backupUi.requiresLegacyPassword) {
                        Text(
                            text = stringResource(R.string.complete_backup_legacy_password_required),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = backupUi.legacyPassword,
                            onValueChange = settingViewModel::updateLegacyBackupPassword,
                            label = { Text(stringResource(R.string.complete_backup_legacy_password)) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            onDismissRequest = settingViewModel::cancelBackupPicker,
            confirmButton = {
                Button(
                    enabled = backupUi.selection.sections.isNotEmpty() &&
                        (!backupUi.requiresLegacyPassword || backupUi.legacyPassword.isNotBlank()) &&
                        (!backupUi.requiresRecoveryKey || backupUi.recoveryKeyUri != null),
                    onClick = settingViewModel::confirmRestore
                ) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = settingViewModel::cancelBackupPicker) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
