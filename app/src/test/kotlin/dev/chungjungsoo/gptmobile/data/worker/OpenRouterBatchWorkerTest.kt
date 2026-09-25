package dev.chungjungsoo.gptmobile.data.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.chungjungsoo.gptmobile.data.database.dao.OpenRouterBatchCacheDao
import dev.chungjungsoo.gptmobile.data.repository.OpenRouterSettingsRepository
import dev.chungjungsoo.gptmobile.domain.model.OpenRouterSettings
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OpenRouterBatchWorkerTest {

    private lateinit var context: Context
    private val openRouterSettingsRepository = mockk<OpenRouterSettingsRepository>()
    private val openRouterBatchCacheDao = mockk<OpenRouterBatchCacheDao>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        coEvery { openRouterBatchCacheDao.deleteExpired(any()) } returns 0
    }

    @Test
    fun `doWork returns failure when prompt is missing or blank`() = runBlocking {
        val workerParams = mockk<WorkerParameters>()
        coEvery { workerParams.inputData } returns workDataOf(
            OpenRouterBatchWorker.KEY_PROMPT to ""
        )
        coEvery { workerParams.runAttemptCount } returns 0

        val worker = OpenRouterBatchWorker(
            context = context,
            params = workerParams,
            openRouterSettingsRepository = openRouterSettingsRepository,
            openRouterBatchCacheDao = openRouterBatchCacheDao
        )

        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result.Failure)
    }

    @Test
    fun `doWork returns failure when API key is blank`() = runBlocking {
        val workerParams = mockk<WorkerParameters>()
        coEvery { workerParams.inputData } returns workDataOf(
            OpenRouterBatchWorker.KEY_PROMPT to "Explain quantum computing",
            OpenRouterBatchWorker.KEY_API_KEY to ""
        )
        coEvery { workerParams.runAttemptCount } returns 0
        coEvery { openRouterSettingsRepository.loadSettings() } returns OpenRouterSettings(apiKey = "")

        val worker = OpenRouterBatchWorker(
            context = context,
            params = workerParams,
            openRouterSettingsRepository = openRouterSettingsRepository,
            openRouterBatchCacheDao = openRouterBatchCacheDao
        )

        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result.Failure)
    }

    @Test
    fun `enqueueBatchRequest enqueues WorkRequest without throwing`() {
        val manager = mockk<androidx.work.WorkManager>(relaxed = true)
        val request = io.mockk.slot<androidx.work.OneTimeWorkRequest>()
        io.mockk.mockkStatic(androidx.work.WorkManager::class)
        try {
            io.mockk.every { androidx.work.WorkManager.getInstance(context) } returns manager
            OpenRouterBatchWorker.enqueueBatchRequest(
                context = context,
                requestId = "req-12345",
                prompt = "Test prompt",
                apiKey = "sk-or-dummy-key"
            )
            io.mockk.verify { manager.enqueue(capture(request)) }
            org.junit.Assert.assertEquals("req-12345", request.captured.workSpec.input.getString(OpenRouterBatchWorker.KEY_REQUEST_ID))
            org.junit.Assert.assertEquals(androidx.work.NetworkType.CONNECTED, request.captured.workSpec.constraints.requiredNetworkType)
        } finally {
            io.mockk.unmockkStatic(androidx.work.WorkManager::class)
        }
    }
}
