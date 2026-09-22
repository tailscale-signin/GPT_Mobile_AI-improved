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
import dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatViewModel
import dev.chungjungsoo.gptmobile.presentation.ui.main.MainActivity

/**
 * Fancy notification manager with deep-linking support for AI chat conversations.
 * Shows notifications with custom icons when agent runs complete, and handles clicks
 * to navigate directly to the relevant conversation in the app.
 */
class NotificationManager(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "ai_chat_notifications"
        private const val CHANNEL_NAME = "AI Chat Notifications"
        private const val NOTIFICATION_ID_BASE = 1000
    }

    init {
        createNotificationChannel()
    }

    /**
     * Creates the notification channel with custom settings for AI chat notifications.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for AI chat agent runs"
                enableVibration(true)
                enableLights(true)
                lightColor = 0xFF00BCD4.toInt() // Cyan color matching app theme
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
            }

            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    /**
     * Shows a fancy notification for an AI agent run completion.
     *
     * @param conversationId The ID of the conversation to deep-link to
     * @param title The notification title
     * @param text The notification message
     * @param isCompleted Whether the agent run completed successfully
     */
    fun showAgentRunNotification(
        conversationId: Int,
        title: String = "AI Agent Complete",
        text: String = "Your AI agent has finished processing.",
        isCompleted: Boolean = true
    ) {
        val notificationId = NOTIFICATION_ID_BASE + conversationId

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("chatRoomId", conversationId)
            putExtra("notificationFrom", "agent_run")
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            conversationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ai_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setOngoing(!isCompleted)

        // Add fancy visual effects
        addFancyEffects(notificationBuilder, isCompleted)

        // Add notification actions for quick responses
        if (isCompleted) {
            addNotificationActions(notificationBuilder, conversationId)
        }

        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.notify(notificationId, notificationBuilder.build())
    }

    /**
     * Adds fancy visual effects to the notification builder.
     */
    private fun addFancyEffects(builder: NotificationCompat.Builder, isCompleted: Boolean) {
        // Set a custom large icon if available
        builder.setLargeIcon(getNotificationLargeIcon(isCompleted))

        // Add LED light for ongoing notifications
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            builder.setLights(0xFF00BCD4.toInt(), 1000, 500)
        }

        // Add badge count indicator
        builder.setBadgeIconType(NotificationCompat.BADGE_ICON_LARGE)
    }

    /**
     * Adds quick action buttons to the notification.
     */
    private fun addNotificationActions(
        builder: NotificationCompat.Builder,
        conversationId: Int
    ) {
        val openChatIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("chatRoomId", conversationId)
            putExtra("notificationFrom", "agent_run")
        }

        val openChatPendingIntent = PendingIntent.getActivity(
            context,
            conversationId + 1,
            openChatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val continueIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("chatRoomId", conversationId)
            putExtra("notificationFrom", "agent_run")
            putExtra("continueLastRun", true)
        }

        val continuePendingIntent = PendingIntent.getActivity(
            context,
            conversationId + 2,
            continueIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        builder.addAction(
            R.drawable.ic_round_arrow_right,
            "Open Chat",
            openChatPendingIntent
        )

        builder.addAction(
            R.drawable.ic_send,
            "Continue",
            continuePendingIntent
        )
    }

    /**
     * Gets a custom large icon for the notification based on completion status.
     */
    private fun getNotificationLargeIcon(isCompleted: Boolean): android.graphics.Bitmap? {
        // Return null to use default, or implement custom bitmap generation
        return null
    }

    /**
     * Cancels all notifications for a specific conversation.
     */
    fun cancelConversationNotifications(conversationId: Int) {
        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.cancel(NOTIFICATION_ID_BASE + conversationId)
    }

    /**
     * Cancels all AI chat notifications.
     */
    fun cancelAllNotifications() {
        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.cancelAll()
    }
}
