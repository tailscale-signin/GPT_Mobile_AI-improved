package dev.chungjungsoo.gptmobile.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.chungjungsoo.gptmobile.R

/**
 * Notification manager for AI chatbot app.
 * Handles notifications with deep linking to specific conversations.
 */
class NotificationManager(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "ai_notifications"
        private const val CHANNEL_NAME = "AI Notifications"
        private const val PRIORITY = NotificationCompat.PRIORITY_HIGH
        private const val VIBRATION_PATTERN = VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
    }

    /**
     * Creates the notification channel if it doesn't exist (Android 8.0+)
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = "Notifications for AI responses and tool calls"
                enableVibration(true)
                setShowBadge(false)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Shows a notification for AI response.
     *
     * @param conversationId The ID of the conversation to link to
     * @param messageId The unique message ID
     * @param messagePreview Short preview text for the notification
     * @param fullMessage Full message content (not shown in notification)
     */
    fun showAIResponseNotification(
        conversationId: String,
        messageId: Long,
        messagePreview: String,
        fullMessage: String = ""
    ) {
        createNotificationChannel()

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("gptmobile://conversation/$conversationId"))
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        intent.putExtra("message_id", messageId)
        intent.putExtra("full_message", fullMessage)

        val pendingIntent = PendingIntent.getActivity(
            context,
            messageId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ai_notification)
            .setContentTitle("🤖 AI Response")
            .setContentText(messagePreview.ifBlank { "New message in your conversation" })
            .setPriority(PRIORITY)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVibrate(VIBRATION_PATTERN)
            .setContentIntent(pendingIntent)
            .setOngoing(false)

        // Add progress indicator for ongoing generation
        if (fullMessage.isNotEmpty()) {
            notificationBuilder.setProgress(100, 45, false)
        }

        val notification = notificationBuilder.build()
        NotificationManagerCompat.from(context).notify(messageId.toInt(), notification)
    }

    /**
     * Shows a notification for tool call.
     *
     * @param conversationId The ID of the conversation to link to
     * @param messageId The unique message ID
     * @param toolName Name of the tool being called
     * @param resultPreview Short preview of the tool result
     */
    fun showToolCallNotification(
        conversationId: String,
        messageId: Long,
        toolName: String,
        resultPreview: String = ""
    ) {
        createNotificationChannel()

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("gptmobile://conversation/$conversationId"))
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        intent.putExtra("message_id", messageId)
        intent.putExtra("tool_name", toolName)

        val pendingIntent = PendingIntent.getActivity(
            context,
            messageId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ai_notification)
            .setContentTitle("🔧 Tool Call: $toolName")
            .setContentText(resultPreview.ifBlank { "AI is using a tool" })
            .setPriority(PRIORITY)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVibrate(VIBRATION_PATTERN)
            .setContentIntent(pendingIntent)

        val notification = notificationBuilder.build()
        NotificationManagerCompat.from(context).notify(messageId.toInt(), notification)
    }

    /**
     * Cancels a notification by message ID.
     */
    fun cancelNotification(messageId: Long) {
        NotificationManagerCompat.from(context).cancel(messageId.toInt())
    }
}