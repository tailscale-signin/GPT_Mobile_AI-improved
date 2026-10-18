package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.backup.BackupStatus
import dev.chungjungsoo.gptmobile.data.backup.CompleteBackupGroup
import dev.chungjungsoo.gptmobile.data.backup.CompleteBackupSection
import dev.chungjungsoo.gptmobile.data.backup.RestoreProgress
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
        dev.chungjungsoo.gptmobile.presentation.common.AppGlassSurface(
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            if (state.isWorking && state.isRestoring) {
                RestoreWorkingContent(state.restoreProgress)
            } else {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(if (restoreOnly) "Restore" else stringResource(R.string.backup_and_restore), style = MaterialTheme.typography.headlineSmall)
                    if (!restoreOnly) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                if (state.selection.requiresEncryption && !state.passwordProtectionEnabled) {
                                    "Health data, credentials and memory are encrypted with a separate recovery key file."
                                } else {
                                    stringResource(if (state.passwordProtectionEnabled) R.string.backup_password_recovery_notice else R.string.backup_unencrypted_notice)
                                },
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
                    if (!state.isBusy && state.recentBackups.isNotEmpty()) {
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Recent backups", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                        if (state.selection.requiresEncryption && !state.passwordProtectionEnabled) {
                            Text(
                                "Health data, credentials and memory are still encrypted. Save the separate recovery key file to restore without a password.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
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
                        Text(state.workingMessage, style = MaterialTheme.typography.bodySmall)
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
internal fun RestoreWorkingContent(progress: RestoreProgress?) {
    val fraction = progress?.fraction
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp).testTag("restore_progress_panel"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    Icons.Rounded.Restore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Restoring backup", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    progress?.phase?.label ?: "Opening backup",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("restore_current_task").semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        }
        if (fraction == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("restore_loading_bar"))
        } else {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().testTag("restore_loading_bar")
            )
            Text(
                "${(fraction * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.End).testTag("restore_percentage")
            )
        }
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
            var expanded by rememberSaveable(group.name) { mutableStateOf(false) }
            val selected = group.sections.count { it in state.selection.sections }
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        TriStateCheckbox(
                            state = when (selected) {
                                0 -> ToggleableState.Off
                                group.sections.size -> ToggleableState.On
                                else -> ToggleableState.Indeterminate
                            },
                            enabled = !state.isBusy && !state.isWorking,
                            modifier = Modifier.testTag("backup_group_select_${group.name}"),
                            onClick = { change(group, selected != group.sections.size) }
                        )
                        Column(Modifier.weight(1f).clickable { expanded = !expanded }.padding(vertical = 8.dp)) {
                            Text(group.title, style = MaterialTheme.typography.titleSmall)
                            Text("$selected / ${group.sections.size} selected", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(group.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("backup_group_expand_${group.name}")) {
                            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, stringResource(if (expanded) R.string.backup_collapse_category else R.string.backup_expand_category))
                        }
                    }
                    AnimatedVisibility(visible = expanded) {
                        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                            group.sections.forEach { section ->
                                val description = backupSectionDescription(section)
                                Row(
                                    Modifier.fillMaxWidth().clickable(enabled = !state.isBusy && !state.isWorking) { onSectionChange(section, !state.selection.includes(section)) }.padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = state.selection.includes(section),
                                        enabled = !state.isBusy && !state.isWorking,
                                        onCheckedChange = { onSectionChange(section, it) },
                                        modifier = Modifier.testTag("backup_section_${section.name}")
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(description.first, style = MaterialTheme.typography.bodyMedium)
                                        Text(description.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Text(
            "Choices are saved for backup and restore. Expand a category to choose individual items. Dependent records are selected automatically: attachments and research evidence require chats; statistics also require profiles and run history.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/** Every row maps to an existing archive section; no decorative selection controls. */
internal fun backupSectionDescription(section: CompleteBackupSection): Pair<String, String> = when (section) {
    CompleteBackupSection.SETTINGS -> "Appearance & preferences" to "Theme, display, behavior and saved app preferences."
    CompleteBackupSection.CONVERSATIONS -> "Chats & favorites" to "Conversation folders, prompts, responses and favorite messages."
    CompleteBackupSection.PLATFORMS -> "AI profiles & provider settings" to "Models, prompts and profile configurations. API secrets are selected separately."
    CompleteBackupSection.TOOLS -> "Plugins & MCP connections" to "Tool configuration, enabled plugins and connection definitions."
    CompleteBackupSection.CREDENTIALS -> "API keys & connection secrets" to "Protected credentials; requires encryption."
    CompleteBackupSection.MEMORY -> "Saved memory" to "Local memory and learned facts; requires encryption."
    CompleteBackupSection.LOCAL_MODELS -> "Downloaded local model files" to "Installed model assets. Large files can make the backup substantially larger."
    CompleteBackupSection.ATTACHMENTS -> "Chat attachments" to "Images and files associated with saved conversations. Includes chats."
    CompleteBackupSection.AGENT_HISTORY -> "AI runs & tool activity" to "Execution records, tool calls and response history. Includes chats."
    CompleteBackupSection.STATISTICS -> "Usage & execution statistics" to "Recorded usage and tool statistics. Includes chats, AI profiles and run history."
    CompleteBackupSection.AMAZON_DATA -> "Shopping data" to "Saved Amazon data and product information."
    CompleteBackupSection.HEALTH_DATA -> "Imported health history" to "Locally imported Health Connect records; requires encryption."
    CompleteBackupSection.RESEARCH_ARCHIVE -> "Research archive" to "Saved studies and archive entries; requires encryption."
    CompleteBackupSection.CHAT_RESEARCH -> "Chat research evidence" to "Retained research sources linked to conversations. Includes chats; requires encryption."
    CompleteBackupSection.RESEARCH_APPRAISALS -> "Article appraisals" to "Saved evaluations of research articles; requires encryption."
}
