package com.example.gpt_mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Broadcast receiver for debug mode related intents
 */
class DebugModeBroadcastReceiver : BroadcastReceiver() {
    
    private val TAG = "DebugModeBroadcastReceiver"
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "Device booted - initializing debug mode")
                // Initialize debug mode components on boot
                initializeDebugMode(context)
            }
            Intent.ACTION_SCREEN_ON -> {
                Log.d(TAG, "Screen turned on")
                // Handle screen on event
            }
            Intent.ACTION_SCREEN_OFF -> {
                Log.d(TAG, "Screen turned off")
                // Handle screen off event
            }
            "com.example.gpt_mobile.DEBUG_MODE_ENABLED" -> {
                Log.d(TAG, "Debug mode enabled via broadcast")
                // Handle debug mode enabled event
                DebugModeNotification.showDebugModeNotification(context, true)
            }
            "com.example.gpt_mobile.DEBUG_MODE_DISABLED" -> {
                Log.d(TAG, "Debug mode disabled via broadcast")
                // Handle debug mode disabled event
                DebugModeNotification.showDebugModeNotification(context, false)
            }
            "com.example.gpt_mobile.TELEMETRY_COLLECTED" -> {
                val eventCount = intent.getIntExtra("event_count", 0)
                Log.d(TAG, "Telemetry collected: $eventCount events")
                // Handle telemetry collection event
                DebugModeNotification.showTelemetryNotification(context, eventCount)
            }
            else -> {
                Log.d(TAG, "Unknown broadcast received: ${intent.action}")
            }
        }
    }
    
    private fun initializeDebugMode(context: Context) {
        // Initialize debug mode components
        // This could include starting services, registering listeners, etc.
        Log.d(TAG, "Initializing debug mode components")
        
        // Example: Start debug mode service
        val serviceIntent = Intent(context, DebugModeService::class.java)
        context.startService(serviceIntent)
    }
}