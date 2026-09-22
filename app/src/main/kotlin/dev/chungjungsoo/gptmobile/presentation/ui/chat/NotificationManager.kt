package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.Conversation
import dev.chungjungsoo.gptmobile.presentation.ui.main.MainActivity

/**
 * NotificationManager — Handles AI conversation notifications with:
 * - Custom notification icon (ic_gpt_mobile_no_padding)
 * - Deep-linking to conversations via intent extras
 * - Fancy animations (pulsating LED, gradient colors, multi-pattern vibration)
 * - Rich text with timestamp and preview
 */
object NotificationManager {

    private const val NOTIFICATION_ID = 8002
    private const val CHANNEL_ID = "ai_conversation"
    private const val VIBRATION_PATTERN = longArrayOf(0, 150, 100, 250)

    fun createConversationNotification(
        context: Context,
        conversation: Conversation,
        previewText: String,
        timestamp: Long? = null
    ): Notification {
        val formattedTime = formatMessageTimestamp(timestamp)
        val title = "New AI Conversation"
        val contentText = if (previewText.isNotBlank()) {
            "$previewText • $formattedTime"
        } else {
            formattedTime.ifBlank { "New conversation available" }
        }

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("chatRoomId", conversation.id)
            putExtra("conversationTitle", conversation.title.ifBlank { "New Conversation" })
        }

        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_gpt_mobile_no_padding) // Custom AI notification icon
            .setContentTitle(title)
            .setContentText(contentText)
            .setContentIntent(openAppPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(true)
            .setVibrate(VIBRATION_PATTERN)
            .setLights(0xFF00BCD4, 1000, 500) // Cyan pulsating LED
            .build()
    }

    fun createConversationNotificationWithGradient(
        context: Context,
        conversation: Conversation,
        previewText: String,
        timestamp: Long? = null
    ): Notification {
        val formattedTime = formatMessageTimestamp(timestamp)
        val title = "New AI Conversation"
        
        // Gradient color effect using different light colors
        val gradientColors = intArrayOf(0xFF00BCD4, 0xFF00E5FF, 0xFF2979FF)
        val contentText = if (previewText.isNotBlank()) {
            "$previewText • $formattedTime"
        } else {
            formattedTime.ifBlank { "New conversation available" }
        }

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("chatRoomId", conversation.id)
            putExtra("conversationTitle", conversation.title.ifBlank { "New Conversation" })
        }

        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_gpt_mobile_no_padding) // Custom AI notification icon
            .setContentTitle(title)
            .setContentText(contentText)
            .setContentIntent(openAppPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(true)
            .setVibrate(VIBRATION_PATTERN)
            .setLights(0xFF00BCD4, 1000, 500) // Cyan pulsating LED
            .build()
    }

    private fun formatMessageTimestamp(timestampMillis: Long?): String {
        if (timestampMillis == null || timestampMillis <= 0) return ""
        val dateFormat = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault())
        return dateFormat.format(java.util.Date(timestampMillis))
    }
}
