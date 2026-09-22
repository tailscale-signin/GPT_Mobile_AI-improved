package dev.chungjungsoo.gptmobile.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.presentation.ui.main.MainActivity

class NotificationManager(private val context: Context) {
    
    companion object {
        private const val CHANNEL_ID = "ai_notifications"
        private const val CHANNEL_NAME = "AI Notifications"
        private const val PRIORITY = NotificationCompat.PRIORITY_HIGH
        
        private val VIBRATION_PATTERN = longArrayOf(0, 200, 100, 200)
        
        fun createNotificationChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "AI conversation notifications"
                        enableVibration(true)
                    }
                )
            }
        }
        
        fun buildOpenAppPendingIntent(conversationId: String): PendingIntent {
            val intent = Intent().setClass(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("conversation_id", conversationId)
            }
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
    
    fun showAIResponseNotification(conversationId: String, messageId: Long, messagePreview: String) {
        createNotificationChannel()
        
        val pendingIntent = buildOpenAppPendingIntent(conversationId)
        
        val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ai_notification)
            .setContentTitle("AI Response")
            .setContentText(messagePreview.ifBlank { "AI has generated a response" })
            .setPriority(PRIORITY)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVibrate(VIBRATION_PATTERN)
            .setContentIntent(pendingIntent)
        
        val notification = notificationBuilder.build()
        NotificationManagerCompat.from(context).notify(messageId.toInt(), notification)
    }
    
    fun showToolCallNotification(conversationId: String, messageId: Long, toolName: String, resultPreview: String) {
        createNotificationChannel()
        
        val pendingIntent = buildOpenAppPendingIntent(conversationId)
        
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
    
    fun cancelNotification(messageId: Long) {
        NotificationManagerCompat.from(context).cancel(messageId.toInt())
    }
}