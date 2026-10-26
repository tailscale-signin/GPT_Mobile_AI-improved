package dev.chungjungsoo.gptmobile.data.agent.tool

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * CI monitoring entry point. No checks are marked passed without authenticated
 * check-run evidence; wiring the GitHub check client is a separate integration.
 */
internal class GitHubEngineeringCiWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val ledger = GitHubEngineeringRoomLedger(
            GitHubEngineeringDatabaseFactory.open(applicationContext),
        )
        val task = ledger.task(taskId) ?: return Result.failure()
        if (task.state != EngineeringState.CI_PENDING.name) return Result.success()
        val operations = ledger.operations(taskId)
        if (operations.none { it.stage == GitSagaStage.REF_UPDATED.name && it.commitSha == it.remoteRefSha }) {
            return Result.failure()
        }
        // Authentication and required-check configuration must be provided before CI can pass.
        return if (runAttemptCount >= MAX_UNVERIFIED_ATTEMPTS) Result.failure() else Result.retry()
    }

    companion object {
        const val KEY_TASK_ID = "engineering_task_id"
        private const val MAX_UNVERIFIED_ATTEMPTS = 48
    }
}
