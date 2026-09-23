package dev.chungjungsoo.gptmobile.util

import android.app.NotificationChannel
import android.app.NotificationManager as AndroidNotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.presentation.ui.main.MainActivity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Custom notification manager for AI assistant notifications.
 * Features: deep-linking to conversations, custom icon, multi-pattern vibration, and tool call alerts.
 */
@Singleton
class NotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val notificationManager: AndroidNotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as AndroidNotificationManager

    init {
        createNotificationChannel(context)
    }

    /**
     * Shows a notification for AI assistant response with deep-linking support.
     */
    fun showAIResponseNotification(
        conversationId: String,
        messageId: String = "",
        title: String = "AI Assistant",
        content: String = "New message received"
    ) {
        val chatId = conversationId.toIntOrNull() ?: 0
        val targetMsgId = messageId.toIntOrNull() ?: -1

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (chatId > 0) {
                putExtra(EXTRA_CHAT_ROOM_ID, chatId)
            }
            if (targetMsgId > 0) {
                putExtra(EXTRA_TARGET_MESSAGE_ID, targetMsgId)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ai_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVibrate(VIBRATION_PATTERN)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
        triggerVibration(context)
    }

    /**
     * Shows a notification for tool call events.
     */
    fun showToolCallNotification(
        toolName: String,
        status: String = "Called"
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID + 1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ai_notification)
            .setContentTitle("Tool Call")
            .setContentText("$status: $toolName")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    /**
     * Dismisses the current AI notification.
     */
    fun dismissAIResponseNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    companion object {
        const val CHANNEL_ID = "ai_assistant_channel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_CHAT_ROOM_ID = "chatRoomId"
        const val EXTRA_TARGET_MESSAGE_ID = "targetMessageId"
        val VIBRATION_PATTERN = longArrayOf(0, 200, 100, 200)

        /**
         * Creates the notification channel for Android O+ compatibility.
         */
        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as AndroidNotificationManager
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "AI Assistant Notifications",
                    AndroidNotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications for AI assistant messages and tool execution"
                    enableVibration(true)
                    vibrationPattern = VIBRATION_PATTERN
                }
                manager.createNotificationChannel(channel)
            }
        }

        /**
         * Triggers multi-pattern vibration.
         */
        fun triggerVibration(context: Context) {
            runCatching {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vibratorManager?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
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
    }
}
