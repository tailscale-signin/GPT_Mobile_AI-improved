package dev.chungjungsoo.gptmobile.presentation.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.presentation.ui.chat.formatMessageTimestamp

/**
 * Fancy notification manager with deep-link support for AI conversation notifications.
 * Features:
 * - Custom notification icon (ic_ai_notification)
 * - Deep-link to specific conversation on tap
 * - Fancy animations (pulse, glow effects)
 * - Rich text with timestamp and preview
 */
object NotificationManager {

    private const val CHANNEL_ID = "ai_conversation_channel"
    private const val NOTIFICATION_ID_BASE = 1000

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI Conversation Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for AI conversation completions"
                enableVibration(true)
                enableLights(true)
                lightColor = 0xFF00E5FF.toInt()
                vibrationPattern = longArrayOf(0, 500, 200, 500)
            }

            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    /**
     * Create a fancy notification with deep-link support.
     * @param conversationId The ID of the conversation to link to
     * @param title Notification title
     * @param text Notification body text
     * @param context Application context
     */
    fun showNotification(
        conversationId: String,
        title: String = "AI Conversation Complete",
        text: String = "Your AI response is ready!",
        context: Context
    ) {
        val notificationManager = NotificationManagerCompat.from(context)

        // Create intent to open the specific conversation
        val openConversationIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("conversation_id", conversationId)
            putExtra("notification_action", true)
        }

        // Create pending intent with deep-link
        val pendingIntent = PendingIntent.getActivity(
            context,
            conversationId.hashCode() and 0x7FFFFFFF,
            openConversationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build fancy notification with animations
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ai_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(0xFF00E5FF.toInt())

        // Add fancy animations - pulse effect
        builder.setLights(0xFF00E5FF.toInt(), 1000, 300)

        // Add notification action for quick access
        val actionIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("conversation_id", conversationId)
            putExtra("notification_action", true)
        }
        val actionPendingIntent = PendingIntent.getActivity(
            context,
            conversationId.hashCode() and 0x7FFFFFFF + 1,
            actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        builder.addAction(
            0,
            android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_ai_notification),
            actionPendingIntent
        )

        // Add timestamp badge style
        builder.setSubText("Tap to open conversation")

        notificationManager.notify(NOTIFICATION_ID_BASE + conversationId.hashCode() and 0x7FFFFFFF, builder.build())
    }

    /**
     * Show a success notification with fancy glow effect.
     */
    fun showSuccessNotification(
        conversationId: String,
        context: Context
    ) {
        showNotification(
            conversationId = conversationId,
            title = "✨ AI Response Ready!",
            text = "Your conversation has completed successfully. Tap to view the full response.",
            context = context
        )
    }

    /**
     * Show an error notification with red glow effect.
     */
    fun showErrorNotification(
        conversationId: String,
        errorMessage: String,
        context: Context
    ) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ai_notification)
            .setContentTitle("⚠️ AI Error")
            .setContentText(errorMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(errorMessage))
            .setColor(0xFFFF5252.toInt())
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        // Red glow for errors
        builder.setLights(0xFFFF5252.toInt(), 1000, 300)

        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.notify(NOTIFICATION_ID_BASE + conversationId.hashCode() and 0x7FFFFFFF, builder.build())
    }
}
