package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReport
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidents
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportOutbox
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
class IssueReportViewModel @Inject constructor(@ApplicationContext private val context: Context) : ViewModel() {
    private val mutableSaving = MutableStateFlow(false)
    private val mutableManual = MutableStateFlow(false)
    private val mutableManualPreview = MutableStateFlow<IssueReport?>(null)
    private val mutableError = MutableStateFlow<Notice?>(null)
    private val mutableReports = MutableStateFlow<List<IssueReportOutbox.Entry>>(emptyList())
    private val selectedReportId = MutableStateFlow<String?>(null)
    private val announced = MutableStateFlow<Set<String>>(emptySet())
    private val preferences by lazy { context.getSharedPreferences("issue_report_receipts", Context.MODE_PRIVATE) }
    internal val saving = mutableSaving.asStateFlow()
    internal val manual = mutableManual.asStateFlow()
    internal val manualPreview = mutableManualPreview.asStateFlow()
    internal val error = mutableError.asStateFlow()
    internal val reports = mutableReports.asStateFlow()
    internal val configured: Boolean get() = IssueReportWorker.configured
    internal val submission = combine(mutableReports, selectedReportId) { reports, id -> reports.firstOrNull { it.reportId == id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    internal val receipts = combine(mutableReports, selectedReportId, announced) { reports, selected, seen ->
        reports.filter { it.reportId != selected && it.state in setOf(IssueReportOutbox.State.SENT, IssueReportOutbox.State.FAILED) && it.receiptKey !in seen }
    }
    internal val currentIncident = combine(IssueReportIncidents.pending, LocalDiagnosticsPolicy.state, IssueReportIncidents.privateSession) { reports, debug, isPrivate ->
        reports.firstOrNull().takeIf { debug && !isPrivate }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            announced.value = preferences.getStringSet("announced", emptySet()).orEmpty().toSet()
            refresh()
            IssueReportWorker.resumeApproved(context)
            refresh()
            WorkManager.getInstance(context).getWorkInfosByTagFlow(IssueReportWorker.TAG).collect { refresh() }
        }
    }

    internal fun openManual() {
        mutableError.value = null
        // This timestamp describes report creation, not an inferred failure time.
        mutableManualPreview.value = IssueReport.manual("", "", emptyList())
        mutableManual.value = true
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

    internal fun submitManual(title: String, description: String, includeDiagnostics: Boolean) {
        if (title.isBlank() || description.isBlank()) return
        val preview = mutableManualPreview.value ?: return
        enqueue {
            val events = if (includeDiagnostics) preview.events else emptyList()
            IssueReport.manual(title, description, events).copy(reportId = preview.reportId, context = preview.context)
        }
    }

    internal fun previewManualDiagnostics(include: Boolean) {
        mutableManualPreview.update { it?.copy(events = if (include) IssueReportIncidents.recentEvents() else emptyList()) }
    }

    internal fun submitIncident(report: IssueReport) {
        if (!LocalDiagnosticsPolicy.enabled || IssueReportIncidents.privateSession.value) return
        enqueue(incidentId = report.reportId) { report }
    }

    internal fun dismissIncident(report: IssueReport) {
        if (!mutableSaving.value) {
            mutableError.value = null
            viewModelScope.launch { IssueReportIncidents.dismiss(report.reportId) }
        }
    }

    internal fun retrySubmission(reportId: String) {
        viewModelScope.launch { retry(reportId) }
    }

    internal suspend fun retry(reportId: String): Boolean {
        if (!mutableSaving.compareAndSet(expect = false, update = true)) return false
        return try {
            mutableError.value = null
            IssueReportWorker.retry(context, reportId)
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

    private fun enqueue(incidentId: String? = null, buildReport: () -> IssueReport) {
        if (!mutableSaving.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                mutableError.value = null
                val report = buildReport()
                val saved = IssueReportWorker.enqueue(context, report)
                selectedReportId.value = saved.reportId
                refresh()
                if (incidentId == null) mutableManual.value = false else IssueReportIncidents.dismiss(incidentId)
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

    internal enum class Notice { SaveFailed, TooLarge, StorageFull }
}
