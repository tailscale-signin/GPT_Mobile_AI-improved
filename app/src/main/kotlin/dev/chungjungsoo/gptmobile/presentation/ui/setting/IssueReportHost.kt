package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReport
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportDiagnostics
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportManualCapture
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportOutbox
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportRoute
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportSubmission
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.jsonObject

private const val ISSUE_TRACKER = "https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues"

@Composable
internal fun IssueReportHost(viewModel: IssueReportViewModel, showPrompts: Boolean, onOpenGitHubSettings: () -> Unit = {}, content: @Composable (openReport: () -> Unit) -> Unit) {
    val manual by viewModel.manual.collectAsStateWithLifecycle()
    val manualPreview by viewModel.manualPreview.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val incident by viewModel.currentIncident.collectAsStateWithLifecycle(initialValue = null)
    val error by viewModel.error.collectAsStateWithLifecycle()
    val reports by viewModel.reports.collectAsStateWithLifecycle()
    val submission by viewModel.submission.collectAsStateWithLifecycle()
    val routes by viewModel.routes.collectAsStateWithLifecycle()
    // Capture the route rendered by this composition. A later StateFlow update
    // must not turn this frame's Save for later click into a delivery approval.
    val route = viewModel.route.collectAsStateWithLifecycle().value
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources by rememberUpdatedState(LocalResources.current)
    val uriHandler = LocalUriHandler.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.refreshAvailability()
            viewModel.receipts.map { it.firstOrNull() }.distinctUntilChanged().filterNotNull().collect { receipt ->
                val number = receipt.issueNumber
                if (receipt.state == IssueReportOutbox.State.SENT && number != null) {
                    val action = snackbar.showSnackbar(resources.getString(R.string.issue_report_posted, number), resources.getString(R.string.issue_report_view), duration = SnackbarDuration.Long)
                    if (action == SnackbarResult.ActionPerformed) runCatching { uriHandler.openUri("$ISSUE_TRACKER/$number") }
                } else {
                    val action = snackbar.showSnackbar(resources.getString(reportFailureText(receipt)), resources.getString(R.string.issue_report_review_saved), withDismissAction = true, duration = SnackbarDuration.Long)
                    if (action == SnackbarResult.ActionPerformed) viewModel.selectReport(receipt.reportId)
                }
                viewModel.acknowledge(receipt)
            }
        }
    }
    val openGitHub = {
        viewModel.closeForSetup()
        onOpenGitHubSettings()
    }
    Box(Modifier.fillMaxSize()) {
        content(viewModel::openManual)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
    when {
        manual -> ManualIssueDialog(saving, error, manualPreview, reports, routes, route, draft, viewModel::updateDraft, viewModel::selectRoute, openGitHub, viewModel::closeManual, viewModel::submitManual, viewModel::previewManualDiagnostics, viewModel::selectReport)
        submission != null -> submission?.let { report ->
            IssueSubmissionDialog(report, saving, error, routes, route, viewModel::selectRoute, openGitHub, viewModel::closeSubmission, { approvedRoute -> viewModel.retrySubmission(report.reportId, approvedRoute) }, { viewModel.removeFailed(report.reportId) }, { viewModel.followUpReport(report) }) {
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
                        ReportDestination(routes, route, saving, viewModel::selectRoute, openGitHub)
                        ReportPreview(report)
                        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                        SubmissionError(error)
                    }
                },
                dismissButton = { TextButton(onClick = { viewModel.dismissIncident(report) }, enabled = !saving) { Text(stringResource(R.string.issue_report_no)) } },
                confirmButton = {
                    Button(onClick = { viewModel.submitIncident(report, route) }, enabled = !saving) {
                        Text(
                            stringResource(
                                if (saving) {
                                    R.string.issue_report_saving
                                } else if (route == null) {
                                    R.string.issue_report_save_later
                                } else {
                                    R.string.issue_report_submit
                                }
                            )
                        )
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
    preview: IssueReport?,
    reports: List<IssueReportOutbox.Entry>,
    routes: List<IssueReportRoute>,
    route: IssueReportRoute?,
    draft: IssueReportViewModel.ManualDraft,
    onDraft: (IssueReportViewModel.ManualDraft) -> Unit,
    onRoute: (IssueReportRoute) -> Unit,
    onSetup: () -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (IssueReport, IssueReportViewModel.ManualDraft, IssueReportRoute?) -> Unit,
    onDiagnostics: (Boolean) -> Unit,
    onSelectReport: (String) -> Unit
) {
    val title = draft.title
    val description = draft.description
    val includeDiagnostics = draft.includeDiagnostics
    val descriptionLimit = IssueReportManualCapture.descriptionLimit(draft.relatedIssueNumber)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.issue_report_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ReportDestination(routes, route, saving, onRoute, onSetup)
                draft.relatedIssueNumber?.let { Text(stringResource(R.string.issue_report_follow_up_to, it), style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(
                    title,
                    { onDraft(draft.copy(title = it)) },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.issue_report_short_title)) },
                    supportingText = { Text(stringResource(R.string.issue_report_character_count, title.length, IssueReport.TITLE_LIMIT)) },
                    isError = title.length > IssueReport.TITLE_LIMIT,
                    singleLine = true,
                    enabled = !saving
                )
                OutlinedTextField(
                    description, { onDraft(draft.copy(description = it)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.issue_report_description)) },
                    supportingText = { Text(stringResource(R.string.issue_report_character_count, description.length, descriptionLimit)) },
                    isError = description.length > descriptionLimit, minLines = 2, maxLines = 5, enabled = !saving
                )
                Row(
                    Modifier.fillMaxWidth().toggleable(includeDiagnostics, enabled = !saving, role = Role.Checkbox) {
                        onDraft(draft.copy(includeDiagnostics = it))
                        onDiagnostics(it)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(checked = includeDiagnostics, onCheckedChange = null, enabled = !saving)
                    Text(stringResource(R.string.issue_report_include_diagnostics))
                }
                if (includeDiagnostics) {
                    DiagnosticAreaSelector(draft.diagnosticArea, saving) { area ->
                        onDraft(draft.copy(diagnosticArea = area))
                        onDiagnostics(true)
                    }
                    preview?.context?.diagnostics?.let { capture ->
                        if (capture.captureMode == "NO_CAPTURE" || capture.values.isEmpty()) {
                            Text(stringResource(R.string.issue_report_capture_unavailable), style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(stringResource(R.string.issue_report_capture_time, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(capture.capturedAtMillis))), style = MaterialTheme.typography.bodySmall)
                            if (capture.unavailableFields.isNotEmpty() || capture.omittedFields.isNotEmpty()) Text(stringResource(R.string.issue_report_capture_partial), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(onClick = { onDiagnostics(true) }, enabled = !saving && draft.diagnosticArea != null) { Text(stringResource(R.string.issue_report_refresh_capture)) }
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
        confirmButton = {
            val diagnosticsReady = !includeDiagnostics || draft.diagnosticArea != null && preview?.context?.diagnostics?.area == draft.diagnosticArea
            Button(onClick = { preview?.let { onSubmit(it, draft, route) } }, enabled = !saving && preview != null && title.isNotBlank() && title.length <= IssueReport.TITLE_LIMIT && description.isNotBlank() && description.length <= descriptionLimit && diagnosticsReady) {
                Text(
                    stringResource(
                        if (saving) {
                            R.string.issue_report_saving
                        } else if (route == null) {
                            R.string.issue_report_save_later
                        } else {
                            R.string.issue_report_submit
                        }
                    )
                )
            }
        }
    )
}

@Composable
private fun DiagnosticAreaSelector(selected: String?, saving: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Text(stringResource(R.string.issue_report_problem_area), style = MaterialTheme.typography.titleSmall)
    Box {
        TextButton(onClick = { expanded = true }, enabled = !saving) {
            Text(selected?.let { stringResource(diagnosticAreaLabel(it)) } ?: stringResource(R.string.issue_report_choose_area))
        }
        DropdownMenu(expanded = expanded && !saving, onDismissRequest = { expanded = false }) {
            IssueReportDiagnostics.areas.forEach { area ->
                DropdownMenuItem(text = { Text(stringResource(diagnosticAreaLabel(area))) }, onClick = {
                    expanded = false
                    onSelect(area)
                })
            }
        }
    }
}

private fun diagnosticAreaLabel(area: String): Int = when (area) {
    "PROVIDER" -> R.string.issue_report_area_provider
    "TOOL" -> R.string.issue_report_area_tool
    "ACCEPTANCE" -> R.string.issue_report_area_acceptance
    "VOICE" -> R.string.issue_report_area_voice
    "GATEWAY" -> R.string.issue_report_area_gateway
    "INCIDENT" -> R.string.issue_report_area_incident
    else -> R.string.issue_report_area_app
}

@Composable
private fun IssueSubmissionDialog(
    report: IssueReportOutbox.Entry,
    saving: Boolean,
    error: IssueReportViewModel.Notice?,
    routes: List<IssueReportRoute>,
    route: IssueReportRoute?,
    onRoute: (IssueReportRoute) -> Unit,
    onSetup: () -> Unit,
    onClose: () -> Unit,
    onRetry: (IssueReportRoute?) -> Unit,
    onRemove: () -> Unit,
    onFollowUp: () -> Unit,
    onView: () -> Unit
) {
    val approved = remember(report.payload) { runCatching { IssueReport.json.decodeFromString(IssueReport.serializer(), report.payload) }.getOrNull() }
    val reconcilingGitHub = report.route?.kind == IssueReportRoute.Kind.GITHUB && report.mayHaveBeenSubmitted
    val retryDestinationAvailable = issueReportRetryDestinationAvailable(report, route)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(if (reconcilingGitHub && report.state == IssueReportOutbox.State.SENDING) R.string.issue_report_check_confirmation else reportStateText(report.state))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (report.state) {
                    IssueReportOutbox.State.QUEUED -> {
                        Text(
                            stringResource(
                                if (reconcilingGitHub) {
                                    R.string.issue_report_github_confirmation_queued
                                } else if (report.failureCode == null) {
                                    R.string.issue_report_queued
                                } else {
                                    R.string.issue_report_retry_queued
                                }
                            )
                        )
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    IssueReportOutbox.State.SENDING -> {
                        Text(stringResource(if (reconcilingGitHub) R.string.issue_report_github_checking else R.string.issue_report_sending))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    IssueReportOutbox.State.SENT -> Text(stringResource(R.string.issue_report_posted, report.issueNumber ?: 0))
                    IssueReportOutbox.State.FAILED -> Text(stringResource(reportFailureText(report)), color = MaterialTheme.colorScheme.error)
                }
                report.failureCode?.let { Text(stringResource(R.string.issue_report_status_code, it), style = MaterialTheme.typography.bodySmall) }
                if (report.state == IssueReportOutbox.State.FAILED && !report.mayHaveBeenSubmitted && report.retryable) {
                    ReportDestination(routes, route, saving, onRoute, onSetup)
                } else {
                    report.route?.let { Text(routeLabel(it), style = MaterialTheme.typography.bodySmall) }
                    if (report.state == IssueReportOutbox.State.FAILED && report.mayHaveBeenSubmitted && report.route != null && !retryDestinationAvailable && report.failureCode != "REPORT_DESTINATION_UNAVAILABLE") {
                        Text(stringResource(R.string.issue_report_destination_wait), style = MaterialTheme.typography.bodySmall)
                    }
                    if (report.failureCode in setOf("REPORT_GITHUB_SIGN_IN_REQUIRED", "REPORT_GITHUB_ACCOUNT_CHANGED", "REPORT_GITHUB_PERMISSION_REQUIRED")) TextButton(onClick = onSetup, enabled = !saving) { Text(stringResource(R.string.issue_report_connect_github)) }
                }
                approved?.let { ReportPreview(it) }
                if (report.state == IssueReportOutbox.State.SENT && report.issueNumber != null) TextButton(onClick = onFollowUp, enabled = !saving) { Text(stringResource(R.string.issue_report_follow_up)) }
                SubmissionError(error)
                if (report.state == IssueReportOutbox.State.FAILED) {
                    Text(stringResource(if (report.mayHaveBeenSubmitted) R.string.issue_report_remove_explanation else R.string.issue_report_not_sent), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onRemove, enabled = !saving) { Text(stringResource(R.string.issue_report_remove_saved)) }
                }
            }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !saving) { Text(stringResource(R.string.close)) } },
        confirmButton = {
            when {
                report.state == IssueReportOutbox.State.SENT -> TextButton(onClick = onView) { Text(stringResource(R.string.issue_report_view)) }
                report.state == IssueReportOutbox.State.FAILED && report.retryable -> Button(onClick = { onRetry(if (report.mayHaveBeenSubmitted) report.route else route) }, enabled = !saving && retryDestinationAvailable) {
                    Text(stringResource(if (report.route?.kind == IssueReportRoute.Kind.GITHUB && report.mayHaveBeenSubmitted) R.string.issue_report_check_confirmation else R.string.retry))
                }
            }
        }
    )
}

/** An uncertain write stays bound to its approved destination even when another route is available. */
internal fun issueReportRetryDestinationAvailable(
    report: IssueReportOutbox.Entry,
    selectedRoute: IssueReportRoute?,
    githubEnabled: Boolean = BuildConfig.ISSUE_REPORT_GITHUB_ENABLED,
    serviceEnabled: Boolean = IssueReportSubmission.configured(BuildConfig.ISSUE_REPORT_ORIGIN)
): Boolean {
    val destination = (if (report.mayHaveBeenSubmitted) report.route else selectedRoute)?.takeIf(IssueReportRoute::valid) ?: return false
    return when (destination.kind) {
        IssueReportRoute.Kind.GITHUB -> githubEnabled
        IssueReportRoute.Kind.SERVICE -> serviceEnabled
    }
}

@Composable
private fun ReportDestination(routes: List<IssueReportRoute>, selected: IssueReportRoute?, saving: Boolean, onSelect: (IssueReportRoute) -> Unit, onSetup: () -> Unit) {
    if (routes.isEmpty()) {
        Text(stringResource(if (BuildConfig.ISSUE_REPORT_GITHUB_ENABLED) R.string.issue_report_setup_required else R.string.issue_report_build_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Text(stringResource(R.string.issue_report_destination), style = MaterialTheme.typography.titleSmall)
        routes.forEach { destination ->
            Row(
                Modifier.fillMaxWidth().selectable(selected == destination, enabled = !saving, role = Role.RadioButton) { onSelect(destination) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RadioButton(selected == destination, onClick = null, enabled = !saving)
                Text(routeLabel(destination), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (selected == null) Text(stringResource(R.string.issue_report_choose_account), style = MaterialTheme.typography.bodySmall)
    }
    if (BuildConfig.ISSUE_REPORT_GITHUB_ENABLED && routes.none { it.kind == IssueReportRoute.Kind.GITHUB }) TextButton(onClick = onSetup, enabled = !saving) { Text(stringResource(R.string.issue_report_connect_github)) }
}

@Composable
private fun routeLabel(route: IssueReportRoute): String = when (route.kind) {
    IssueReportRoute.Kind.SERVICE -> stringResource(R.string.issue_report_service_destination, route.origin.orEmpty())
    IssueReportRoute.Kind.GITHUB -> stringResource(R.string.issue_report_github_destination, route.accountLogin.orEmpty())
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
    "REPORT_SERVICE_UNCONFIGURED", "REPORT_DESTINATION_REQUIRED" -> R.string.issue_report_unconfigured
    "REPORT_DESTINATION_UNAVAILABLE" -> if (report.mayHaveBeenSubmitted) R.string.issue_report_destination_wait else R.string.issue_report_unconfigured
    "REPORT_SERVICE_UPDATE_REQUIRED" -> R.string.issue_report_service_update
    "REPORT_GITHUB_SIGN_IN_REQUIRED", "REPORT_GITHUB_ACCOUNT_CHANGED" -> R.string.issue_report_sign_in_required
    "REPORT_GITHUB_PERMISSION_REQUIRED" -> R.string.issue_report_github_permission
    "REPORT_GITHUB_CONFIRMATION_PENDING" -> R.string.issue_report_github_confirmation
    "REPORT_LEGACY_DESTINATION_UNKNOWN" -> R.string.issue_report_legacy_destination
    else -> if (!report.mayHaveBeenSubmitted) {
        R.string.issue_report_delivery_blocked
    } else if (report.retryable) {
        R.string.issue_report_send_failed
    } else {
        R.string.issue_report_rejected
    }
}

@Composable
private fun SubmissionError(error: IssueReportViewModel.Notice?) {
    val message = when (error) {
        IssueReportViewModel.Notice.SaveFailed -> R.string.issue_report_save_failed
        IssueReportViewModel.Notice.TooLarge -> R.string.issue_report_too_large
        IssueReportViewModel.Notice.StorageFull -> R.string.issue_report_storage_full
        IssueReportViewModel.Notice.DiagnosticsChanged -> R.string.issue_report_diagnostics_changed
        else -> return
    }
    Text(stringResource(message), color = MaterialTheme.colorScheme.error)
}
