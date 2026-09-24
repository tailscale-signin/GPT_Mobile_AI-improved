package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.backup.BackupStatus
import dev.chungjungsoo.gptmobile.data.backup.CompleteBackupSection
import java.text.DateFormat
import java.util.Date

@Composable
fun CompleteBackupDialog(
    state: SettingViewModelV2.BackupUiState,
    backupStatus: BackupStatus,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onSectionChange: (CompleteBackupSection, Boolean) -> Unit = { _, _ -> },
    onPasswordProtectionChange: (Boolean) -> Unit = {},
    onPasswordChange: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState()
    var showContents by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.isWorking, state.message) {
        if (state.isWorking || state.message != null) {
            listState.animateScrollToItem(4)
        }
    }

    Dialog(
        onDismissRequest = { if (!state.isBusy) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !state.isBusy,
            dismissOnClickOutside = !state.isBusy,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier
                .safeDrawingPadding()
                .imePadding()
                .padding(12.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    stringResource(R.string.backup_and_restore),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "Choose exactly what is included. Backups are portable and unencrypted by default; password encryption is optional.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp)
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("backup_content"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text(
                            backupStatus.lastBackupEpochMs?.let {
                                stringResource(
                                    R.string.complete_backup_last,
                                    DateFormat.getDateTimeInstance().format(Date(it))
                                )
                            } ?: stringResource(R.string.complete_backup_none),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = onBackup,
                                enabled = state.canBackup,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("backup_all")
                            ) {
                                Text(stringResource(R.string.complete_backup_action))
                            }
                            OutlinedButton(
                                onClick = onRestore,
                                enabled = !state.isBusy,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("restore_all")
                            ) {
                                Text(stringResource(R.string.complete_restore_action))
                            }
                        }
                    }

                    item {
                        OutlinedButton(
                            onClick = { showContents = !showContents },
                            enabled = !state.isBusy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("backup_contents")
                        ) {
                            Text(
                                stringResource(
                                    if (showContents) R.string.complete_backup_hide_contents
                                    else R.string.complete_backup_show_contents
                                )
                            )
                        }

                        AnimatedVisibility(visible = showContents) {
                            Column(
                                modifier = Modifier.padding(top = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                BackupOptionRow(
                                    checked = CompleteBackupSection.SETTINGS in state.selection.sections,
                                    title = "Settings & preferences",
                                    subtitle = "Theme, Advanced Settings and app preferences",
                                    enabled = !state.isBusy,
                                    onCheckedChange = { onSectionChange(CompleteBackupSection.SETTINGS, it) }
                                )
                                BackupOptionRow(
                                    checked = CompleteBackupSection.CONVERSATIONS in state.selection.sections,
                                    title = "Conversations & favorites",
                                    subtitle = "Conversation titles, messages, favorites, drafts and per-chat model choices",
                                    enabled = !state.isBusy,
                                    onCheckedChange = { onSectionChange(CompleteBackupSection.CONVERSATIONS, it) }
                                )
                                BackupOptionRow(
                                    checked = CompleteBackupSection.PLATFORMS in state.selection.sections,
                                    title = "AI platforms & profiles",
                                    subtitle = "Provider connections and AI profile configuration",
                                    enabled = !state.isBusy,
                                    onCheckedChange = { onSectionChange(CompleteBackupSection.PLATFORMS, it) }
                                )
                                BackupOptionRow(
                                    checked = CompleteBackupSection.TOOLS in state.selection.sections,
                                    title = "Tool connections",
                                    subtitle = "MCP connections, installed tools and profile tool bindings",
                                    enabled = !state.isBusy,
                                    onCheckedChange = { onSectionChange(CompleteBackupSection.TOOLS, it) }
                                )
                                BackupOptionRow(
                                    checked = CompleteBackupSection.CREDENTIALS in state.selection.sections,
                                    title = "Credentials",
                                    subtitle = "Saved provider and tool secrets",
                                    enabled = !state.isBusy,
                                    onCheckedChange = { onSectionChange(CompleteBackupSection.CREDENTIALS, it) }
                                )
                                BackupOptionRow(
                                    checked = CompleteBackupSection.LOCAL_MODELS in state.selection.sections,
                                    title = "Local AI models",
                                    subtitle = "Downloaded model records and model files",
                                    enabled = !state.isBusy,
                                    onCheckedChange = { onSectionChange(CompleteBackupSection.LOCAL_MODELS, it) }
                                )
                                BackupOptionRow(
                                    checked = CompleteBackupSection.ATTACHMENTS in state.selection.sections,
                                    title = "Conversation attachments",
                                    subtitle = "Images and files attached to chats; conversations are included automatically",
                                    enabled = !state.isBusy,
                                    onCheckedChange = { onSectionChange(CompleteBackupSection.ATTACHMENTS, it) }
                                )
                                BackupOptionRow(
                                    checked = CompleteBackupSection.AGENT_HISTORY in state.selection.sections,
                                    title = "Agent & tool history",
                                    subtitle = "Agent runs, tool calls, results and diagnostics history; conversations are included automatically",
                                    enabled = !state.isBusy,
                                    onCheckedChange = { onSectionChange(CompleteBackupSection.AGENT_HISTORY, it) }
                                )
                            }
                        }
                    }

                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Password encryption",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            "Optional. Off by default. A password-encrypted backup can be restored on another installation.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = state.passwordProtectionEnabled,
                                        enabled = !state.isBusy,
                                        onCheckedChange = onPasswordProtectionChange
                                    )
                                }
                                AnimatedVisibility(state.passwordProtectionEnabled) {
                                    OutlinedTextField(
                                        value = state.backupPassword,
                                        onValueChange = onPasswordChange,
                                        label = { Text("Backup password") },
                                        supportingText = { Text("Minimum 8 characters") },
                                        visualTransformation = PasswordVisualTransformation(),
                                        singleLine = true,
                                        enabled = !state.isBusy,
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (state.isWorking) {
                        item {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(
                                stringResource(R.string.complete_backup_working),
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .semantics { liveRegion = LiveRegionMode.Polite }
                            )
                        }
                    }

                    state.message?.let { message ->
                        item {
                            Text(
                                message,
                                color = if (state.isError) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                            )
                        }
                    }

                    item {
                        Text(
                            stringResource(R.string.complete_backup_legacy_password_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                TextButton(
                    onClick = onDismiss,
                    enabled = !state.isBusy,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}

@Composable
private fun BackupOptionRow(
    checked: Boolean,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
