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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.backup.BackupStatus
import java.text.DateFormat
import java.util.Date

@Composable
fun CompleteBackupDialog(
    state: SettingViewModelV2.BackupUiState,
    backupStatus: BackupStatus,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
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
                    text = stringResource(R.string.complete_backup_passwordless_description),
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
                                modifier = Modifier.padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                backupContentLabels().forEach { label ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Checkbox(checked = true, onCheckedChange = null, enabled = false)
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
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
private fun backupContentLabels(): List<String> = listOf(
    stringResource(R.string.complete_backup_item_configuration),
    stringResource(R.string.complete_backup_item_conversations),
    stringResource(R.string.complete_backup_item_favorites),
    stringResource(R.string.complete_backup_item_platforms),
    stringResource(R.string.complete_backup_item_tools),
    stringResource(R.string.complete_backup_item_credentials),
    stringResource(R.string.complete_backup_item_attachments),
    stringResource(R.string.complete_backup_item_models),
    stringResource(R.string.complete_backup_item_agent_history)
)
