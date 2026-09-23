package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    onPasswordChange: (String) -> Unit,
    onConfirmationChange: (String) -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.isWorking, state.message) {
        if (state.isWorking || state.message != null) listState.animateScrollToItem(7)
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
            modifier = Modifier.safeDrawingPadding().imePadding().padding(12.dp)
                .widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight(0.95f),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.backup_and_restore), style = MaterialTheme.typography.headlineSmall)
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("backup_content"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item { Text(stringResource(R.string.complete_backup_description)) }
                    item {
                        Text(
                            backupStatus.lastBackupEpochMs?.let {
                                stringResource(R.string.complete_backup_last, DateFormat.getDateTimeInstance().format(Date(it)))
                            } ?: stringResource(R.string.complete_backup_none),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    item { Text(stringResource(R.string.complete_backup_password_help), style = MaterialTheme.typography.bodySmall) }
                    item {
                        OutlinedTextField(
                            value = state.password,
                            onValueChange = onPasswordChange,
                            enabled = !state.isBusy,
                            label = { Text(stringResource(R.string.complete_backup_password)) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("backup_password")
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = state.confirmation,
                            onValueChange = onConfirmationChange,
                            enabled = !state.isBusy,
                            label = { Text(stringResource(R.string.complete_backup_confirm_password)) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            isError = state.confirmation.isNotEmpty() && state.password != state.confirmation,
                            modifier = Modifier.fillMaxWidth().testTag("backup_confirmation")
                        )
                        if (state.confirmation.isNotEmpty() && state.password != state.confirmation) {
                            Text(stringResource(R.string.complete_backup_password_mismatch), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    item {
                        Button(onClick = onBackup, enabled = state.canBackup, modifier = Modifier.fillMaxWidth().testTag("backup_all")) {
                            Text(stringResource(R.string.complete_backup_action))
                        }
                    }
                    item {
                        OutlinedButton(onClick = onRestore, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth().testTag("restore_all")) {
                            Text(stringResource(R.string.complete_restore_action))
                        }
                    }
                    if (state.isWorking) {
                        item {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(stringResource(R.string.complete_backup_working), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                    }
                    state.message?.let { message ->
                        item {
                            Text(
                                message,
                                color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                            )
                        }
                    }
                    item { Text(stringResource(R.string.complete_backup_legacy), style = MaterialTheme.typography.bodySmall) }
                }
                TextButton(onClick = onDismiss, enabled = !state.isBusy, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}
