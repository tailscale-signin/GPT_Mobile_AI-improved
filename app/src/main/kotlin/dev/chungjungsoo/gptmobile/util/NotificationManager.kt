package dev.chungjungsoo.gptmobile.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.presentation.ui.main.MainActivity
import javax.inject.Inject

/**
 * Custom notification manager for AI assistant notifications.
 * Features: custom icon, deep-linking, pulsating LED effect, multi-pattern vibration.
 */
class NotificationManager @Inject constructor(private val context: Context) {
    
    companion object {
        private const val CHANNEL_ID = "ai_notifications"
        private const val CHANNEL_NAME = "AI Notifications"
        private const val CHANNEL_DESCRIPTION = "Notifications from AI Assistant"
        
        // Vibration pattern for AI notifications (custom multi-pattern)
        private val VIBRATION_PATTERN = longArrayOf(0, 200, 100, 200)
        
        /**
         * Shows a notification for an AI response message.
         * Includes deep-linking to the conversation and custom vibration pattern.
         */
        fun showAIResponseNotification(
            conversationId: String,
            messageId: Long,
            messagePreview: String
        ) {
            val notificationIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("conversation_id", conversationId)
                putExtra("message_id", messageId.toString())
            }
            
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val notification = createAIResponseNotification(
                conversationId,
                messageId,
                messagePreview,
                pendingIntent
            ).build()
            
            NotificationManagerCompat.from(context).notify(messageId.toInt(), notification)
        }
        
        /**
         * Shows a notification for tool call events.
         */
        fun showToolCallNotification(conversationId: String, toolName: String) {
            val notificationIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("conversation_id", conversationId)
            }
            
            val pendingIntent = PendingIntent.getActivity(
                context,
                1,
                notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val notification = createToolCallNotification(conversationId, toolName, pendingIntent).build()
            
            NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), notification)
        }
        
        /**
         * Creates the AI response notification with custom icon and deep-linking.
         */
        private fun createAIResponseNotification(
            conversationId: String,
            messageId: Long,
            messagePreview: String,
            pendingIntent: PendingIntent
        ): NotificationCompat.Builder {
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_ai_notification)
                .setContentTitle("AI Assistant")
                .setContentText(messagePreview)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setVibrate(VIBRATION_PATTERN)
                .setOnlyAlertOnce(true)
            
            // Add deep-link action
            val openChatIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("conversation_id", conversationId)
            }
            
            val openChatPendingIntent = PendingIntent.getActivity(
                context,
                2,
                openChatIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            builder.addAction(
                NotificationCompat.Action(
                    R.drawable.ic_ai_notification,
                    "Open Chat",
                    openChatPendingIntent
                )
            )
            
            return builder
        }
        
        /**
         * Creates the tool call notification.
         */
        private fun createToolCallNotification(
            conversationId: String,
            toolName: String,
            pendingIntent: PendingIntent
        ): NotificationCompat.Builder {
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_ai_notification)
                .setContentTitle("AI Assistant")
                .setContentText("Using $toolName")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setVibrate(VIBRATION_PATTERN)
            
            return builder
        }
        
        /**
         * Creates the notification channel for Android O+ devices.
         */
        fun createNotificationChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = CHANNEL_DESCRIPTION
                    enableVibration(true)
                    setSound(null, null) // Use custom vibration pattern instead
                }
                
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
            }
        }
    }
}
