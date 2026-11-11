package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReport
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidents
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportWorker
import dev.chungjungsoo.gptmobile.data.diagnostics.LocalDiagnosticsPolicy
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class IssueReportViewModel @Inject constructor(@ApplicationContext private val context: Context) : ViewModel() {
    private val mutableSaving = MutableStateFlow(false)
    private val mutableManual = MutableStateFlow(false)
    private val mutableError = MutableStateFlow<Notice?>(null)
    private val queuedChannel = Channel<Unit>(Channel.CONFLATED)
    private val mutableReceipts = MutableStateFlow<List<Receipt>>(emptyList())
    private val announced = MutableStateFlow<Set<String>>(emptySet())
    private val preferences by lazy { context.getSharedPreferences("issue_report_receipts", Context.MODE_PRIVATE) }
    internal val saving = mutableSaving.asStateFlow()
    internal val manual = mutableManual.asStateFlow()
    internal val error = mutableError.asStateFlow()
    internal val queued = queuedChannel.receiveAsFlow()
    internal val receipts = mutableReceipts.asStateFlow()
    internal val currentIncident = combine(
        IssueReportIncidents.pending,
        LocalDiagnosticsPolicy.state,
        IssueReportIncidents.privateSession
    ) { reports, debug, isPrivate -> reports.firstOrNull().takeIf { debug && !isPrivate } }

    init {
        if (IssueReportWorker.configured) {
            viewModelScope.launch(Dispatchers.IO) {
                announced.value = preferences.getStringSet("announced", emptySet()).orEmpty().toSet()
                combine(WorkManager.getInstance(context).getWorkInfosByTagFlow(IssueReportWorker.TAG), announced) { work, seen ->
                    work.mapNotNull { info ->
                        val id = info.id.toString()
                        val number = info.outputData.getInt(IssueReportWorker.ISSUE_NUMBER, 0)
                        when {
                            id in seen -> null
                            info.state == WorkInfo.State.SUCCEEDED && number > 0 -> Receipt(id, number)
                            info.state == WorkInfo.State.FAILED -> Receipt(id, null)
                            else -> null
                        }
                    }
                }.collect { mutableReceipts.value = it }
            }
        }
    }

    internal fun openManual() {
        clearError()
        mutableManual.value = true
    }

    internal fun closeManual() {
        if (!mutableSaving.value) {
            clearError()
            mutableManual.value = false
        }
    }

    internal suspend fun acknowledge(receipt: Receipt) = withContext(Dispatchers.IO) {
        announced.update { it + receipt.id }
        preferences.edit().putStringSet("announced", announced.value).apply()
    }

    internal fun submitManual(title: String, description: String, includeDiagnostics: Boolean) {
        if (title.isBlank() || description.isBlank()) return
        enqueue {
            val events = if (includeDiagnostics) IssueReportIncidents.recentEvents() else emptyList()
            IssueReport.manual(title, description, events)
        }
    }

    internal fun submitIncident(report: IssueReport) {
        if (!LocalDiagnosticsPolicy.enabled || IssueReportIncidents.privateSession.value) return
        enqueue(incidentId = report.reportId) { report }
    }

    internal fun dismissIncident(report: IssueReport) {
        if (!mutableSaving.value) {
            clearError()
            viewModelScope.launch { IssueReportIncidents.dismiss(report.reportId) }
        }
    }

    private fun clearError() {
        mutableError.value = null
    }

    private fun enqueue(incidentId: String? = null, buildReport: () -> IssueReport) {
        if (!mutableSaving.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                clearError()
                if (!IssueReportWorker.configured) {
                    mutableError.value = Notice.Unavailable
                    return@launch
                }
                withContext(Dispatchers.IO) { IssueReportWorker.enqueue(context, buildReport()) }
                if (incidentId == null) mutableManual.value = false else IssueReportIncidents.dismiss(incidentId)
                queuedChannel.trySend(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableError.value = Notice.SaveFailed
            } finally {
                mutableSaving.value = false
            }
        }
    }

    internal enum class Notice { Unavailable, SaveFailed }

    internal data class Receipt(val id: String, val number: Int?)
}
