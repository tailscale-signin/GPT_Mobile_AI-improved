package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.Application
import android.content.Context
import androidx.lifecycle.viewModelScope
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
import dev.chungjungsoo.gptmobile.presentation.ui.setting.IssueReportViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
        LocalDiagnosticsPolicy.setEnabled(false)
        IssueReportIncidents.privacyChanged(false)
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

    @Test fun displayedSaveForLaterStaysLocalWhenAnAccountAppearsBeforeTheClick() = runBlocking {
        withReportModel(emptyList()) { model, available, manager ->
            model.openManual()
            val displayedDraft = IssueReportViewModel.ManualDraft(title = "Saved report", description = "Approved local contents")
            model.updateDraft(displayedDraft)
            val displayedPreview = requireNotNull(model.manualPreview.value)
            val id = displayedPreview.reportId
            val displayedRoute = model.route.value
            assertNull(displayedRoute)
            val clickDisplayedButton = { model.submitManual(displayedPreview, displayedDraft, displayedRoute) }

            available.value = listOf(route)
            model.refreshAvailability()
            withTimeout(5000) { model.route.first { it == route } }
            clickDisplayedButton()

            val saved = withTimeout(5000) { model.reports.first { rows -> rows.any { it.reportId == id } } }.single { it.reportId == id }
            assertEquals(IssueReportOutbox.State.FAILED, saved.state)
            assertEquals("REPORT_DESTINATION_REQUIRED", saved.failureCode)
            assertNull(saved.route)
            assertFalse(saved.mayHaveBeenSubmitted)
            verify(exactly = 0) { manager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
        }
    }

    @Test fun displayedAccountApprovalCannotFollowAChangedAvailabilityDefault() = runBlocking {
        withReportModel(listOf(route)) { model, available, manager ->
            model.openManual()
            val displayedDraft = IssueReportViewModel.ManualDraft(title = "Account-bound report", description = "Original approved contents")
            model.updateDraft(displayedDraft)
            val displayedPreview = requireNotNull(model.manualPreview.value)
            val id = displayedPreview.reportId
            val displayedRoute = model.route.value
            assertEquals(route, displayedRoute)
            val clickDisplayedButton = { model.submitManual(displayedPreview, displayedDraft, displayedRoute) }
            val replacement = route.copy(connectionUid = "another-connection", accountId = "43", accountLogin = "another-author")

            available.value = listOf(replacement)
            model.refreshAvailability()
            withTimeout(5000) { model.route.first { it == replacement } }
            clickDisplayedButton()

            val saved = withTimeout(5000) { model.reports.first { rows -> rows.any { it.reportId == id } } }.single { it.reportId == id }
            assertEquals(IssueReportOutbox.State.QUEUED, saved.state)
            assertEquals(route, saved.route)
            assertFalse(saved.mayHaveBeenSubmitted)
            verify(exactly = 1) { manager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
        }
    }

    @Test fun displayedIncidentSaveForLaterDoesNotApproveANewlyAvailableAccount() = runBlocking {
        withReportModel(emptyList()) { model, available, manager ->
            LocalDiagnosticsPolicy.setEnabled(true)
            val incident = report.copy(feature = "chat", errorCode = "NETWORK_ERROR")
            val displayedRoute = model.route.value
            val clickDisplayedButton = { model.submitIncident(incident, displayedRoute) }
            available.value = listOf(route)
            model.refreshAvailability()
            withTimeout(5000) { model.route.first { it == route } }

            clickDisplayedButton()

            val saved = withTimeout(5000) { model.reports.first { rows -> rows.any { it.reportId == incident.reportId } } }.single { it.reportId == incident.reportId }
            assertEquals("REPORT_DESTINATION_REQUIRED", saved.failureCode)
            assertNull(saved.route)
            assertFalse(saved.mayHaveBeenSubmitted)
            verify(exactly = 0) { manager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
        }
    }

    @Test fun savedReportRetryRequiresTheDisplayedDestinationAndDoesNotAdoptANewDefault() = runBlocking {
        val outbox = IssueReportOutbox.forContext(context)
        outbox.approve(report)
        outbox.failed(report.reportId, IssueReportSubmission.Failure("REPORT_DESTINATION_REQUIRED", true, automaticRetry = false), automaticRetry = false)
        withReportModel(listOf(route)) { model, available, manager ->
            model.selectReport(report.reportId)
            val displayedRoute = model.route.value
            assertEquals(route, displayedRoute)
            val replacement = route.copy(connectionUid = "another-connection", accountId = "43", accountLogin = "another-author")
            available.value = listOf(replacement)
            model.refreshAvailability()
            withTimeout(5000) { model.route.first { it == replacement } }

            assertFalse(model.retry(report.reportId, null))
            assertNull(outbox.load(report.reportId)?.route)
            verify(exactly = 0) { manager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
            assertTrue(model.retry(report.reportId, displayedRoute))
            assertEquals(route, outbox.load(report.reportId)?.route)
            assertEquals(report.approvedPayload(), outbox.load(report.reportId)?.payload)
            verify(exactly = 1) { manager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun withReportModel(
        initialRoutes: List<IssueReportRoute>,
        block: suspend (IssueReportViewModel, MutableStateFlow<List<IssueReportRoute>>, WorkManager) -> Unit
    ) {
        val available = MutableStateFlow(initialRoutes)
        val router = mockk<IssueReportRouting>()
        coEvery { router.options() } coAnswers { available.value }
        val manager = mockk<WorkManager>()
        val operation = mockk<Operation>()
        val initialized = CompletableDeferred<Unit>()
        every { operation.result } returns ImmediateFuture(Operation.SUCCESS)
        every { manager.getWorkInfosByTagFlow(any()) } answers {
            if (firstArg<String>() == IssueReportWorker.TAG) initialized.complete(Unit)
            flowOf(emptyList())
        }
        every { manager.enqueueUniqueWork(any(), ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) } returns operation
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkObject(WorkManager.Companion)
        var model: IssueReportViewModel? = null
        try {
            every { WorkManager.getInstance(context) } returns manager
            val active = IssueReportViewModel(context, dagger.Lazy { router })
            model = active
            withTimeout(5000) { initialized.await() }
            block(active, available, manager)
        } finally {
            model?.viewModelScope?.coroutineContext?.job?.cancelAndJoin()
            unmockkObject(WorkManager.Companion)
            Dispatchers.resetMain()
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
