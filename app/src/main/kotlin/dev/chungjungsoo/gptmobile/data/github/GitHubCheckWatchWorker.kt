package dev.chungjungsoo.gptmobile.data.github

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class GitHubCheckWatchWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val watches: GitHubCheckWatchRepository
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("watch_id") ?: return Result.failure()
        return try {
            if (!watches.poll(id, runAttemptCount)) watches.scheduleNext(id)
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Only failed persistence/scheduling uses WorkManager retry. Healthy CI polls schedule a new observation.
            Result.retry()
        }
    }
}
