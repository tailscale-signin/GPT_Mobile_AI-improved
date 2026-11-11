package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReport
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val ISSUE_TRACKER = "https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues"

@Composable
internal fun IssueReportHost(
    viewModel: IssueReportViewModel,
    showPrompts: Boolean,
    content: @Composable (openReport: () -> Unit) -> Unit
) {
    val manual by viewModel.manual.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val incident by viewModel.currentIncident.collectAsStateWithLifecycle(initialValue = null)
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources by rememberUpdatedState(LocalResources.current)
    val uriHandler = LocalUriHandler.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                viewModel.queued.collect { snackbar.showSnackbar(resources.getString(R.string.issue_report_queued)) }
            }
            viewModel.receipts.map { it.firstOrNull() }.distinctUntilChanged().filterNotNull().collect { receipt ->
                val number = receipt.number
                if (number != null) {
                    val action = snackbar.showSnackbar(
                        message = resources.getString(R.string.issue_report_posted, number),
                        actionLabel = resources.getString(R.string.issue_report_view),
                        duration = SnackbarDuration.Long
                    )
                    if (action == SnackbarResult.ActionPerformed) {
                        runCatching { uriHandler.openUri("$ISSUE_TRACKER/$number") }
                    }
                } else {
                    snackbar.showSnackbar(resources.getString(R.string.issue_report_send_failed))
                }
                viewModel.acknowledge(receipt)
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        content(viewModel::openManual)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
    if (manual) {
        ManualIssueDialog(
            saving = saving,
            error = error,
            onDismiss = viewModel::closeManual,
            onSubmit = viewModel::submitManual
        )
    } else if (showPrompts) {
        incident?.let { report ->
            AlertDialog(
                onDismissRequest = { viewModel.dismissIncident(report) },
                title = { Text(stringResource(R.string.issue_report_debug_question)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.issue_report_debug_public))
                        SubmissionError(error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissIncident(report) }, enabled = !saving) {
                        Text(stringResource(R.string.issue_report_no))
                    }
                },
                confirmButton = {
                    Button(onClick = { viewModel.submitIncident(report) }, enabled = !saving) {
                        Text(stringResource(R.string.issue_report_yes))
                    }
                }
            )
        }
    }
}

@Composable
private fun ManualIssueDialog(
    saving: Boolean,
    error: IssueReportViewModel.Notice?,
    onDismiss: () -> Unit,
    onSubmit: (String, String, Boolean) -> Unit
) {
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var includeDiagnostics by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.issue_report_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(IssueReport.TITLE_LIMIT) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.issue_report_short_title)) },
                    singleLine = true,
                    enabled = !saving
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(IssueReport.DESCRIPTION_LIMIT) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.issue_report_description)) },
                    minLines = 2,
                    maxLines = 5,
                    enabled = !saving
                )
                Row(
                    Modifier.fillMaxWidth().toggleable(includeDiagnostics, enabled = !saving, role = Role.Checkbox) { includeDiagnostics = it },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(checked = includeDiagnostics, onCheckedChange = null, enabled = !saving)
                    Text(stringResource(R.string.issue_report_include_diagnostics))
                }
                Text(
                    stringResource(R.string.issue_report_public),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                SubmissionError(error)
                if (error == IssueReportViewModel.Notice.Unavailable) {
                    TextButton(onClick = { runCatching { uriHandler.openUri(ISSUE_TRACKER) } }) {
                        Text(stringResource(R.string.issue_report_view))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.issue_report_cancel)) }
        },
        confirmButton = {
            Button(onClick = { onSubmit(title, description, includeDiagnostics) }, enabled = !saving && title.isNotBlank() && description.isNotBlank()) {
                Text(stringResource(R.string.issue_report_submit))
            }
        }
    )
}

@Composable
private fun SubmissionError(error: IssueReportViewModel.Notice?) {
    val message = when (error) {
        IssueReportViewModel.Notice.Unavailable -> R.string.issue_report_unavailable
        IssueReportViewModel.Notice.SaveFailed -> R.string.issue_report_save_failed
        else -> return
    }
    Text(stringResource(message), color = MaterialTheme.colorScheme.error)
}
