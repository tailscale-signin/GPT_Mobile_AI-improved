package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.backup.BackupStatus
import dev.chungjungsoo.gptmobile.data.backup.CompleteBackupGroup
import dev.chungjungsoo.gptmobile.data.backup.CompleteBackupSection
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog
import dev.chungjungsoo.gptmobile.presentation.common.FadingDialog as Dialog
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import java.text.DateFormat
import java.util.Date

private enum class BackupAction { BACKUP, RESTORE }

@Composable
fun CompleteBackupDialog(
    state: SettingViewModelV2.BackupUiState,
    backupStatus: BackupStatus,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onRecentRestore: (String) -> Unit = {},
    onSectionChange: (CompleteBackupSection, Boolean) -> Unit = { _, _ -> },
    onPasswordProtectionChange: (Boolean) -> Unit = {},
    onPasswordChange: (String) -> Unit = {},
    onDismiss: () -> Unit,
    restoreOnly: Boolean = false,
    onGroupChange: ((CompleteBackupGroup?, Boolean) -> Unit)? = null
) {
    var pendingAction by rememberSaveable { mutableStateOf<BackupAction?>(null) }

    Dialog(onDismissRequest = { if (!state.isBusy) onDismiss() }) {
        Surface(
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(if (restoreOnly) "Restore" else stringResource(R.string.backup_and_restore), style = MaterialTheme.typography.headlineSmall)
                if (!restoreOnly) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            if (state.passwordProtectionEnabled) "Your password is saved securely in the app and included inside the encrypted backup. Use it to restore after reinstall or on another device." else "Encryption is off. The backup includes readable app data and your saved password. Enable encryption to protect it.",
                            modifier = Modifier.padding(start = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        backupStatus.lastBackupEpochMs?.let {
                            stringResource(R.string.complete_backup_last, DateFormat.getDateTimeInstance().format(Date(it)))
                        } ?: stringResource(R.string.complete_backup_none),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (restoreOnly) Text("Choose your backup to restore your conversations, profiles and settings.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!state.isBusy && state.recentBackups.isNotEmpty()) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Recent backups", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "The three most recent backups remembered from the last backup folder.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            state.recentBackups.take(3).forEach { recent ->
                                TextButton(
                                    onClick = { onRecentRestore(recent.uri) },
                                    enabled = !state.isBusy,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Rounded.Restore, null)
                                    Column(
                                        Modifier.weight(1f).padding(start = 10.dp),
                                        horizontalAlignment = Alignment.Start
                                    ) {
                                        Text(recent.displayName, maxLines = 1)
                                        Text(
                                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                                .format(Date(recent.savedAtEpochMs)),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (!state.isBusy && !state.isWorking) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (!restoreOnly) {
                                Button(
                                    onClick = { pendingAction = BackupAction.BACKUP },
                                    enabled = state.canBackup,
                                    modifier = Modifier.fillMaxWidth().testTag("backup_all")
                                ) {
                                    Icon(Icons.Rounded.Backup, null)
                                    Text("Backup", Modifier.padding(start = 8.dp))
                                }
                            }
                            OutlinedButton(
                                onClick = onRestore,
                                enabled = !state.isBusy,
                                modifier = Modifier.fillMaxWidth().testTag("restore_all")
                            ) {
                                Icon(Icons.Rounded.Restore, null)
                                Text("Restore", Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }

                if (!restoreOnly) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary)
                        Text("Encrypt backup", Modifier.weight(1f).padding(start = 10.dp))
                        Switch(
                            checked = state.passwordProtectionEnabled,
                            onCheckedChange = onPasswordProtectionChange,
                            enabled = !state.isBusy,
                            modifier = Modifier.testTag("backup_encrypt")
                        )
                    }
                    if (state.passwordProtectionEnabled) {
                        OutlinedTextField(
                            value = state.backupPassword,
                            onValueChange = onPasswordChange,
                            label = { Text("Password") },
                            supportingText = { Text("Saved automatically. Use at least 8 characters.") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            enabled = !state.isBusy,
                            modifier = Modifier.fillMaxWidth().testTag("backup_password")
                        )
                    }
                }

                if (state.isWorking) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.complete_backup_working), style = MaterialTheme.typography.bodySmall)
                }
                state.message?.let {
                    Text(
                        it,
                        color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                TextButton(onClick = onDismiss, enabled = !state.isBusy, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }

    pendingAction?.let { action ->
        BackupSelectionDialog(
            action = action,
            state = state,
            onSectionChange = onSectionChange,
            onGroupChange = onGroupChange,
            onCancel = { pendingAction = null },
            onContinue = {
                pendingAction = null
                if (action == BackupAction.BACKUP) onBackup() else onRestore()
            }
        )
    }
}

@Composable
private fun BackupSelectionDialog(
    action: BackupAction,
    state: SettingViewModelV2.BackupUiState,
    onSectionChange: (CompleteBackupSection, Boolean) -> Unit,
    onGroupChange: ((CompleteBackupGroup?, Boolean) -> Unit)?,
    onCancel: () -> Unit,
    onContinue: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = {
            Icon(
                if (action == BackupAction.BACKUP) Icons.Rounded.Backup else Icons.Rounded.Restore,
                null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(if (action == BackupAction.BACKUP) "Backup contents" else "Restore contents") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                BackupSelectionContent(state, onGroupChange, onSectionChange)
            }
        },
        confirmButton = {
            Button(onClick = onContinue, enabled = state.selection.sections.isNotEmpty()) {
                Text(if (action == BackupAction.BACKUP) "Backup" else "Restore")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
internal fun BackupSelectionContent(
    state: SettingViewModelV2.BackupUiState,
    onGroupChange: ((CompleteBackupGroup?, Boolean) -> Unit)? = null,
    onSectionChange: (CompleteBackupSection, Boolean) -> Unit
) {
    fun change(group: CompleteBackupGroup?, checked: Boolean) {
        if (onGroupChange != null) {
            onGroupChange(group, checked)
        } else {
            (group?.sections ?: CompleteBackupSection.entries.toSet()).forEach { onSectionChange(it, checked) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = state.selection.sections.containsAll(CompleteBackupSection.entries),
                enabled = !state.isBusy,
                onCheckedChange = { change(null, it) }
            )
            Text("Select all", fontWeight = FontWeight.SemiBold)
        }
        CompleteBackupGroup.entries.forEach { group ->
            val selected = group.sections.count { it in state.selection.sections }
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                TriStateCheckbox(
                    state = when (selected) {
                        0 -> ToggleableState.Off
                        group.sections.size -> ToggleableState.On
                        else -> ToggleableState.Indeterminate
                    },
                    enabled = !state.isBusy,
                    onClick = { change(group, selected != group.sections.size) }
                )
                Column(Modifier.weight(1f)) {
                    Text(group.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(group.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(
            "Choices are saved. A dash preserves a partial selection from an earlier backup. Statistics include the profile records they reference.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
