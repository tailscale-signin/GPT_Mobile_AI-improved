package dev.chungjungsoo.gptmobile.data.agent.tool

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Small boundary so controller/restart tests exercise durable state without scheduling real work. */
open class GitHubEngineeringWorkQueue @Inject constructor(@ApplicationContext private val context: Context) {
    internal open fun enqueue(taskId: String) = GitHubEngineeringCiScheduler.enqueue(context, taskId)
    internal open fun cancel(taskId: String) = GitHubEngineeringCiScheduler.cancel(context, taskId)
}
