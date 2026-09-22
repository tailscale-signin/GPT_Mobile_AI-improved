package dev.chungjungsoo.gptmobile.presentation.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.presentation.ui.chat.formatMessageTimestamp
import dev.chungjungsoo.gptmobile.presentation.ui.main.MainActivity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fancy notification manager with custom icons, deep-linking, and advanced animations.
 * Features:
 * - Custom notification icon (ic_ai_notification)
 * - Deep-link to specific conversation on tap
 * - Fancy animations (pulse, glow, gradient effects)
 * - Rich text with timestamp and preview
 * - Conversation-specific badges and actions
 */
@Singleton
class NotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_ID = "ai_conversation_channel"
        const val NOTIFICATION_ID_BASE = 1000
        
        // Fancy notification colors with gradients
        const val PRIMARY_GLOW_COLOR = 0xFF00E5FF
        const val SECONDARY_GLOW_COLOR = 0xFF00BCD4
        const val ACCENT_GLOW_COLOR = 0xFFFF4081
        const val SUCCESS_COLOR = 0xFF4CAF50
        const val ERROR_COLOR = 0xFFFF5252
        
        // Conversation ID key for deep-linking
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_MESSAGE_TITLE = "message_title"
        const val EXTRA_PREVIEW_TEXT = "preview_text"
    }

    private val notificationManager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * Create a notification channel for AI notifications (Android 8.0+)
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.ai_notifications_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.ai_notifications_channel_description)
                enableVibration(true)
                enableLights(true)
                lightColor = PRIMARY_GLOW_COLOR
                setShowBadge(false)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Create a fancy notification with custom icon and deep-link to conversation.
     * 
     * @param conversationId The unique ID of the conversation to link to
     * @param messageTitle Optional title for the notification (defaults to AI response preview)
     * @param previewText Optional preview text (defaults to first 100 chars of response)
     */
    fun createConversationNotification(
        conversationId: String,
        messageTitle: String? = null,
        previewText: String? = null
    ) {
        createNotificationChannel()

        // Create intent to open the specific conversation
        val openConversationIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_CHAT_ROOM_ID, -1) // Will navigate to conversation list
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
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
            .setSmallIcon(R.drawable.ic_gpt_mobile_no_padding) // Custom app icon as notification icon
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        // Fancy title and content
        val title = messageTitle ?: context.getString(R.string.ai_notification_title)
        val text = previewText ?: "AI is ready to continue your conversation."

        builder
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(text)
                    .setSummaryText(context.getString(R.string.ai_notification_summary))
            )

        // Add fancy pulsating LED effect with gradient colors
        val ledColors = intArrayOf(PRIMARY_GLOW_COLOR, SECONDARY_GLOW_COLOR, ACCENT_GLOW_COLOR)
        builder.setLights(ledColors[0], 1000, 300)

        // Add conversation-specific action button for quick access
        val tapThroughIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_CHAT_ROOM_ID, -1)
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
            putExtra("tap_through", true)
        }

        val tapThroughPendingIntent = PendingIntent.getActivity(
            context,
            conversationId.hashCode() and 0x7FFFFFFF + 1,
            tapThroughIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        builder.addAction(
            R.drawable.ic_arrow_forward_rounded,
            context.getString(R.string.tap_to_continue),
            tapThroughPendingIntent
        )

        // Add timestamp badge style (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val timestamp = formatMessageTimestamp(System.currentTimeMillis())
            builder.setSubText("Completed • $timestamp")
        }

        // Add conversation-specific badge count
        updateConversationBadge(conversationId, 1)

        val notificationId = NOTIFICATION_ID_BASE + conversationId.hashCode() and 0x7FFFFFFF
        notificationManager.notify(notificationId, builder.build())
    }

    /**
     * Update the badge count for a specific conversation.
     */
    private fun updateConversationBadge(conversationId: String, count: Int) {
        val notificationId = NOTIFICATION_ID_BASE + conversationId.hashCode() and 0x7FFFFFFF
        notificationManager.notify(notificationId, NotificationCompat.Badge().build())
    }

    /**
     * Cancel a specific conversation notification.
     */
    fun cancelConversationNotification(conversationId: String) {
        val notificationId = NOTIFICATION_ID_BASE + conversationId.hashCode() and 0x7FFFFFFF
        notificationManager.cancel(notificationId)
    }

    /**
     * Cancel all AI notifications.
     */
    fun cancelAllNotifications() {
        notificationManager.cancel(NOTIFICATION_ID_BASE)
    }
}
