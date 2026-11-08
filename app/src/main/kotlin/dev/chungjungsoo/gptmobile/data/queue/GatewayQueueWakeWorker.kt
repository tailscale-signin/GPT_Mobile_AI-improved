package dev.chungjungsoo.gptmobile.data.queue

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

/** Android may defer this wake; the durable queue, rather than its timer, owns the work. */
@HiltWorker
class GatewayQueueWakeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val queue: DurablePromptQueue
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        dev.chungjungsoo.gptmobile.presentation.StartupRecoveryGate.await()
        queue.wake()
        // Give shared readiness/reconciliation a bounded observation window. The
        // coordinator retains its Android foreground-service admission checks.
        delay(4_000)
        if (!queue.hasGatewayWork()) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork(PERIODIC)
            return Result.success()
        }
        return if (runAttemptCount < 3) Result.retry() else Result.success()
    }

    companion object {
        private const val WAKE = "gateway-queue-network-wake"
        private const val PERIODIC = "gateway-queue-durable-reconciliation"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniqueWork(
                WAKE,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<GatewayQueueWakeWorker>().setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            )
            manager.enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<GatewayQueueWakeWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build()
            )
        }
    }
}
