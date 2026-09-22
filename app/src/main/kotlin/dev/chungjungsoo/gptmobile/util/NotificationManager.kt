package dev.chungjungsoo.gptmobile.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.presentation.ui.main.MainActivity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Custom notification manager for AI responses with deep-linking and animations.
 */
@Singleton
class NotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val CHANNEL_ID = "ai_notifications"
        private const val NOTIFICATION_ID_BASE = 8000
        private const val VIBRATION_PATTERN = longArrayOf(0, 200, 100, 200)

        /**
         * Shows a notification for an AI response with deep-linking to the conversation.
         */
        fun showAIResponseNotification(
            context: Context,
            conversationId: String,
            messageId: Long,
            messagePreview: String
        ) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

            // Create notification with custom icon and deep-linking
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("conversation_id", conversationId)
                putExtra("message_id", messageId.toString())
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_ai_notification) // Custom AI notification icon
                .setContentTitle("AI Response")
                .setContentText(messagePreview.take(100))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            notificationManager.notify(NOTIFICATION_ID_BASE, notification)

            // Trigger vibration pattern
            triggerVibration(context)
        }

        /**
         * Shows a notification for tool calls.
         */
        fun showToolCallNotification(
            context: Context,
            conversationId: String,
            toolName: String
        ) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("conversation_id", conversationId)
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                1,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_ai_notification) // Custom AI notification icon
                .setContentTitle("Tool Call")
                .setContentText("$toolName executed")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            notificationManager.notify(NOTIFICATION_ID_BASE + 1, notification)
        }

        /**
         * Triggers multi-pattern vibration.
         */
        private fun triggerVibration(context: Context) {
            runCatching {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                    vibratorManager?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                }

                vibrator?.let { vib ->
                    if (vib.hasVibrator()) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val effect = VibrationEffect.createWaveform(VIBRATION_PATTERN, -1)
                            vib.vibrate(effect)
                        } else {
                            @Suppress("DEPRECATION")
                            vib.vibrate(VIBRATION_PATTERN, -1)
                        }
                    }
                }
            }
        }

        /**
         * Creates the notification channel for AI notifications.
         */
        fun createNotificationChannel(context: Context) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notificationManager.createNotificationChannel(
                    android.app.NotificationChannel(
                        CHANNEL_ID,
                        "AI Notifications",
                        android.app.NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
    }
}
