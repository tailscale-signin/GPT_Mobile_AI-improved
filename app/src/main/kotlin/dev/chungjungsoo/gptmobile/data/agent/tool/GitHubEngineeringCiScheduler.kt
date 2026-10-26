package dev.chungjungsoo.gptmobile.data.agent.tool

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

internal object GitHubEngineeringCiScheduler {
    fun enqueue(context: Context, taskId: String) {
        require(taskId.isNotBlank())
        require(taskId.length <= 128)
        val request = OneTimeWorkRequestBuilder<GitHubEngineeringCiWorker>()
            .setInputData(Data.Builder().putString(GitHubEngineeringCiWorker.KEY_TASK_ID, taskId).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("engineering-ci")
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "engineering-ci-$taskId",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(context: Context, taskId: String) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork("engineering-ci-$taskId")
    }
}
