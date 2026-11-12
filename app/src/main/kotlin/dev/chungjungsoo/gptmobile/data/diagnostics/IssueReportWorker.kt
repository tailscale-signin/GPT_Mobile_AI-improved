package dev.chungjungsoo.gptmobile.data.diagnostics

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import dev.chungjungsoo.gptmobile.BuildConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** WorkManager delivers only durable, explicitly approved reports; no browser submission fallback. */
class IssueReportWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val outbox = IssueReportOutbox.forContext(applicationContext)
        var reportId = inputData.getString(REPORT_ID)
        try {
            if (reportId == null) {
                // Upgrade an already-approved WorkManager request without changing its wire bytes.
                val payload = inputData.getString(LEGACY_PAYLOAD) ?: return@withContext Result.failure()
                val report = IssueReport.json.decodeFromString(IssueReport.serializer(), payload)
                IssueReportIncidents.retainCorrelation(report)
                reportId = outbox.approve(report, payload).reportId
            }
            val id = checkNotNull(reportId)
            val client = IssueReportSubmission(BuildConfig.ISSUE_REPORT_ORIGIN)
            val result = IssueReportDelivery.attempt(outbox, id, client::send) {
                setProgress(workDataOf(REPORT_ID to id, PHASE to "SENDING"))
            }
            val saved = outbox.load(id)
            val data = workDataOf(REPORT_ID to id, ISSUE_NUMBER to (saved?.issueNumber ?: 0), FAILURE_CODE to saved?.failureCode)
            when (result) {
                IssueReportDelivery.Result.CONFIRMED -> Result.success(data)
                IssueReportDelivery.Result.RETRY -> {
                    setProgress(workDataOf(REPORT_ID to id, PHASE to "QUEUED", FAILURE_CODE to saved?.failureCode))
                    Result.retry()
                }
                IssueReportDelivery.Result.FAILED -> Result.failure(data)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A local persistence failure cannot prove a remote issue was not created.
            reportId?.let { id -> runCatching { outbox.failed(id, IssueReportSubmission.Failure("REPORT_LOCAL_UNAVAILABLE", retryable = true, automaticRetry = false), automaticRetry = false) } }
            Result.failure(workDataOf(REPORT_ID to reportId, FAILURE_CODE to "REPORT_LOCAL_UNAVAILABLE"))
        }
    }

    companion object {
        internal const val TAG = "gpt-mobile-issue-report"
        internal const val REPORT_ID = "report-id"
        internal const val ISSUE_NUMBER = "issue-number"
        internal const val FAILURE_CODE = "failure-code"
        private const val PHASE = "phase"
        private const val LEGACY_PAYLOAD = "approved-report"
        private val queueLock = Mutex()
        internal val configured: Boolean get() = IssueReportSubmission.configured(BuildConfig.ISSUE_REPORT_ORIGIN)

        internal suspend fun enqueue(context: Context, report: IssueReport): IssueReportOutbox.Entry = withContext(Dispatchers.IO) {
            val payload = report.approvedPayload()
            IssueReportIncidents.retainCorrelation(report)
            val approved = IssueReportOutbox.forContext(context).approve(report, payload)
            enqueueApproved(context, approved.reportId, manualRetry = false)
        }

        internal suspend fun retry(context: Context, reportId: String): IssueReportOutbox.Entry =
            withContext(Dispatchers.IO) { enqueueApproved(context, reportId, manualRetry = true) }

        internal suspend fun resumeApproved(context: Context) = withContext(Dispatchers.IO) {
            IssueReportOutbox.forContext(context).entries().filter { it.state in setOf(IssueReportOutbox.State.QUEUED, IssueReportOutbox.State.SENDING) }.forEach {
                enqueueApproved(context, it.reportId, manualRetry = false)
            }
        }

        private suspend fun enqueueApproved(context: Context, reportId: String, manualRetry: Boolean): IssueReportOutbox.Entry = queueLock.withLock {
            val outbox = IssueReportOutbox.forContext(context)
            val saved = checkNotNull(outbox.load(reportId)) { "Approved report is unavailable" }
            if (saved.state == IssueReportOutbox.State.SENT || !saved.retryable) return@withLock saved
            if (!configured) return@withLock outbox.failed(reportId, IssueReportSubmission.Failure("REPORT_SERVICE_UNCONFIGURED", retryable = true, automaticRetry = false), automaticRetry = false)
            try {
                val manager = WorkManager.getInstance(context)
                val name = "$TAG:$reportId"
                if (manager.getWorkInfosByTagFlow(name).first().any { !it.state.isFinished }) return@withLock saved
                val queued = outbox.queue(reportId, manualRetry)
                val request = OneTimeWorkRequestBuilder<IssueReportWorker>()
                    .setInputData(workDataOf(REPORT_ID to reportId))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInitialDelay((queued.retryNotBeforeMillis - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                    .addTag(TAG).addTag(name).build()
                manager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request).await()
                checkNotNull(outbox.load(reportId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                outbox.failed(reportId, IssueReportSubmission.Failure("REPORT_QUEUE_UNAVAILABLE", retryable = true, automaticRetry = false), automaticRetry = false)
            }
        }
    }
}
