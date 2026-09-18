package com.example.gpt_mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Notification component for debug mode
 */
object DebugModeNotification {
    
    private const val CHANNEL_ID = "debug_mode_channel"
    private const val CHANNEL_NAME = "Debug Mode"
    private const val NOTIFICATION_ID = 1001
    
    /**
     * Create notification channel for debug mode
     */
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Show debug mode notification
     */
    fun showDebugModeNotification(context: Context, isEnabled: Boolean) {
        createNotificationChannel(context)
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification) // You'll need to add this drawable
            .setContentTitle("AETHERION Debug Mode")
            .setContentText(if (isEnabled) "Debug mode is enabled" else "Debug mode is disabled")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        
        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    /**
     * Show telemetry collection notification
     */
    fun showTelemetryNotification(context: Context, eventCount: Int) {
        createNotificationChannel(context)
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Telemetry Collected")
            .setContentText("$eventCount telemetry events collected")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        
        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }
    
    /**
     * Show error notification
     */
    fun showErrorNotification(context: Context, error: String) {
        createNotificationChannel(context)
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_error)
            .setContentTitle("Debug Mode Error")
            .setContentText(error)
            .setPriority(NotificationCompat.PRIORITY_ERROR)
            .build()
        
        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.notify(NOTIFICATION_ID + 2, notification)
    }
    
    /**
     * Cancel all debug mode notifications
     */
    fun cancelAllNotifications(context: Context) {
        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.cancelAll()
    }
}