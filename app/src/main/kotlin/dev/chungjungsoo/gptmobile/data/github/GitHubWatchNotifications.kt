package dev.chungjungsoo.gptmobile.data.github

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.app.NotificationCompat
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.presentation.ui.main.MainActivity

/** Observed check state survives the generating service; the system chronometer needs no timer loop. */
internal object GitHubWatchNotifications {
    private const val CHANNEL = "github_live_checks"
    fun publish(context: Context, watch: GitHubCheckWatch) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "GitHub live status", NotificationManager.IMPORTANCE_LOW))
        val color = when (watch.status) {
            "PASSED", "MERGED" -> 0xFF55C99A.toInt()
            "FAILED", "BLOCKED" -> 0xFFEF7979.toInt()
            "RUNNING", "RETRYING", "NONE", "UNKNOWN" -> 0xFFE8BC58.toInt()
            else -> 0xFF9AA9B2.toInt()
        }
        val light = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        Canvas(light).drawCircle(32f, 32f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        val id = watch.id.hashCode()
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_CHAT_ROOM_ID, watch.chatId)
                putExtra(MainActivity.EXTRA_NOTIFICATION_EVENT_ID, watch.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = watch.pullNumber?.let { "${if (watch.draft) "Draft PR" else "PR"} #$it" } ?: "Workflow ${watch.runId}"
        val state = watch.status.lowercase().replace('_', ' ')
        val text = "${watch.branch.ifBlank { watch.headSha.take(12) }} · $state"
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_github_outline).setLargeIcon(light).setColor(color)
            .setContentTitle("${watch.repo} · $title").setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n${watch.detail}"))
            .setWhen(watch.startedAt).setShowWhen(true).setUsesChronometer(!watch.terminal)
            .setOngoing(!watch.terminal).setAutoCancel(watch.terminal).setOnlyAlertOnce(true)
            .setContentIntent(open).setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (!watch.terminal) builder.setProgress(0, 0, true)
        if (watch.terminal) builder.setSubText("Watch total ${githubElapsedTime(watch.startedAt, watch.updatedAt)}")
        runCatching { manager.notify(id, builder.build()) }
    }
}
