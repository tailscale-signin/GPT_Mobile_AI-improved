package dev.chungjungsoo.gptmobile.presentation.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatViewModel
import dev.chungjungsoo.gptmobile.presentation.ui.main.MainActivity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_ID = "gpt_mobile_ai_notifications"
        const val NOTIFICATION_ID_BASE = 1000
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for AI agent runs"
                enableVibration(true)
                enableLights(true)
                lightColor = 0xFF00E5FF.toInt()
                vibrationPattern = longVibrationPattern
            }

            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun longVibrationPattern: LongArray = longArrayOf(0, 500, 200, 500, 200, 500)

    /**
     * Creates a fancy notification with icon and deep-link to the conversation.
     *
     * @param runId The unique identifier of the agent run
     * @param chatRoomTitle The title of the chat room
     * @param message The notification message
     */
    fun showAgentRunNotification(
        runId: String,
        chatRoomTitle: String,
        message: String
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("chatRoomId", ChatViewModel.getChatRoomIdFromRunId(runId))
            putExtra("chatRoomTitle", chatRoomTitle)
            action = "OPEN_CHAT_FROM_NOTIFICATION"
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            runId.hashCode() and 0x7FFFFFFF,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ai_notification_large)
            .setContentTitle("AI Agent: $chatRoomTitle")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_ai_notification,
                "Open Chat",
                pendingIntent
            )

        // Add fancy visual effects
        notificationBuilder.setStyle(
            androidx.core.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0)
        )

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager?.notify(runId.hashCode() and 0x7FFFFFFF, notificationBuilder.build())
    }

    /**
     * Cancels a specific notification by run ID.
     */
    fun cancelNotification(runId: String) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager?.cancel(runId.hashCode() and 0x7FFFFFFF)
    }
}

// Helper function to extract chat room ID from run ID (simplified - in production, use proper mapping)
fun ChatViewModel.getChatRoomIdFromRunId(runId: String): Int {
    // This is a simplified approach. In production, you'd want to store the chat room ID
    // with each agent run and retrieve it properly.
    return 0
}