package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.app.Application
import android.app.job.JobScheduler
import android.content.Context
import android.net.NetworkCapabilities
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.util.concurrent.ListenableFuture
import dev.chungjungsoo.gptmobile.data.voice.KokoroVoice
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionBackend
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VoicePreferenceStoreTest {
    @Test
    fun durableWorkManagerStatesRestoreVisibleInstallPhase() {
        val install = dev.chungjungsoo.gptmobile.data.voice.VoicePluginInstall
        assertEquals("QUEUED", install.phaseForWorkStates(listOf(WorkInfo.State.ENQUEUED)))
        assertEquals("QUEUED", install.phaseForWorkStates(listOf(WorkInfo.State.BLOCKED)))
        assertEquals("RUNNING", install.phaseForWorkStates(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING)))
        assertNull(install.phaseForWorkStates(listOf(WorkInfo.State.SUCCEEDED)))
    }

    @Test fun bundleDownloadShowsQueuedStateAndKeepsOneDurableJob() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure) {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
        val scheduler = context.getSystemService(android.app.job.JobScheduler::class.java)
        dev.chungjungsoo.gptmobile.data.voice.VoicePluginInstall.cancel(context)
        try {
            dev.chungjungsoo.gptmobile.data.voice.VoicePluginInstall.start(context)
            assertEquals("QUEUED", dev.chungjungsoo.gptmobile.data.voice.VoicePluginInstall.state.value.phase)
            dev.chungjungsoo.gptmobile.data.voice.VoicePluginInstall.start(context)
            assertEquals(1, scheduler.allPendingJobs.count { it.id == dev.chungjungsoo.gptmobile.data.voice.VoicePluginInstall.JOB_ID })
            val request = requireNotNull(requireNotNull(scheduler.getPendingJob(dev.chungjungsoo.gptmobile.data.voice.VoicePluginInstall.JOB_ID)).requiredNetwork)
            assertTrue(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            assertFalse(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
        } finally {
            dev.chungjungsoo.gptmobile.data.voice.VoicePluginInstall.cancel(context)
        }
        assertEquals("CANCELLED", dev.chungjungsoo.gptmobile.data.voice.VoicePluginInstall.state.value.phase)
        assertFalse(dev.chungjungsoo.gptmobile.data.voice.VoicePluginInstall.state.value.pending)
    }

    @Test fun missingQueuedJobIsReconciledWithoutAutomaticallySchedulingAnotherTransfer() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure) {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val install = dev.chungjungsoo.gptmobile.data.voice.VoicePluginInstall
        install.cancel(context)
        try {
            install.start(context)
            scheduler.cancel(install.JOB_ID)
            install.refresh(context)
            assertEquals("FAILED", install.state.value.phase)
            assertEquals("WORK_NOT_FOUND", install.state.value.failure)
            assertEquals("MISSING", install.state.value.pendingReason)
            assertTrue(scheduler.allPendingJobs.isEmpty())
        } finally {
            install.cancel(context)
        }
    }

    @Test fun retiredWorkerCallbacksCannotReplaceNewQueuedOrCompletedState() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure) {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val install = dev.chungjungsoo.gptmobile.data.voice.VoicePluginInstall
        install.cancel(context)
        try {
            install.start(context)
            val old = requireNotNull(requireNotNull(scheduler.getPendingJob(install.JOB_ID)).extras.getString(install.ATTEMPT))
            install.cancel(context)
            install.start(context)
            val current = requireNotNull(requireNotNull(scheduler.getPendingJob(install.JOB_ID)).extras.getString(install.ATTEMPT))
            install.observe("FAILED", "ASSET_FAILURE", owner = old)
            assertEquals("QUEUED", install.state.value.phase)
            install.observe("COMPLETED", owner = current)
            scheduler.cancel(install.JOB_ID)
            install.refresh(context)
            install.observe("WAITING_NETWORK", owner = old)
            assertEquals("COMPLETED", install.state.value.phase)
        } finally {
            install.cancel(context)
        }
    }

    @Test
    @Config(sdk = [33])
    fun retryWhileWorkManagerCancellationIsPendingCannotReadoptTheCancelledOwner() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val install = dev.chungjungsoo.gptmobile.data.voice.VoicePluginInstall
        val manager = mockk<WorkManager>()
        val enqueued = mockk<Operation>()
        val cancelling = mockk<Operation>()
        val cancelled = WorkFuture<Operation.State.SUCCESS>()
        val requests = mutableListOf<OneTimeWorkRequest>()
        var scheduled = emptyList<WorkInfo>()
        every { enqueued.result } returns WorkFuture<Operation.State.SUCCESS>().apply { complete(Operation.SUCCESS) }
        every { cancelling.result } returns cancelled
        every { manager.cancelUniqueWork(install.WORK) } returns enqueued
        every { manager.getWorkInfosForUniqueWork(install.WORK) } answers { WorkFuture<List<WorkInfo>>().apply { complete(scheduled) } }
        every { manager.enqueueUniqueWork(install.WORK, ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) } answers {
            val request = thirdArg<OneTimeWorkRequest>()
            requests += request
            val work = mockk<WorkInfo>()
            every { work.id } returns request.id
            every { work.tags } returns request.tags
            every { work.state } returns WorkInfo.State.RUNNING
            scheduled = listOf(work)
            enqueued
        }
        mockkObject(WorkManager.Companion)
        try {
            every { WorkManager.getInstance(context) } returns manager
            install.cancel(context)
            install.start(context)
            shadowOf(Looper.getMainLooper()).idle()
            val old = requireNotNull(requests.single().workSpec.input.getString(install.ATTEMPT))
            assertTrue(install.ownsAttempt(old))
            assertEquals("RUNNING", install.state.value.phase)

            // Android has accepted cancellation, but its database still reports
            // the old work as RUNNING when the user immediately retries.
            every { manager.cancelUniqueWork(install.WORK) } returns cancelling
            install.cancel(context)
            assertFalse(cancelled.isDone)
            install.start(context)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(install.ownsAttempt(old))
            install.observe("RUNNING", owner = old)
            assertEquals("CANCELLED", install.state.value.phase)
            assertEquals(1, requests.size)

            scheduled = emptyList()
            cancelled.complete(Operation.SUCCESS)
            install.start(context)
            shadowOf(Looper.getMainLooper()).idle()
            val current = requireNotNull(requests.last().workSpec.input.getString(install.ATTEMPT))
            assertEquals(2, requests.size)
            assertFalse(current == old)
            assertTrue(install.ownsAttempt(current))
            install.observe("FAILED", "ASSET_FAILURE", owner = old)
            assertEquals("RUNNING", install.state.value.phase)
        } finally {
            install.cancel(context)
            unmockkObject(WorkManager.Companion)
        }
    }

    @Test fun schedulerReasonsAreClosedObservedValues() {
        val install = dev.chungjungsoo.gptmobile.data.voice.VoicePluginInstall
        assertEquals("CONNECTIVITY", install.pendingReason(JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONNECTIVITY))
        assertEquals("EXECUTING", install.pendingReason(JobScheduler.PENDING_JOB_REASON_EXECUTING))
        assertEquals("MISSING", install.pendingReason(JobScheduler.PENDING_JOB_REASON_INVALID_JOB_ID))
        assertNull(install.pendingReason(Int.MAX_VALUE))
    }

    @Test fun anOldNotificationCancelActionCannotCancelTheReplacementDownload() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure) {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val install = dev.chungjungsoo.gptmobile.data.voice.VoicePluginInstall
        val receiver = dev.chungjungsoo.gptmobile.data.voice.VoicePluginCancelReceiver()
        install.cancel(context)
        try {
            install.start(context)
            val old = requireNotNull(requireNotNull(scheduler.getPendingJob(install.JOB_ID)).extras.getString(install.ATTEMPT))
            val oldAction = install.notificationCancelAction(context, old)
            install.cancel(context)
            install.start(context)
            val current = requireNotNull(requireNotNull(scheduler.getPendingJob(install.JOB_ID)).extras.getString(install.ATTEMPT))
            val currentAction = install.notificationCancelAction(context, current)
            assertTrue(oldAction.isImmutable)
            assertTrue(currentAction.isImmutable)
            assertFalse(oldAction == currentAction)

            receiver.onReceive(context, install.notificationCancelRequest(context, old))
            assertEquals("QUEUED", install.state.value.phase)
            assertEquals(current, requireNotNull(scheduler.getPendingJob(install.JOB_ID)).extras.getString(install.ATTEMPT))

            val mismatchedIdentity = install.notificationCancelRequest(context, old).putExtra(install.ATTEMPT, current)
            receiver.onReceive(context, mismatchedIdentity)
            assertEquals("QUEUED", install.state.value.phase)

            receiver.onReceive(context, install.notificationCancelRequest(context, current))
            assertEquals("CANCELLED", install.state.value.phase)
            assertNull(scheduler.getPendingJob(install.JOB_ID))
        } finally {
            install.cancel(context)
        }
    }

    @Test fun malformedFieldsDoNotDiscardValidChoicesOrGrantOnlineSpeech() {
        val value = readVoicePreferences(
            mapOf(
                "online" to "true", "captions" to false, "rate" to Float.NaN, "kokoro_threads" to "4",
                "language" to "en-CA", "review" to true, "voice" to 12, "automatic" to "false",
                "recognition_backend" to "UNKNOWN", "kokoro_voice" to "EMMA", "spoken_replies" to false
            )
        )
        assertFalse(value.online)
        assertFalse(value.captions)
        assertFalse(value.spokenReplies)
        assertEquals(1f, value.rate)
        assertEquals(2, value.kokoroThreads)
        assertEquals("en-CA", value.language)
        assertEquals("", value.voice)
        assertEquals(VoiceRecognitionBackend.LOCAL_MOONSHINE, value.recognitionBackend)
        assertEquals(KokoroVoice.EMMA, value.kokoroVoice)
        assertEquals(true, value.review)
    }

    @Test fun screenDisposalBeforeWriterRunsStillSavesLastSettings() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val views = ViewModelStore()
        try {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val preferences = context.getSharedPreferences("voice-writer-test", Context.MODE_PRIVATE)
            preferences.edit().clear().commit()
            val store = VoicePreferenceStore({ preferences }, backgroundScope)
            val model = VoicePreferencesViewModel(context, store)
            views.put("voice", model)
            model.save(VoicePreferences(spokenReplies = true, kokoroThreads = 2))
            val expected = VoicePreferences(spokenReplies = false, online = false, kokoroVoice = KokoroVoice.HEART, kokoroThreads = 4)
            model.save(expected)
            views.clear()
            runCurrent()
            assertEquals(expected, readVoicePreferences(preferences.all))
            assertEquals(expected, store.preferences.value)
            assertNull(store.saveError.value)
        } finally {
            views.clear()
            Dispatchers.resetMain()
        }
    }

    private class WorkFuture<T> : ListenableFuture<T> {
        private val future = CompletableFuture<T>()
        fun complete(value: T): Boolean = future.complete(value)
        override fun addListener(listener: Runnable, executor: Executor) {
            future.whenComplete { _, _ -> executor.execute(listener) }
        }
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = future.cancel(mayInterruptIfRunning)
        override fun isCancelled(): Boolean = future.isCancelled
        override fun isDone(): Boolean = future.isDone
        override fun get(): T = future.get()
        override fun get(timeout: Long, unit: TimeUnit): T = future.get(timeout, unit)
    }
}
