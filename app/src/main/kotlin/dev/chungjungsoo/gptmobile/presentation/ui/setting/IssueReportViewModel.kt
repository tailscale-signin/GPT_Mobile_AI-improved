package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReport
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidents
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportManualCapture
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportOutbox
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportRoute
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportRouting
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportWorker
import dev.chungjungsoo.gptmobile.data.diagnostics.LocalDiagnosticsPolicy
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class IssueReportViewModel @Inject constructor(@ApplicationContext private val context: Context, private val routing: Lazy<IssueReportRouting>) : ViewModel() {
    private val mutableSaving = MutableStateFlow(false)
    private val mutableManual = MutableStateFlow(false)
    private val mutableManualPreview = MutableStateFlow<IssueReport?>(null)
    private val mutableError = MutableStateFlow<Notice?>(null)
    private val mutableReports = MutableStateFlow<List<IssueReportOutbox.Entry>>(emptyList())
    private val mutableRoutes = MutableStateFlow<List<IssueReportRoute>>(emptyList())
    private val mutableRoute = MutableStateFlow<IssueReportRoute?>(null)
    private val mutableDraft = MutableStateFlow(ManualDraft())
    private val setupInProgress = MutableStateFlow(false)
    private val selectedReportId = MutableStateFlow<String?>(null)
    private val announced = MutableStateFlow<Set<String>>(emptySet())
    private val preferences by lazy { context.getSharedPreferences("issue_report_receipts", Context.MODE_PRIVATE) }
    internal val saving = mutableSaving.asStateFlow()
    internal val manual = mutableManual.asStateFlow()
    internal val manualPreview = mutableManualPreview.asStateFlow()
    internal val error = mutableError.asStateFlow()
    internal val reports = mutableReports.asStateFlow()
    internal val routes = mutableRoutes.asStateFlow()
    internal val route = mutableRoute.asStateFlow()
    internal val draft = mutableDraft.asStateFlow()
    internal val submission = combine(mutableReports, selectedReportId) { reports, id -> reports.firstOrNull { it.reportId == id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    internal val receipts = combine(mutableReports, selectedReportId, announced) { reports, selected, seen ->
        reports.filter { it.reportId != selected && it.state in setOf(IssueReportOutbox.State.SENT, IssueReportOutbox.State.FAILED) && it.receiptKey !in seen }
    }
    internal val currentIncident = combine(IssueReportIncidents.pending, LocalDiagnosticsPolicy.state, IssueReportIncidents.privateSession, setupInProgress) { reports, debug, isPrivate, setup ->
        reports.firstOrNull().takeIf { debug && !isPrivate && !setup }
    }

    init {
        viewModelScope.launch {
            combine(LocalDiagnosticsPolicy.state, IssueReportIncidents.privateSession) { debug, isPrivate -> debug && !isPrivate }
                .collect { available ->
                    if (!available) previewManualDiagnostics(mutableDraft.value.includeDiagnostics)
                }
        }
        viewModelScope.launch(Dispatchers.IO) {
            announced.value = preferences.getStringSet("announced", emptySet()).orEmpty().toSet()
            refresh()
            refreshRoutes()
            IssueReportWorker.resumeApproved(context)
            refresh()
            WorkManager.getInstance(context).getWorkInfosByTagFlow(IssueReportWorker.TAG).collect { refresh() }
        }
    }

    internal fun openManual() {
        setupInProgress.value = false
        mutableError.value = null
        // This timestamp describes report creation, not an inferred failure time.
        if (mutableManualPreview.value == null) mutableManualPreview.value = IssueReport.manual("", "", emptyList())
        mutableManual.value = true
        refreshAvailability()
    }

    internal fun closeManual() {
        if (!mutableSaving.value) {
            mutableError.value = null
            mutableManual.value = false
        }
    }

    internal fun selectReport(reportId: String) {
        mutableManual.value = false
        mutableError.value = null
        selectedReportId.value = reportId
        val report = mutableReports.value.firstOrNull { it.reportId == reportId }
        mutableRoute.value = report?.route ?: defaultRoute(mutableRoutes.value)
        refreshAvailability()
    }

    internal fun selectRoute(route: IssueReportRoute) {
        if (!mutableSaving.value && route in mutableRoutes.value) mutableRoute.value = route
    }

    internal fun updateDraft(value: ManualDraft) {
        mutableDraft.value = value
    }

    internal fun refreshAvailability() {
        viewModelScope.launch(Dispatchers.IO) { refreshRoutes() }
    }

    internal fun closeForSetup() {
        setupInProgress.value = true
        mutableManual.value = false
        selectedReportId.value = null
    }

    internal fun completeSetup() {
        if (setupInProgress.compareAndSet(expect = true, update = false)) refreshAvailability()
    }

    internal fun closeSubmission() {
        val saved = submission.value
        viewModelScope.launch {
            if (saved?.state in setOf(IssueReportOutbox.State.SENT, IssueReportOutbox.State.FAILED)) saved?.let { acknowledge(it) }
            selectedReportId.value = null
            mutableError.value = null
        }
    }

    internal suspend fun acknowledge(receipt: IssueReportOutbox.Entry) = withContext(Dispatchers.IO) {
        announced.update { it + receipt.receiptKey }
        preferences.edit().putStringSet("announced", announced.value).apply()
    }

    internal fun submitManual(preview: IssueReport, draft: ManualDraft, approvedRoute: IssueReportRoute?) {
        if (draft.title.isBlank() || draft.description.isBlank() || preview.reportId != mutableManualPreview.value?.reportId) return
        if (draft.title.length > IssueReport.TITLE_LIMIT || draft.description.length > IssueReportManualCapture.descriptionLimit(draft.relatedIssueNumber)) return
        if (draft.includeDiagnostics && (draft.diagnosticArea == null || preview.context?.diagnostics?.area != draft.diagnosticArea)) return
        if (draft.includeDiagnostics && (preview.context?.diagnostics?.values?.isNotEmpty() == true || preview.events.isNotEmpty()) && !IssueReportIncidents.diagnosticCaptureEnabled()) {
            previewManualDiagnostics(true)
            mutableError.value = Notice.DiagnosticsChanged
            return
        }
        enqueue(approvedRoute) {
            IssueReportManualCapture.submission(preview, draft.title, draft.description, draft.includeDiagnostics, draft.relatedIssueNumber)
        }
    }

    internal fun previewManualDiagnostics(include: Boolean) {
        val area = mutableDraft.value.diagnosticArea
        val snapshot = if (include && area != null) IssueReportIncidents.diagnosticSnapshot(area) else null
        val events = if (include && IssueReportIncidents.diagnosticCaptureEnabled()) IssueReportIncidents.recentEvents() else emptyList()
        mutableManualPreview.update { report -> report?.let { IssueReportManualCapture.preview(it, include, area, snapshot, events) } }
    }

    internal fun followUpReport(receipt: IssueReportOutbox.Entry) {
        if (mutableSaving.value) return
        val number = receipt.issueNumber?.takeIf { it > 0 && receipt.state == IssueReportOutbox.State.SENT } ?: return
        val original = runCatching { IssueReport.json.decodeFromString(IssueReport.serializer(), receipt.payload) }.getOrNull()
        val area = original?.context?.diagnostics?.area ?: when {
            original?.errorCode == "GITHUB_TASK_INCOMPLETE" -> "ACCEPTANCE"
            original?.feature == "voice" -> "VOICE"
            original?.feature == "tools" -> "TOOL"
            original?.feature == "gateway" -> "GATEWAY"
            original?.feature in setOf("chat", "local") -> "PROVIDER"
            original?.feature == "app" -> "APP"
            else -> null
        }
        mutableManualPreview.value = IssueReport.manual("", "", emptyList())
        mutableDraft.value = ManualDraft(
            title = original?.title?.takeIf(String::isNotBlank) ?: original?.errorCode.orEmpty(),
            includeDiagnostics = true,
            diagnosticArea = area,
            relatedIssueNumber = number
        )
        selectedReportId.value = null
        mutableError.value = null
        mutableManual.value = true
        previewManualDiagnostics(true)
        refreshAvailability()
    }

    internal fun submitIncident(report: IssueReport, approvedRoute: IssueReportRoute?) {
        if (!LocalDiagnosticsPolicy.enabled || IssueReportIncidents.privateSession.value) return
        enqueue(approvedRoute, incidentId = report.reportId) { report }
    }

    internal fun dismissIncident(report: IssueReport) {
        if (!mutableSaving.value) {
            mutableError.value = null
            viewModelScope.launch { IssueReportIncidents.dismiss(report.reportId) }
        }
    }

    internal fun retrySubmission(reportId: String, approvedRoute: IssueReportRoute?) {
        viewModelScope.launch { retry(reportId, approvedRoute) }
    }

    internal suspend fun retry(reportId: String, approvedRoute: IssueReportRoute?): Boolean {
        if (!mutableSaving.compareAndSet(expect = false, update = true)) return false
        return try {
            mutableError.value = null
            val saved = mutableReports.value.firstOrNull { it.reportId == reportId } ?: return false
            val destination = approvedRoute ?: return false
            if (saved.mayHaveBeenSubmitted && saved.route != destination) return false
            IssueReportWorker.retry(context, reportId, destination)
            selectReport(reportId)
            refresh()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableError.value = Notice.SaveFailed
            false
        } finally {
            mutableSaving.value = false
        }
    }

    internal fun removeFailed(reportId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                IssueReportOutbox.forContext(context).removeFailed(reportId)
                selectedReportId.value = null
                refresh()
            } catch (_: Exception) {
                mutableError.value = Notice.SaveFailed
            }
        }
    }

    private fun enqueue(approvedRoute: IssueReportRoute?, incidentId: String? = null, buildReport: () -> IssueReport) {
        if (!mutableSaving.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                mutableError.value = null
                val report = buildReport()
                val saved = IssueReportWorker.enqueue(context, report, approvedRoute)
                selectedReportId.value = saved.reportId
                refresh()
                if (incidentId == null) {
                    mutableManual.value = false
                    mutableManualPreview.value = null
                    mutableDraft.value = ManualDraft()
                } else {
                    IssueReportIncidents.dismiss(incidentId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IllegalArgumentException) {
                mutableError.value = Notice.TooLarge
            } catch (failure: IllegalStateException) {
                mutableError.value = if (failure.message == IssueReportOutbox.CAPACITY_MESSAGE) Notice.StorageFull else Notice.SaveFailed
            } catch (_: Exception) {
                mutableError.value = Notice.SaveFailed
            } finally {
                mutableSaving.value = false
            }
        }
    }

    private suspend fun refresh() = withContext(Dispatchers.IO) { mutableReports.value = IssueReportOutbox.forContext(context).entries() }

    private suspend fun refreshRoutes() {
        try {
            val available = routing.get().options()
            mutableRoutes.value = available
            val current = mutableRoute.value
            val saved = mutableReports.value.firstOrNull { it.reportId == selectedReportId.value }
            if (saved?.mayHaveBeenSubmitted == true) {
                mutableRoute.value = saved.route
            } else if (current !in available) {
                mutableRoute.value = defaultRoute(available)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Availability must never stay optimistically enabled after local lookup fails.
            mutableRoutes.value = listOfNotNull(IssueReportRouting.serviceRoute())
            mutableRoute.value = defaultRoute(mutableRoutes.value)
        }
    }

    private fun defaultRoute(options: List<IssueReportRoute>): IssueReportRoute? = options.firstOrNull { it.kind == IssueReportRoute.Kind.SERVICE } ?: options.singleOrNull()

    internal enum class Notice { SaveFailed, TooLarge, StorageFull, DiagnosticsChanged }
    internal data class ManualDraft(
        val title: String = "",
        val description: String = "",
        val includeDiagnostics: Boolean = false,
        val diagnosticArea: String? = null,
        val relatedIssueNumber: Int? = null
    )
}
