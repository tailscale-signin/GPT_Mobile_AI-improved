package dev.chungjungsoo.gptmobile.data.github

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Polls durable gateway jobs after the app closes. WorkManager resumes after process death/reboot. */
@HiltWorker
class GatewayEngineeringSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val engineering: GatewayEngineeringRepository
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        dev.chungjungsoo.gptmobile.presentation.StartupRecoveryGate.await()
        return if (engineering.syncAll()) Result.success() else Result.retry()
    }
}
