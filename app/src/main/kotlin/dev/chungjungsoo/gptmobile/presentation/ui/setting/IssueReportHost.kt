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
import androidx.compose.material3.LinearProgressIndicator
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
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportOutbox
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.jsonObject

private const val ISSUE_TRACKER = "https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues"

@Composable
internal fun IssueReportHost(viewModel: IssueReportViewModel, showPrompts: Boolean, content: @Composable (openReport: () -> Unit) -> Unit) {
    val manual by viewModel.manual.collectAsStateWithLifecycle()
    val manualPreview by viewModel.manualPreview.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val incident by viewModel.currentIncident.collectAsStateWithLifecycle(initialValue = null)
    val error by viewModel.error.collectAsStateWithLifecycle()
    val reports by viewModel.reports.collectAsStateWithLifecycle()
    val submission by viewModel.submission.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources by rememberUpdatedState(LocalResources.current)
    val uriHandler = LocalUriHandler.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.receipts.map { it.firstOrNull() }.distinctUntilChanged().filterNotNull().collect { receipt ->
                val number = receipt.issueNumber
                if (receipt.state == IssueReportOutbox.State.SENT && number != null) {
                    val action = snackbar.showSnackbar(resources.getString(R.string.issue_report_posted, number), resources.getString(R.string.issue_report_view), duration = SnackbarDuration.Long)
                    if (action == SnackbarResult.ActionPerformed) runCatching { uriHandler.openUri("$ISSUE_TRACKER/$number") }
                } else if (receipt.retryable && viewModel.configured) {
                    offerIssueReportRetry(
                        reportId = receipt.reportId,
                        prompt = { retryUnavailable ->
                            snackbar.showSnackbar(
                                resources.getString(if (retryUnavailable) R.string.issue_report_save_failed else reportFailureText(receipt)),
                                resources.getString(R.string.retry),
                                withDismissAction = true,
                                duration = SnackbarDuration.Indefinite
                            ) == SnackbarResult.ActionPerformed
                        },
                        retry = viewModel::retry
                    )
                } else {
                    snackbar.showSnackbar(resources.getString(reportFailureText(receipt)), duration = SnackbarDuration.Long)
                }
                viewModel.acknowledge(receipt)
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        content(viewModel::openManual)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
    when {
        manual -> ManualIssueDialog(saving, error, manualPreview, reports, viewModel::closeManual, viewModel::submitManual, viewModel::previewManualDiagnostics, viewModel::selectReport)
        submission != null -> submission?.let { report ->
            IssueSubmissionDialog(report, saving, error, viewModel.configured, viewModel::closeSubmission, { viewModel.retrySubmission(report.reportId) }, { viewModel.removeFailed(report.reportId) }) {
                report.issueNumber?.let { number -> runCatching { uriHandler.openUri("$ISSUE_TRACKER/$number") } }
            }
        }
        showPrompts -> incident?.let { report ->
            AlertDialog(
                onDismissRequest = { viewModel.dismissIncident(report) },
                title = { Text(stringResource(R.string.issue_report_debug_question)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.issue_report_debug_public))
                        ReportPreview(report)
                        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                        SubmissionError(error)
                    }
                },
                dismissButton = { TextButton(onClick = { viewModel.dismissIncident(report) }, enabled = !saving) { Text(stringResource(R.string.issue_report_no)) } },
                confirmButton = { Button(onClick = { viewModel.submitIncident(report) }, enabled = !saving) { Text(stringResource(if (saving) R.string.issue_report_saving else R.string.issue_report_submit)) } }
            )
        }
    }
}

@Composable
private fun ManualIssueDialog(
    saving: Boolean,
    error: IssueReportViewModel.Notice?,
    preview: IssueReport?,
    reports: List<IssueReportOutbox.Entry>,
    onDismiss: () -> Unit,
    onSubmit: (String, String, Boolean) -> Unit,
    onDiagnostics: (Boolean) -> Unit,
    onSelectReport: (String) -> Unit
) {
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var includeDiagnostics by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.issue_report_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it.take(IssueReport.TITLE_LIMIT) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.issue_report_short_title)) }, singleLine = true, enabled = !saving)
                OutlinedTextField(description, { description = it.take(IssueReport.DESCRIPTION_LIMIT) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.issue_report_description)) }, minLines = 2, maxLines = 5, enabled = !saving)
                Row(
                    Modifier.fillMaxWidth().toggleable(includeDiagnostics, enabled = !saving, role = Role.Checkbox) {
                        includeDiagnostics = it
                        onDiagnostics(it)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(checked = includeDiagnostics, onCheckedChange = null, enabled = !saving)
                    Text(stringResource(R.string.issue_report_include_diagnostics))
                }
                Text(stringResource(R.string.issue_report_public), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                preview?.let { ReportPreview(it) }
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                SubmissionError(error)
                if (reports.isNotEmpty()) {
                    Text(stringResource(R.string.issue_report_saved_reports), style = MaterialTheme.typography.titleSmall)
                    reports.forEach { report ->
                        TextButton(onClick = { onSelectReport(report.reportId) }, enabled = !saving) {
                            Text("${report.title?.takeIf(String::isNotBlank) ?: report.reportErrorCode ?: stringResource(R.string.issue_report_title)} · ${stringResource(reportStateText(report.state))}")
                        }
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.issue_report_cancel)) } },
        confirmButton = { Button(onClick = { onSubmit(title, description, includeDiagnostics) }, enabled = !saving && title.isNotBlank() && description.isNotBlank()) { Text(stringResource(if (saving) R.string.issue_report_saving else R.string.issue_report_submit)) } }
    )
}

@Composable
private fun IssueSubmissionDialog(
    report: IssueReportOutbox.Entry,
    saving: Boolean,
    error: IssueReportViewModel.Notice?,
    configured: Boolean,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onView: () -> Unit
) {
    val approved = remember(report.payload) { runCatching { IssueReport.json.decodeFromString(IssueReport.serializer(), report.payload) }.getOrNull() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(reportStateText(report.state))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (report.state) {
                    IssueReportOutbox.State.QUEUED -> {
                        Text(stringResource(if (report.failureCode == null) R.string.issue_report_queued else R.string.issue_report_retry_queued))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    IssueReportOutbox.State.SENDING -> {
                        Text(stringResource(R.string.issue_report_sending))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    IssueReportOutbox.State.SENT -> Text(stringResource(R.string.issue_report_posted, report.issueNumber ?: 0))
                    IssueReportOutbox.State.FAILED -> Text(stringResource(reportFailureText(report)), color = MaterialTheme.colorScheme.error)
                }
                report.failureCode?.let { Text(stringResource(R.string.issue_report_status_code, it), style = MaterialTheme.typography.bodySmall) }
                approved?.let { ReportPreview(it) }
                SubmissionError(error)
                if (report.state == IssueReportOutbox.State.FAILED) {
                    Text(stringResource(R.string.issue_report_remove_explanation), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onRemove, enabled = !saving) { Text(stringResource(R.string.issue_report_remove_saved)) }
                }
            }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !saving) { Text(stringResource(R.string.close)) } },
        confirmButton = {
            when {
                report.state == IssueReportOutbox.State.SENT -> TextButton(onClick = onView) { Text(stringResource(R.string.issue_report_view)) }
                report.state == IssueReportOutbox.State.FAILED && report.retryable && configured -> Button(onClick = onRetry, enabled = !saving) { Text(stringResource(R.string.retry)) }
            }
        }
    )
}

@Composable
private fun ReportPreview(report: IssueReport) {
    var expanded by rememberSaveable(report.reportId) { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text(stringResource(if (expanded) R.string.issue_report_hide_details else R.string.issue_report_review_details)) }
    if (expanded) {
        report.title?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        report.description?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text("${report.version} (${report.build}) · ${report.commit}", style = MaterialTheme.typography.bodySmall)
        Text("Android ${report.android} (SDK ${report.sdk}) · ${report.device}", style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.issue_report_id, report.reportId), style = MaterialTheme.typography.bodySmall)
        report.feature?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        report.errorCode?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        report.errorType?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        report.context?.let { context ->
            if (context.source == "MANUAL_REPORT") Text(stringResource(R.string.issue_report_manual_time), style = MaterialTheme.typography.bodySmall)
            IssueReport.json.encodeToJsonElement(IssueReportContext.serializer(), context).jsonObject.forEach { (key, value) -> Text("$key: $value", style = MaterialTheme.typography.bodySmall) }
        } ?: Text(stringResource(R.string.issue_report_context_unavailable), style = MaterialTheme.typography.bodySmall)
        report.frames.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        report.events.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun reportStateText(state: IssueReportOutbox.State): Int = when (state) {
    IssueReportOutbox.State.QUEUED -> R.string.issue_report_state_queued
    IssueReportOutbox.State.SENDING -> R.string.issue_report_state_sending
    IssueReportOutbox.State.SENT -> R.string.issue_report_state_sent
    IssueReportOutbox.State.FAILED -> R.string.issue_report_state_failed
}

private fun reportFailureText(report: IssueReportOutbox.Entry): Int = when (report.failureCode) {
    "REPORT_SERVICE_UNCONFIGURED" -> R.string.issue_report_unconfigured
    "REPORT_SERVICE_UPDATE_REQUIRED" -> R.string.issue_report_service_update
    else -> if (report.retryable) R.string.issue_report_send_failed else R.string.issue_report_rejected
}

@Composable
private fun SubmissionError(error: IssueReportViewModel.Notice?) {
    val message = when (error) {
        IssueReportViewModel.Notice.SaveFailed -> R.string.issue_report_save_failed
        IssueReportViewModel.Notice.TooLarge -> R.string.issue_report_too_large
        IssueReportViewModel.Notice.StorageFull -> R.string.issue_report_storage_full
        else -> return
    }
    Text(stringResource(message), color = MaterialTheme.colorScheme.error)
}
