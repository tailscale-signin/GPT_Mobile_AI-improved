package dev.chungjungsoo.gptmobile.data.agent

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class ProviderLimitResumeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val store: ProviderWaitStore,
    private val coordinator: AgentRunCoordinator
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val scope = inputData.getString("scope") ?: return Result.failure()
        if (!dev.chungjungsoo.gptmobile.presentation.service.AgentRunForegroundService.canStartNow()) return Result.retry()
        coordinator.resumeProviderWaits(store.readyActions(scope))
        return Result.success()
    }

    companion object {
        fun enqueue(context: Context, scope: String, retryAt: Long, append: Boolean = false) {
            val work = OneTimeWorkRequestBuilder<ProviderLimitResumeWorker>()
                .setInputData(workDataOf("scope" to scope))
                .setInitialDelay((retryAt - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag("provider-limit-waits").build()
            WorkManager.getInstance(context).enqueueUniqueWork("provider-limit:$scope", if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, work)
        }
    }
}
