package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.ProgressUpdater
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.common.util.concurrent.ListenableFuture
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IssueReportWorkerTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val report = IssueReport("75d84431-c695-4ff4-973b-3a80d63d5993", "manual", "1.0", 1, "abcdef0", "16", 36, "Fixture", title = "Retained report", description = "Original approved text")
    private val route = IssueReportRoute(IssueReportRoute.Kind.GITHUB, connectionUid = "approved-connection", accountId = "42", accountLogin = "approved-author")

    @Before @After
    fun reset() {
        File(context.noBackupFilesDir, "approved_issue_reports").deleteRecursively()
        File(context.noBackupFilesDir, "issue-report-correlations.json").delete()
        IssueReportIncidents.initialize(context)
    }

    @Test fun nativeEnqueuePersistsApprovedPayloadAndCorrelationBeforeSchedulingOnlyItsIdentity() = runBlocking {
        val manager = mockk<WorkManager>()
        val operation = mockk<Operation>()
        val request = slot<OneTimeWorkRequest>()
        every { operation.result } returns ImmediateFuture(Operation.SUCCESS)
        mockkObject(WorkManager.Companion, IssueReportWorker.Companion)
        try {
            every { WorkManager.getInstance(context) } returns manager
            every { manager.getWorkInfosByTagFlow(any()) } returns flowOf(emptyList())
            every { manager.enqueueUniqueWork("${IssueReportWorker.TAG}:${report.reportId}", ExistingWorkPolicy.KEEP, capture(request)) } answers {
                val durable = requireNotNull(IssueReportOutbox.forContext(context).load(report.reportId))
                assertEquals(report.approvedPayload(), durable.payload)
                operation
            }
            val approved = IssueReportWorker.enqueue(context, report, route)
            assertEquals(report.reportId, request.captured.workSpec.input.getString(IssueReportWorker.REPORT_ID))
            assertNull(request.captured.workSpec.input.getString("approved-report"))
            assertEquals(setOf(IssueReportWorker.REPORT_ID), request.captured.workSpec.input.keyValueMap.keys)
            assertEquals(androidx.work.NetworkType.CONNECTED, request.captured.workSpec.constraints.requiredNetworkType)
            assertEquals(report.approvedPayload(), approved.payload)
            assertEquals(route, approved.route)
            assertFalse(approved.mayHaveBeenSubmitted)
            assertTrue(IssueReportIncidents.correlationSnapshot().any { it["reportId"].toString() == "\"${report.reportId}\"" })
        } finally {
            unmockkObject(WorkManager.Companion, IssueReportWorker.Companion)
        }
    }

    @Test fun legacyApprovedWorkerPreservesExactWireBytesWithoutGuessingItsLostDestination() = runBlocking {
        // Whitespace distinguishes the originally approved bytes from a reserialization.
        val legacy = report.approvedPayload().replace(",", ",\n ")
        val parameters = mockk<WorkerParameters>()
        val progress = mockk<ProgressUpdater>()
        every { parameters.inputData } returns workDataOf("approved-report" to legacy)
        every { parameters.id } returns UUID.randomUUID()
        every { parameters.progressUpdater } returns progress
        every { progress.updateProgress(any(), any(), any()) } returns ImmediateFuture<Void?>(null)
        mockkObject(IssueReportWorker.Companion)
        try {
            every { IssueReportWorker.routing(context) } returns IssueReportRouting(mockk(), mockk())
            val result = IssueReportWorker(context, parameters).doWork()
            assertTrue(result is ListenableWorker.Result.Failure)
            val output = (result as ListenableWorker.Result.Failure).outputData
            assertEquals(report.reportId, output.getString(IssueReportWorker.REPORT_ID))
            assertEquals("REPORT_LEGACY_DESTINATION_UNKNOWN", output.getString(IssueReportWorker.FAILURE_CODE))
            val saved = requireNotNull(IssueReportOutbox.forContext(context).load(report.reportId))
            assertEquals(legacy, saved.payload)
            assertEquals(IssueReportOutbox.State.FAILED, saved.state)
            assertNull(saved.issueNumber)
            assertTrue(saved.mayHaveBeenSubmitted)
            assertFalse(saved.retryable)
        } finally {
            unmockkObject(IssueReportWorker.Companion)
        }
    }

    @Test fun unconfiguredSendIsSavedInAppWithoutSchedulingOrBrowserFallback() = runBlocking {
        mockkObject(IssueReportWorker.Companion)
        try {
            val result = IssueReportWorker.enqueue(context, report)
            assertEquals(IssueReportOutbox.State.FAILED, result.state)
            assertEquals("REPORT_DESTINATION_REQUIRED", result.failureCode)
            assertEquals(report.approvedPayload(), result.payload)
            assertTrue(result.retryable)
            assertFalse(result.mayHaveBeenSubmitted)
            assertFalse(result.payload.contains("issues/new?"))
        } finally {
            unmockkObject(IssueReportWorker.Companion)
        }
    }

    @Test fun anOldUnconfiguredReportCanBeRetriedWithExplicitNewAccountConsent() = runBlocking {
        val outbox = IssueReportOutbox.forContext(context)
        outbox.approve(report)
        outbox.failed(report.reportId, IssueReportSubmission.Failure("REPORT_SERVICE_UNCONFIGURED", true, automaticRetry = false), automaticRetry = false)
        // Startup must not implicitly choose a public author for an old service report.
        IssueReportWorker.resumeApproved(context)
        assertEquals(IssueReportOutbox.State.FAILED, outbox.load(report.reportId)?.state)
        val manager = mockk<WorkManager>()
        val operation = mockk<Operation>()
        every { operation.result } returns ImmediateFuture(Operation.SUCCESS)
        mockkObject(WorkManager.Companion)
        try {
            every { WorkManager.getInstance(context) } returns manager
            every { manager.getWorkInfosByTagFlow(any()) } returns flowOf(emptyList())
            every { manager.enqueueUniqueWork(any(), ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) } returns operation
            val queued = IssueReportWorker.retry(context, report.reportId, route)
            assertEquals(IssueReportOutbox.State.QUEUED, queued.state)
            assertEquals(report.reportId, queued.reportId)
            assertEquals(report.approvedPayload(), queued.payload)
            assertEquals(route, queued.route)
            assertFalse(queued.mayHaveBeenSubmitted)
        } finally {
            unmockkObject(WorkManager.Companion)
        }
    }

    private class ImmediateFuture<T>(private val value: T) : ListenableFuture<T> {
        override fun addListener(listener: Runnable, executor: Executor) = executor.execute(listener)
        override fun cancel(mayInterruptIfRunning: Boolean) = false
        override fun isCancelled() = false
        override fun isDone() = true
        override fun get(): T = value
        override fun get(timeout: Long, unit: TimeUnit): T = value
    }
}
