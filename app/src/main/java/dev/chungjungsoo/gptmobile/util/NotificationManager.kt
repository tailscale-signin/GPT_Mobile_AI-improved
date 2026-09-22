package dev.chungjungsoo.gptmobile.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build.VERSION_CODES
import android.view.VibrationEffect
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Creates a deep link intent for opening conversations
 */
private fun createConversationIntent(context: Context, chatId: String): Intent {
    val intent = Intent(context, dev.chungjungsoo.gptmobile.presentation.ui.main.MainActivity::class.java)
        .apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("chatRoomId", chatId)
        }
    return PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

/**
 * Creates a notification for AI responses with deep linking
 */
fun createNotification(
    context: Context,
    chatId: String,
    title: String,
    content: String
): NotificationCompat.Builder {
    val pendingIntent = createConversationIntent(context, chatId)
    
    return NotificationCompat.Builder(context, "ai_notifications")
        .setSmallIcon(R.drawable.ic_ai_notification)
        .setContentTitle(title)
        .setContentText(content)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(pendingIntent)
}

/**
 * Creates a notification with vibration effect for completion
 */
fun createNotificationWithVibration(
    context: Context,
    chatId: String,
    title: String,
    content: String
): NotificationCompat.Builder {
    val pendingIntent = createConversationIntent(context, chatId)
    
    return NotificationCompat.Builder(context, "ai_notifications")
        .setSmallIcon(R.drawable.ic_ai_notification)
        .setContentTitle(title)
        .setContentText(content)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(pendingIntent)
        .setVibrate(longArrayOf(0L, 200L, 400L))
}
