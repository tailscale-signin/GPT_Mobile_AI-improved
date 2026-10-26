package dev.chungjungsoo.gptmobile.data.agent.tool

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** The persisted CI deadline survives WorkManager rescheduling and process death. */
@HiltWorker
class GitHubEngineeringCiWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted parameters: WorkerParameters,
    private val controller: GitHubEngineeringController
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        dev.chungjungsoo.gptmobile.presentation.StartupRecoveryGate.await()
        if (inputData.getBoolean(KEY_RECOVER, false)) {
            controller.recover()
            return Result.success()
        }
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        return when (controller.pollCi(taskId)) {
            EngineeringWorkerOutcome.VERIFIED -> Result.success()
            EngineeringWorkerOutcome.CANCELLED -> Result.failure(androidx.work.workDataOf("outcome" to "cancelled"))
            EngineeringWorkerOutcome.INVALID -> Result.failure()
            EngineeringWorkerOutcome.RETRY -> Result.retry()
        }
    }
    companion object {
        const val KEY_TASK_ID = "engineering_task_id"
        const val KEY_RECOVER = "engineering_recover"
    }
}
