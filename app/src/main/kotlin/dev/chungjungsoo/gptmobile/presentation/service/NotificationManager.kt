package dev.chungjungsoo.gptmobile.presentation.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.presentation.ui.main.MainActivity

/**
 * Fancy notification manager with custom icons and deep linking to conversations.
 */
class NotificationManager(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "ai_notifications"
        private const val CHANNEL_NAME = "AI Notifications"
        private const val NOTIFICATION_ID_BASE = 1000
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for AI responses and tool calls"
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Show a fancy notification with custom icon and deep link to conversation.
     */
    fun showAIResponseNotification(
        conversationId: String,
        messageId: Long,
        messagePreview: String,
        fullMessage: String = ""
    ) {
        val notificationBuilder = createFancyNotificationBuilder(conversationId, messageId)
            .setContentTitle("🤖 AI Response")
            .setContentText(messagePreview)
            .setSmallIcon(R.drawable.ic_ai_notification)
            .setLargeIcon(getAIIconBitmap())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOngoing(false)

        // Add deep link to conversation
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(
            "gptmobile://conversation/$conversationId"
        ))
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        intent.putExtra("message_id", messageId)
        intent.putExtra("full_message", fullMessage)

        val pendingIntent = PendingIntent.getActivity(
            context,
            (NOTIFICATION_ID_BASE + conversationId.hashCode()).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        notificationBuilder.setContentIntent(pendingIntent)

        // Add progress indicator for ongoing generation
        if (fullMessage.isNotEmpty()) {
            notificationBuilder.setProgress(100, 45, false)
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.notify(messageId.toInt(), notificationBuilder.build())
    }

    /**
     * Show a notification for tool call completion.
     */
    fun showToolCallNotification(
        conversationId: String,
        messageId: Long,
        toolName: String,
        resultPreview: String
    ) {
        val notificationBuilder = createFancyNotificationBuilder(conversationId, messageId)
            .setContentTitle("🔧 Tool Execution")
            .setContentText("$toolName completed successfully")
            .setSmallIcon(R.drawable.ic_ai_notification)
            .setLargeIcon(getAIIconBitmap())
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(
            "gptmobile://conversation/$conversationId"
        ))
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        intent.putExtra("message_id", messageId)

        val pendingIntent = PendingIntent.getActivity(
            context,
            (NOTIFICATION_ID_BASE + conversationId.hashCode()).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        notificationBuilder.setContentIntent(pendingIntent)

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.notify(messageId.toInt(), notificationBuilder.build())
    }

    private fun createFancyNotificationBuilder(
        conversationId: String,
        messageId: Long
    ): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ai_notification)
            .setLargeIcon(getAIIconBitmap())
    }

    private fun getAIIconBitmap(): android.graphics.Bitmap? {
        // Use the existing app icon as fallback
        return BitmapFactory.decodeResource(
            context.resources,
            R.mipmap.ic_gpt_mobile
        )
    }
}
