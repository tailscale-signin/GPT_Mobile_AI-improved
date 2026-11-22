package dev.chungjungsoo.gptmobile.data.github

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

/** Explicit, non-exported immutable PendingIntents enqueue one durable answer attempt. */
class GitHubDecisionActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val segments = intent.data?.pathSegments.orEmpty()
        if (intent.action != ACTION || intent.data?.scheme != SCHEME || intent.data?.authority != "answer" || segments.size != 3) return
        val (requestId, binding, index) = segments
        val option = index.toIntOrNull() ?: return
        if (!DIGEST.matches(requestId) || !DIGEST.matches(binding) || option !in 0..3) return
        val pending = goAsync()
        try {
            val request = OneTimeWorkRequestBuilder<GitHubDecisionActionWorker>()
                .setInputData(workDataOf(REQUEST to requestId, BINDING to binding, OPTION to option))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("github-decision:$requestId:$binding", ExistingWorkPolicy.KEEP, request)
                .result.addListener({ pending.finish() }, ContextCompat.getMainExecutor(context))
        } catch (_: Exception) {
            // The notification remains available if Android cannot schedule the save.
            pending.finish()
        }
    }

    companion object {
        private const val ACTION = "dev.chungjungsoo.gptmobile.GITHUB_DECISION_ANSWER"
        private const val SCHEME = "gptmobile-github-decision"
        internal const val REQUEST = "request"
        internal const val BINDING = "binding"
        internal const val OPTION = "option"
        private val DIGEST = Regex("[a-f0-9]{64}")

        fun intent(context: Context, action: GitHubDecisionNotificationAction): Intent = Intent(context, GitHubDecisionActionReceiver::class.java).apply {
            this.action = ACTION
            data = Uri.Builder().scheme(SCHEME).authority("answer")
                .appendPath(action.requestId).appendPath(action.binding).appendPath(action.option.toString()).build()
        }
    }
}
