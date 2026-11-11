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
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** WorkManager persists the approved, filtered payload and retries with the same report ID. */
class IssueReportWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val payload = inputData.getString(PAYLOAD) ?: return@withContext Result.failure()
        if (!configured) return@withContext Result.failure()
        val request = Request.Builder().url("${BuildConfig.ISSUE_REPORT_ORIGIN}/v1/issue-reports")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE)).build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.code in 500..599 || response.code in setOf(408, 425, 429)) return@use Result.retry()
                if (!response.isSuccessful) return@use Result.failure()
                val body = response.body ?: return@use Result.retry()
                if (body.source().request(MAX_RECEIPT_BYTES + 1)) return@use Result.retry()
                val number = IssueReport.json.decodeFromString(Receipt.serializer(), body.string()).number
                if (number > 0) Result.success(workDataOf(ISSUE_NUMBER to number)) else Result.retry()
            }
        } catch (_: IOException) {
            Result.retry()
        } catch (_: SerializationException) {
            // GitHub may have accepted the report even if a proxy damaged the receipt.
            Result.retry()
        }
    }

    @Serializable
    private data class Receipt(val number: Int)

    companion object {
        internal const val TAG = "gpt-mobile-issue-report"
        internal const val ISSUE_NUMBER = "issue-number"
        private const val PAYLOAD = "approved-report"
        private const val MAX_PAYLOAD_BYTES = 8192
        private const val MAX_RECEIPT_BYTES = 1024L
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).callTimeout(40, TimeUnit.SECONDS).build()
        internal val configured: Boolean get() = BuildConfig.ISSUE_REPORT_ORIGIN.isNotBlank()

        internal suspend fun enqueue(context: Context, report: IssueReport) {
            check(configured)
            val payload = IssueReport.json.encodeToString(IssueReport.serializer(), report)
            require(payload.encodeToByteArray().size <= MAX_PAYLOAD_BYTES)
            val request = OneTimeWorkRequestBuilder<IssueReportWorker>()
                .setInputData(workDataOf(PAYLOAD to payload))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("$TAG:${report.reportId}", ExistingWorkPolicy.KEEP, request).await()
        }
    }
}
